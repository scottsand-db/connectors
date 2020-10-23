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

package io.delta.alpine.internal

import java.math.{BigDecimal => JBigDecimal}
import java.sql.Timestamp
import java.util.{TimeZone, List => JList, Map => JMap}
import java.util.Arrays.{asList => asJList}

import scala.collection.JavaConverters._

import io.delta.alpine.data.{RowRecord => JRowRecord}
import io.delta.alpine.DeltaLog
import io.delta.alpine.internal.sources.AlpineHadoopConf
import io.delta.alpine.internal.util.GoldenTableUtils._
import io.delta.alpine.types.{DateType, StructField, StructType, TimestampType}
import org.apache.hadoop.conf.Configuration
// scalastyle:off funsuite
import org.scalatest.FunSuite

/**
 * Instead of using Spark in this project to WRITE data and log files for tests, we have
 * io.delta.golden.GoldenTables do it instead. During tests, we then refer by name to specific
 * golden tables that that class is responsible for generating ahead of time. This allows us to
 * focus on READING only so that we may fully decouple from Spark and not have it as a dependency.
 *
 * See io.delta.golden.GoldenTables for documentation on how to ensure that the needed files have
 * been generated.
 */
class DeltaDataReaderSuite extends FunSuite {
  // scalastyle:on funsuite

