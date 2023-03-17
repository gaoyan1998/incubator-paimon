/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.file.manifest;

import org.apache.paimon.file.io.RollingFileWriter;
import org.apache.paimon.file.io.SingleFileWriter;
import org.apache.paimon.file.schema.SchemaManager;
import org.apache.paimon.file.stats.FieldStatsArraySerializer;
import org.apache.paimon.file.utils.FileStorePathFactory;
import org.apache.paimon.file.utils.FileUtils;
import org.apache.paimon.file.utils.VersionedObjectSerializer;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.types.RowType;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.format.FieldStatsCollector;
import org.apache.paimon.format.FileFormat;
import org.apache.paimon.format.FormatReaderFactory;
import org.apache.paimon.format.FormatWriterFactory;

import java.io.IOException;
import java.util.List;

/**
 * This file includes several {@link ManifestEntry}s, representing the additional changes since last
 * snapshot.
 */
public class ManifestFile {

    private final FileIO fileIO;
    private final SchemaManager schemaManager;
    private final RowType partitionType;
    private final ManifestEntrySerializer serializer;
    private final FormatReaderFactory readerFactory;
    private final FormatWriterFactory writerFactory;
    private final FileStorePathFactory pathFactory;
    private final long suggestedFileSize;

    private ManifestFile(
            FileIO fileIO,
            SchemaManager schemaManager,
            RowType partitionType,
            ManifestEntrySerializer serializer,
            FormatReaderFactory readerFactory,
            FormatWriterFactory writerFactory,
            FileStorePathFactory pathFactory,
            long suggestedFileSize) {
        this.fileIO = fileIO;
        this.schemaManager = schemaManager;
        this.partitionType = partitionType;
        this.serializer = serializer;
        this.readerFactory = readerFactory;
        this.writerFactory = writerFactory;
        this.pathFactory = pathFactory;
        this.suggestedFileSize = suggestedFileSize;
    }

    @VisibleForTesting
    public long suggestedFileSize() {
        return suggestedFileSize;
    }

    public List<ManifestEntry> read(String fileName) {
        try {
            return FileUtils.readListFromFile(
                    fileIO, pathFactory.toManifestFilePath(fileName), serializer, readerFactory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read manifest file " + fileName, e);
        }
    }

    /**
     * Write several {@link ManifestEntry}s into manifest files.
     *
     * <p>NOTE: This method is atomic.
     */
    public List<ManifestFileMeta> write(List<ManifestEntry> entries) {
        RollingFileWriter<ManifestEntry, ManifestFileMeta> writer =
                new RollingFileWriter<>(
                        () -> new ManifestEntryWriter(writerFactory, pathFactory.newManifestFile()),
                        suggestedFileSize);
        try {
            writer.write(entries);
            writer.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return writer.result();
    }

    public void delete(String fileName) {
        fileIO.deleteQuietly(pathFactory.toManifestFilePath(fileName));
    }

    private class ManifestEntryWriter extends SingleFileWriter<ManifestEntry, ManifestFileMeta> {

        private final FieldStatsCollector partitionStatsCollector;
        private final FieldStatsArraySerializer partitionStatsSerializer;

        private long numAddedFiles = 0;
        private long numDeletedFiles = 0;
        private long schemaId = Long.MIN_VALUE;

        ManifestEntryWriter(FormatWriterFactory factory, Path path) {
            super(ManifestFile.this.fileIO, factory, path, serializer::toRow, null);

            this.partitionStatsCollector = new FieldStatsCollector(partitionType);
            this.partitionStatsSerializer = new FieldStatsArraySerializer(partitionType);
        }

        @Override
        public void write(ManifestEntry entry) throws IOException {
            super.write(entry);

            switch (entry.kind()) {
                case ADD:
                    numAddedFiles++;
                    break;
                case DELETE:
                    numDeletedFiles++;
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown entry kind: " + entry.kind());
            }
            schemaId = Math.max(schemaId, entry.file().schemaId());

            partitionStatsCollector.collect(entry.partition());
        }

        @Override
        public ManifestFileMeta result() throws IOException {
            return new ManifestFileMeta(
                    path.getName(),
                    fileIO.getFileSize(path),
                    numAddedFiles,
                    numDeletedFiles,
                    partitionStatsSerializer.toBinary(partitionStatsCollector.extract()),
                    numAddedFiles + numDeletedFiles > 0
                            ? schemaId
                            : schemaManager.latest().get().id());
        }
    }

    /** Creator of {@link ManifestFile}. */
    public static class Factory {

        private final FileIO fileIO;
        private final SchemaManager schemaManager;
        private final RowType partitionType;
        private final FileFormat fileFormat;
        private final FileStorePathFactory pathFactory;
        private final long suggestedFileSize;

        public Factory(
                FileIO fileIO,
                SchemaManager schemaManager,
                RowType partitionType,
                FileFormat fileFormat,
                FileStorePathFactory pathFactory,
                long suggestedFileSize) {
            this.fileIO = fileIO;
            this.schemaManager = schemaManager;
            this.partitionType = partitionType;
            this.fileFormat = fileFormat;
            this.pathFactory = pathFactory;
            this.suggestedFileSize = suggestedFileSize;
        }

        public ManifestFile create() {
            RowType entryType = VersionedObjectSerializer.versionType(ManifestEntry.schema());
            return new ManifestFile(
                    fileIO,
                    schemaManager,
                    partitionType,
                    new ManifestEntrySerializer(),
                    fileFormat.createReaderFactory(entryType),
                    fileFormat.createWriterFactory(entryType),
                    pathFactory,
                    suggestedFileSize);
        }
    }
}