/*
 * Copyright 2019 Databricks, Inc.
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

package org.apache.spark.sql.delta

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.storage.LogStore
import org.apache.spark.sql.delta.util.{FileNames, JsonUtils}
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.streaming.CheckpointFileManager
import org.apache.spark.util.Utils

/**
 * Stats calculated within a snapshot, which we store along individual transactions for
 * verification.
 *
 * @param tableSizeBytes The size of the table in bytes
 * @param numFiles Number of `AddFile` actions in the snapshot
 * @param numMetadata Number of `Metadata` actions in the snapshot
 * @param numProtocol Number of `Protocol` actions in the snapshot
 * @param numTransactions Number of `SetTransaction` actions in the snapshot
 */
case class VersionChecksum(
    tableSizeBytes: Long,
    numFiles: Long,
    numMetadata: Long,
    numProtocol: Long,
    numTransactions: Long)

/**
 * Record the state of the table as a checksum file along with a commit.
 */
trait RecordChecksum extends DeltaLogging {
  val deltaLog: DeltaLog
  protected def spark: SparkSession

  private lazy val writer =
    CheckpointFileManager.create(deltaLog.logPath, spark.sessionState.newHadoopConf())

  protected def writeChecksumFile(snapshot: Snapshot): Unit = {
    val version = snapshot.version
    val checksum = VersionChecksum(
      tableSizeBytes = snapshot.sizeInBytes,
      numFiles = snapshot.numOfFiles,
      numMetadata = snapshot.numOfMetadata,
      numProtocol = snapshot.numOfProtocol,
      numTransactions = snapshot.numOfSetTransactions)
    try {
      recordDeltaOperation(deltaLog, "delta.checksum.write") {
        val stream = writer.createAtomic(
          FileNames.checksumFile(deltaLog.logPath, version),
          overwriteIfPossible = false)
        try {
          val toWrite = JsonUtils.toJson(checksum) + "\n"
          stream.write(toWrite.getBytes(UTF_8))
          stream.close()
        } catch {
          case NonFatal(e) =>
            logWarning(s"Failed to write the checksum for version: $version", e)
            stream.cancel()
        }
      }
    } catch {
      case NonFatal(e) =>
        logWarning(s"Failed to write the checksum for version: $version", e)
    }
  }
}

/**
 * Verify the state of the table using the checksum files.
 */
trait VerifyChecksum extends DeltaLogging {
  self: DeltaLog =>

  val logPath: Path
  private[delta] def store: LogStore

  protected def validateChecksum(snapshot: Snapshot): Unit = {
    val version = snapshot.version
    val checksumFile = FileNames.checksumFile(logPath, version)
    val content = try Some(store.read(checksumFile)) catch {
      case _: FileNotFoundException =>
        None
    }

    if (content.isEmpty) {
      // We may not find the checksum file in two cases:
      //  - We just upgraded our Spark version from an old one
      //  - Race conditions where we commit a transaction, and before we can write the checksum
      //    this reader lists the new version, and uses it to create the snapshot.
      recordDeltaEvent(
        this,
        "delta.checksum.error.missing",
        data = Map("version" -> version))

      return
    }
    val checksumData = content.get
    if (checksumData.isEmpty) {
      recordDeltaEvent(
        this,
        "delta.checksum.error.empty",
        data = Map("version" -> version))
      return
    }
    var mismatchStringOpt: Option[String] = None
    try {
      val checksum = JsonUtils.mapper.readValue[VersionChecksum](checksumData.head)
      mismatchStringOpt = checkMismatch(checksum, snapshot)
    } catch {
      case NonFatal(e) =>
        recordDeltaEvent(
          this,
          "delta.checksum.error.parsing",
          data = Map("exception" -> Utils.exceptionString(e)))
    }
    if (mismatchStringOpt.isDefined) {
      // Report the failure to usage logs.
      recordDeltaEvent(
        this,
        "delta.checksum.invalid",
        data = Map("error" -> mismatchStringOpt.get))
      // We get the active SparkSession, which may be different than the SparkSession of the
      // Snapshot that was created, since we cache `DeltaLog`s.
      val spark = SparkSession.getActiveSession.getOrElse {
        throw new IllegalStateException("Active SparkSession not set.")
      }
      val conf = DeltaSQLConf.DELTA_STATE_CORRUPTION_IS_FATAL
      if (spark.sessionState.conf.getConf(conf)) {
        throw new IllegalStateException(
          "The transaction log has failed integrity checks. We recommend you contact " +
            s"Databricks support for assistance. To disable this check, set ${conf.key} to " +
            s"false. Failed verification of:\n${mismatchStringOpt.get}"
        )
      }
    }
  }

  private def checkMismatch(checksum: VersionChecksum, snapshot: Snapshot): Option[String] = {
    val result = new ArrayBuffer[String]()
    def compare(expected: Long, found: Long, title: String): Unit = {
      if (expected != found) {
        result += s"$title - Expected: $expected Computed: $found"
      }
    }
    compare(checksum.tableSizeBytes, snapshot.sizeInBytes, "Table size (bytes)")
    compare(checksum.numFiles, snapshot.numOfFiles, "Number of files")
    compare(checksum.numMetadata, snapshot.numOfMetadata, "Metadata updates")
    compare(checksum.numProtocol, snapshot.numOfProtocol, "Protocol updates")
    compare(checksum.numTransactions, snapshot.numOfSetTransactions, "Transactions")
    if (result.isEmpty) None else Some(result.mkString("\n"))
  }
}