  test("read - primitives") {
    withLogForGoldenTable("data-reader-primitives") { (log, _) =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("as_int")
        assert(row.getLong("as_long") == i.longValue)
        assert(row.getByte("as_byte") == i.toByte)
        assert(row.getShort("as_short") == i.shortValue)
        assert(row.getBoolean("as_boolean") == (i % 2 == 0))
        assert(row.getFloat("as_float") == i.floatValue)
        assert(row.getDouble("as_double") == i.doubleValue)
        assert(row.getString("as_string") == i.toString)
        assert(row.getBinary("as_binary") sameElements Array[Byte](i.toByte, i.toByte))
        assert(row.getBigDecimal("as_big_decimal") == new JBigDecimal(i))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - date types") {
    Seq("UTC", "Iceland", "PST", "America/Los_Angeles", "Etc/GMT+9", "Asia/Beirut",
      "JST").foreach { timeZoneId =>
      withGoldenTable(s"data-reader-date-types-$timeZoneId") { tablePath =>
        val timeZone = TimeZone.getTimeZone(timeZoneId)
        TimeZone.setDefault(timeZone)

        val timestamp = Timestamp.valueOf("2020-01-01 08:09:10")
        val date = java.sql.Date.valueOf("2020-01-01")

        val hadoopConf = new Configuration()
        hadoopConf.set(AlpineHadoopConf.PARQUET_DATA_TIME_ZONE_ID, timeZoneId)

        val log = DeltaLog.forTable(hadoopConf, tablePath)
        val recordIter = log.snapshot().open()

        if (!recordIter.hasNext) fail(s"No row record for timeZoneId $timeZoneId")

        val row = recordIter.next()

        assert(row.getTimestamp("timestamp").equals(timestamp))
        assert(row.getDate("date").equals(date))

        recordIter.close()
      }
    }
  }

  test("read - array of primitives") {
    withLogForGoldenTable("data-reader-array-primitives") { (log, _) =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val list = row.getList[Int]("as_array_int")
        val i = list.get(0)

        assert(row.getList[Long]("as_array_long") == asJList(i.toLong))
        assert(row.getList[Byte]("as_array_byte") == asJList(i.toByte))
        assert(row.getList[Short]("as_array_short") == asJList(i.shortValue))
        assert(row.getList[Boolean]("as_array_boolean") == asJList(i % 2 == 0))
        assert(row.getList[Float]("as_array_float") == asJList(i.floatValue))
        assert(row.getList[Double]("as_array_double") == asJList(i.doubleValue))
        assert(row.getList[String]("as_array_string") == asJList(i.toString))
        assert(row.getList[Array[Byte]]("as_array_binary").get(0) sameElements
          Array(i.toByte, i.toByte))
        assert(row.getList[JBigDecimal]("as_array_big_decimal") == asJList(new JBigDecimal(i)))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - array of complex objects") {
    withLogForGoldenTable("data-reader-array-complex-objects") { (log, _) =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("i")
        assert(
          row.getList[JList[JList[Int]]]("3d_int_list") ==
          asJList(
            asJList(asJList(i, i, i), asJList(i, i, i)),
            asJList(asJList(i, i, i), asJList(i, i, i))
          )
        )

        assert(
          row.getList[JList[JList[JList[Int]]]]("4d_int_list") ==
            asJList(
              asJList(
                asJList(asJList(i, i, i), asJList(i, i, i)),
                asJList(asJList(i, i, i), asJList(i, i, i))
              ),
              asJList(
                asJList(asJList(i, i, i), asJList(i, i, i)),
                asJList(asJList(i, i, i), asJList(i, i, i))
              )
            )
        )

        assert(
          row.getList[JMap[String, Long]]("list_of_maps") ==
          asJList(
            Map[String, Long](i.toString -> i.toLong).asJava,
            Map[String, Long](i.toString -> i.toLong).asJava
          )
        )

        val recordList = row.getList[JRowRecord]("list_of_records")
        recordList.forEach(nestedRow => assert(nestedRow.getInt("val") == i))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - map") {
    withLogForGoldenTable("data-reader-map") { (log, _) =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("i")
        assert(row.getMap[Int, Int]("a").equals(Map(i -> i).asJava))
        assert(row.getMap[Long, Byte]("b").equals(Map(i.toLong -> i.toByte).asJava))
        assert(row.getMap[Short, Boolean]("c").equals(Map(i.toShort -> (i % 2 == 0)).asJava))
        assert(row.getMap[Float, Double]("d").equals(Map(i.toFloat -> i.toDouble).asJava))
        assert(
          row.getMap[String, JBigDecimal]("e").equals(Map(i.toString -> new JBigDecimal(i)).asJava)
        )

        val mapOfRecordList = row.getMap[Int, java.util.List[JRowRecord]]("f")
        val recordList = mapOfRecordList.get(i)
        recordList.forEach(nestedRow => assert(nestedRow.getInt("val") == i))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - nested struct") {
    withLogForGoldenTable("data-reader-nested-struct") { (log, _) =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("b")
        val nestedStruct = row.getRecord("a")
        assert(nestedStruct.getString("aa") == i.toString)
        assert(nestedStruct.getString("ab") == i.toString)

        val nestedNestedStruct = nestedStruct.getRecord("ac")
        assert(nestedNestedStruct.getInt("aca") == i)
        assert(nestedNestedStruct.getLong("acb") == i.toLong)
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - nullable field, invalid schema column key") {
    withLogForGoldenTable("data-reader-nullable-field-invalid-schema-key") { (log, _) =>
      val recordIter = log.snapshot().open()

      if (!recordIter.hasNext) fail(s"No row record")

      val row = recordIter.next()
      row.getList[String]("array_can_contain_null").forEach(elem => assert(elem == null))

      val e = intercept[IllegalArgumentException] {
        row.getInt("foo_key_does_not_exist")
      }
      assert(e.getMessage.contains("No matching schema column for field"))

      recordIter.close()
    }
  }

  test("test escaped char sequences in path") {
    withLogForGoldenTable("data-reader-escaped-chars") { (log, _) =>
      assert(log.snapshot().getAllFiles.asScala.forall(_.getPath.contains("_2=bar")))

      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        assert(row.getString("_1").contains("foo"))
        count += 1
      }

      assert(count == 3)
    }
  }

  test("test bad type cast") {
    withLogForGoldenTable("data-reader-primitives") { (log, _) =>
      val recordIter = log.snapshot().open()
      assertThrows[ClassCastException] {
        val row = recordIter.next()
        row.getString("as_big_decimal")
      }
    }
  }

  test("correct schema and length") {
    withLogForGoldenTable("data-reader-date-types-UTC") { (log, _) =>
      val recordIter = log.snapshot().open()
      if (!recordIter.hasNext) fail(s"No row record")
      val row = recordIter.next()
      assert(row.getLength == 2)

      val expectedSchema = new StructType(Array(
        new StructField("timestamp", new TimestampType),
        new StructField("date", new DateType)
      ))

      assert(row.getSchema == expectedSchema)
    }
  }
}
