/*
 * Copyright (2020) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.hive

import java.net.URI
import java.util.Locale

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import io.delta.alpine.actions.AddFile
import io.delta.alpine.types._
import io.delta.alpine.{DeltaLog, Snapshot}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{BlockLocation, FileStatus, FileSystem, LocatedFileStatus, Path}
import org.apache.hadoop.hive.metastore.api.MetaException
import org.apache.hadoop.hive.serde2.typeinfo._
import org.apache.hadoop.mapred.JobConf
import org.slf4j.LoggerFactory

object DeltaHelper {

  private val LOG = LoggerFactory.getLogger(getClass.getName)

  def listDeltaFiles(
      nonNormalizedPath: Path,
      job: JobConf): (Array[FileStatus], Map[URI, Array[PartitionColumnInfo]]) = {
    val loadStartMs = System.currentTimeMillis()
    val fs = nonNormalizedPath.getFileSystem(job)
    // We need to normalize the table path so that all paths we return to Hive will be normalized
    // This is necessary because `HiveInputFormat.pushProjectionsAndFilters` will try to figure out
    // which table a split path belongs to by comparing the split path with the normalized (? I have
    // not yet confirmed this) table paths.
    // TODO The assumption about Path in Hive is too strong, we should try to see if we can fail if
    // `pushProjectionsAndFilters` doesn't find a table for a Delta split path.
    val rootPath = fs.makeQualified(nonNormalizedPath)
    val snapshotToUse = loadDeltaLatestSnapshot(job, rootPath)

    val hiveSchema = TypeInfoUtils.getTypeInfoFromTypeString(
      job.get(DeltaStorageHandler.DELTA_TABLE_SCHEMA)).asInstanceOf[StructTypeInfo]
    DeltaHelper.checkTableSchema(snapshotToUse.getMetadata.getSchema, hiveSchema)

    // The default value 128M is the same as the default value of
    // "spark.sql.files.maxPartitionBytes" in Spark. It's also the default parquet row group size
    // which is usually the best split size for parquet files.
    val blockSize = job.getLong("parquet.block.size", 128L * 1024 * 1024)

    val localFileToPartition = mutable.Map[URI, Array[PartitionColumnInfo]]()

    val partitionColumns = snapshotToUse.getMetadata.getPartitionColumns.asScala.toSet
    val partitionColumnWithIndex = snapshotToUse.getMetadata.getSchema.getFields.zipWithIndex
      .filter { case (t, _) =>
        partitionColumns.contains(t.getName)
      }.sortBy(_._2)

    val files = snapshotToUse
      .getAllFiles
      .asScala
      .map { addF =>
        // Drop unused potential huge fields
        val f = new AddFile(addF.getPath, addF.getPartitionValues, addF.getSize,
          addF.getModificationTime, addF.isDataChange, null, null)

        val status = toFileStatus(fs, rootPath, f, blockSize)
        localFileToPartition +=
          status.getPath.toUri -> partitionColumnWithIndex.map { case (t, index) =>
            // TODO Is `catalogString` always correct? We may need to add our own conversion rather
            // than relying on Spark.
            PartitionColumnInfo(
              index,
              t.getDataType.getCatalogString,
              f.getPartitionValues.get(t.getName))
          }
        status
      }

    val loadEndMs = System.currentTimeMillis()
    logOperationDuration("fetching file list", rootPath, snapshotToUse, loadEndMs - loadStartMs)
    LOG.info(s"Found ${files.size} files to process " +
      s"in the Delta Lake table ${hideUserInfoInPath(rootPath)}")

    (files.toArray, localFileToPartition.toMap)
  }

  def getPartitionCols(hadoopConf: Configuration, rootPath: Path): Seq[String] = {
    loadDeltaLatestSnapshot(hadoopConf, rootPath).getMetadata.getPartitionColumns.asScala
  }

  def loadDeltaLatestSnapshot(hadoopConf: Configuration, rootPath: Path): Snapshot = {
    val loadStartMs = System.currentTimeMillis()
    val snapshot = DeltaLog.forTable(hadoopConf, rootPath).update()
    val loadEndMs = System.currentTimeMillis()
    logOperationDuration("loading snapshot", rootPath, snapshot, loadEndMs - loadStartMs)
    if (snapshot.getVersion < 0) {
      throw new MetaException(
        s"${hideUserInfoInPath(rootPath)} does not exist or it's not a Delta table")
    }
    snapshot
  }

  @throws(classOf[MetaException])
  def checkTableSchema(alpineSchema: StructType, hiveSchema: StructTypeInfo): Unit = {
    val alpineType = normalizeSparkType(alpineSchema).asInstanceOf[StructType]
    val hiveType = hiveTypeToSparkType(hiveSchema).asInstanceOf[StructType]
    if (alpineType != hiveType) {
      val diffs =
        SchemaUtils.reportDifferences(existingSchema = alpineType, specifiedSchema = hiveType)
      throw metaInconsistencyException(
        alpineSchema,
        hiveSchema,
        diffs.mkString("\n"))
    }
  }

  /**
   * Convert an [[AddFile]] to Hadoop's [[FileStatus]].
   *
   * @param root the table path which will be used to create the real path from relative path.
   */
  private def toFileStatus(fs: FileSystem, root: Path, f: AddFile, blockSize: Long): FileStatus = {
    val status = new FileStatus(
      f.getSize, // length
      false, // isDir
      1, // blockReplication, FileInputFormat doesn't use this
      blockSize, // blockSize
      f.getModificationTime, // modificationTime
      absolutePath(fs, root, f.getPath) // path
    )
    // We don't have `blockLocations` in `AddFile`. However, fetching them by calling
    // `getFileStatus` for each file is unacceptable because that's pretty inefficient and it will
    // make Delta look worse than a parquet table because of these FileSystem RPC calls.
    //
    // But if we don't set the block locations, [[FileInputFormat]] will try to fetch them. Hence,
    // we create a `LocatedFileStatus` with dummy block locations to save FileSystem RPC calls. We
    // lose the locality but this is fine today since most of storage systems are on Cloud and the
    // computation is running separately.
    //
    // An alternative solution is using "listStatus" recursively to get all `FileStatus`s and keep
    // those present in `AddFile`s. This is much cheaper and the performance should be the same as a
    // parquet table. However, it's pretty complicated as we need to be careful to avoid listing
    // unnecessary directories. So we decide to not do this right now.
    val dummyBlockLocations =
      Array(new BlockLocation(Array("localhost:50010"), Array("localhost"), 0, f.getSize))
    new LocatedFileStatus(status, dummyBlockLocations)
  }

  /**
   * Create an absolute [[Path]] from `child` using the `root` path if `child` is a relative path.
   * Return a [[Path]] version of child` if it is an absolute path.
   *
   * @param child an escaped string read from Delta's [[AddFile]] directly which requires to
   *              unescape before creating the [[Path]] object.
   */
  private def absolutePath(fs: FileSystem, root: Path, child: String): Path = {
    val p = new Path(new URI(child))
    if (p.isAbsolute) {
      fs.makeQualified(p)
    } else {
      new Path(root, p)
    }
  }

  /**
   * Normalize the Spark type so that we can compare it with user specified Hive schema.
   * - Field names will be converted to lower case.
   * - Nullable will be set to `true` since Hive doesn't support non-null fields.
   */
  private def normalizeSparkType(sparkType: DataType): DataType = {
    sparkType match {
      case structType: StructType =>
        new StructType(structType.getFields.map(f => new StructField(
          f.getName.toLowerCase(Locale.ROOT),
          normalizeSparkType(f.getDataType)
        )))
      case arrayType: ArrayType =>
        new ArrayType(normalizeSparkType(arrayType.getElementType), true)
      case mapType: MapType =>
        new MapType(
          normalizeSparkType(mapType.getKeyType),
          normalizeSparkType(mapType.getValueType),
          true)
      case other => other
    }
  }

  /**
   * Convert a Hive's type to a Spark type so that we can compare it with the underlying Delta Spark
   * type.
   */
  private def hiveTypeToSparkType(hiveType: TypeInfo): DataType = {
    hiveType match {
      case TypeInfoFactory.byteTypeInfo => new ByteType
      case TypeInfoFactory.binaryTypeInfo => new BinaryType
      case TypeInfoFactory.booleanTypeInfo => new BooleanType
      case TypeInfoFactory.intTypeInfo => new IntegerType
      case TypeInfoFactory.longTypeInfo => new LongType
      case TypeInfoFactory.stringTypeInfo => new StringType
      case TypeInfoFactory.floatTypeInfo => new FloatType
      case TypeInfoFactory.doubleTypeInfo => new DoubleType
      case TypeInfoFactory.shortTypeInfo => new ShortType
      case TypeInfoFactory.dateTypeInfo => new DateType
      case TypeInfoFactory.timestampTypeInfo => new TimestampType
      case hiveDecimalType: DecimalTypeInfo =>
        new DecimalType(hiveDecimalType.precision(), hiveDecimalType.scale())
      case hiveListType: ListTypeInfo =>
        new ArrayType(hiveTypeToSparkType(hiveListType.getListElementTypeInfo), true)
      case hiveMapType: MapTypeInfo =>
        new MapType(
          hiveTypeToSparkType(hiveMapType.getMapKeyTypeInfo),
          hiveTypeToSparkType(hiveMapType.getMapValueTypeInfo),
          true)
      case hiveStructType: StructTypeInfo =>
        val size = hiveStructType.getAllStructFieldNames.size
        val fields = (0 until size) map { i =>
          val hiveFieldName = hiveStructType.getAllStructFieldNames.get(i)
          val hiveFieldType = hiveStructType.getAllStructFieldTypeInfos.get(i)
          new StructField(
            hiveFieldName.toLowerCase(Locale.ROOT), hiveTypeToSparkType(hiveFieldType))
        }
        new StructType(fields.toArray)
      case _ =>
        // TODO More Hive types:
        //  - void
        //  - char
        //  - varchar
        //  - intervalYearMonthType
        //  - intervalDayTimeType
        //  - UnionType
        //  - Others?
        throw new UnsupportedOperationException(s"Hive type $hiveType is not supported")
    }
  }

  private def metaInconsistencyException(
      deltaSchema: StructType,
      hiveSchema: StructTypeInfo,
      diffs: String): MetaException = {
    val hiveSchemaString = hiveSchema.getAllStructFieldNames
      .asScala
      .zip(hiveSchema.getAllStructFieldTypeInfos.asScala.map(_.getTypeName))
      .map(_.productIterator.mkString(": "))
      .mkString("\n")
    new MetaException(
      s"""The Delta table schema is not the same as the Hive schema:
         |
         |$diffs
         |
         |Delta table schema:
         |${deltaSchema.getTreeString}
         |
         |Hive schema:
         |$hiveSchemaString
         |
         |Please update your Hive table's schema to match the Delta table schema.""".stripMargin)
  }

  private def logOperationDuration(
    ops: String,
    path: Path,
    snapshot: Snapshot,
    durationMs: Long): Unit = {
    LOG.info(s"Delta Lake table '${hideUserInfoInPath(path)}' (" +
      s"version: ${snapshot.getVersion}, " +
      s"add: ${snapshot.getNumOfFiles}, " +
      s"partitions: ${snapshot.getMetadata.getPartitionColumns.asScala.mkString("[", ", ", "]")}" +
      s") spent ${durationMs} ms on $ops.")
  }

  /** Strip out user information to avoid printing credentials to logs. */
  private def hideUserInfoInPath(path: Path): Path = {
    try {
      val uri = path.toUri
      val newUri = new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath,
        uri.getQuery, uri.getFragment)
      new Path(newUri)
    } catch {
      case NonFatal(e) =>
        // This path may have illegal format, and we can not remove its user info and reassemble the
        // uri.
        LOG.error("Path contains illegal format: " + path, e)
        path
    }
  }
}
