/*
 * Copyright (C) 2021 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.scanner.storages

import com.fasterxml.jackson.module.kotlin.readValue

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.model.ArtifactProvenance
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.scanner.ProvenanceBasedScanStorage
import org.ossreviewtoolkit.scanner.ScanStorageException
import org.ossreviewtoolkit.scanner.ScannerMatcher
import org.ossreviewtoolkit.scanner.utils.requireEmptyVcsPath
import org.ossreviewtoolkit.utils.common.collectMessages
import org.ossreviewtoolkit.utils.common.fileSystemEncode
import org.ossreviewtoolkit.utils.ort.showStackTrace
import org.ossreviewtoolkit.utils.ort.storage.FileStorage

class ProvenanceBasedFileStorage(private val backend: FileStorage) : ProvenanceBasedScanStorage {
    override fun read(provenance: KnownProvenance, scannerMatcher: ScannerMatcher?): List<ScanResult> {
        requireEmptyVcsPath(provenance)

        val path = storagePath(provenance)

        return runCatching {
            backend.read(path).use { input ->
                yamlMapper.readValue<List<ScanResult>>(input).map {
                    // Use the provided provenance for the result instead of building it from the stored values, because
                    // in the case of a RepositoryRevision only the resolved revision matters.
                    it.copy(provenance = provenance)
                }.filter { scannerMatcher?.matches(it.scanner) != false }
            }
        }.getOrElse {
            when (it) {
                is FileNotFoundException -> {
                    // If the file cannot be found it means no scan results have been stored, yet.
                    emptyList()
                }

                else -> {
                    logger.info {
                        "Could not read scan results for '$provenance' from path '$path': " +
                            it.collectMessages()
                    }

                    // TODO: Propagate error.
                    emptyList()
                }
            }
        }
    }

    override fun write(scanResult: ScanResult): Boolean {
        val provenance = scanResult.provenance

        requireEmptyVcsPath(provenance)

        if (provenance !is KnownProvenance) {
            throw ScanStorageException("Scan result must have a known provenance, but it is $provenance.")
        }

        val existingScanResults = read(provenance)

        if (existingScanResults.any { it.matches(scanResult) }) {
            logger.debug {
                "Not writing scan result because storage already contains a result for ${scanResult.provenance} and " +
                    "${scanResult.scanner}."
            }

            return false
        }

        val scanResults = existingScanResults + scanResult

        val path = storagePath(provenance)
        val yamlBytes = yamlMapper.writeValueAsBytes(scanResults)
        val input = ByteArrayInputStream(yamlBytes)

        runCatching {
            backend.write(path, input)
            logger.debug { "Stored scan result for '$provenance' at path '$path'." }
            return true
        }.onFailure {
            when (it) {
                is IllegalArgumentException, is IOException -> {
                    it.showStackTrace()

                    logger.warn {
                        "Could not store scan result for '$provenance' at path '$path': ${it.collectMessages()}"
                    }
                }

                else -> throw it
            }
        }

        return false
    }
}

private fun storagePath(provenance: KnownProvenance) =
    when (provenance) {
        is ArtifactProvenance -> "artifact/${provenance.sourceArtifact.url.fileSystemEncode()}/$SCAN_RESULTS_FILE_NAME"
        is RepositoryProvenance -> {
            "repository/${provenance.vcsInfo.type.toString().fileSystemEncode()}" +
                "/${provenance.vcsInfo.url.fileSystemEncode()}" +
                "/${provenance.resolvedRevision.fileSystemEncode()}" +
                "/$SCAN_RESULTS_FILE_NAME"
        }
    }

private fun ScanResult.matches(other: ScanResult): Boolean {
    val thisProvenance = provenance
    val otherProvenance = other.provenance

    return scanner == other.scanner && when {
        thisProvenance is RepositoryProvenance && otherProvenance is RepositoryProvenance -> {
            thisProvenance.vcsInfo.type == otherProvenance.vcsInfo.type &&
                thisProvenance.vcsInfo.url == otherProvenance.vcsInfo.url &&
                thisProvenance.resolvedRevision == otherProvenance.resolvedRevision
        }

        else -> provenance == otherProvenance
    }
}
