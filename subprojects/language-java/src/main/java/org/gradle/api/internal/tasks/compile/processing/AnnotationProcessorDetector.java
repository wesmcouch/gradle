/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.compile.processing;

import com.google.common.base.Charsets;
import com.google.common.base.Enums;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipFile;
import org.gradle.api.UncheckedIOException;
import org.gradle.cache.internal.FileContentCache;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.internal.FileUtils;
import org.gradle.internal.file.FileType;
import org.gradle.internal.serialize.ListSerializer;
import org.gradle.util.DeprecationLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Inspects a classpath to find annotation processors contained in it.
 */
public class AnnotationProcessorDetector {

    public static final String PROCESSOR_DECLARATION = "META-INF/services/javax.annotation.processing.Processor";
    public static final String INCREMENTAL_PROCESSOR_DECLARATION = "META-INF/gradle/incremental.annotation.processors";

    private final FileContentCache<List<AnnotationProcessorDeclaration>> cache;

    public AnnotationProcessorDetector(FileContentCacheFactory cacheFactory) {
        cache = cacheFactory.newCache("annotation-processors", 20000, new ProcessorServiceLocator(), new ListSerializer<AnnotationProcessorDeclaration>(new AnnotationProcessorDeclarationSerializer()));
    }

    public List<AnnotationProcessorDeclaration> detectProcessors(Iterable<File> processorPath) {
        List<AnnotationProcessorDeclaration> processors = Lists.newArrayList();
        for (File jarOrClassesDir : processorPath) {
            processors.addAll(cache.get(jarOrClassesDir));
        }
        return processors;
    }

    private static class ProcessorServiceLocator implements FileContentCacheFactory.Calculator<List<AnnotationProcessorDeclaration>> {

        @Override
        public List<AnnotationProcessorDeclaration> calculate(File file, FileType fileType) {
            if (fileType == FileType.Directory) {
                return detectProcessorsInClassesDir(file);
            }
            if (fileType == FileType.RegularFile && FileUtils.hasExtensionIgnoresCase(file.getName(), ".jar")) {
                return detectProcessorsInJar(file);
            }
            return Collections.emptyList();
        }

        private List<AnnotationProcessorDeclaration> detectProcessorsInClassesDir(File classesDir) {
            return toProcessorDeclarations(getProcessorClassNames(classesDir), getProcessorTypes(classesDir));
        }


        private List<String> getProcessorClassNames(File classesDir) {
            File processorDeclaration = new File(classesDir, PROCESSOR_DECLARATION);
            if (!processorDeclaration.isFile()) {
                return Collections.emptyList();
            }
            return readLines(processorDeclaration);
        }

        private Map<String, IncrementalAnnotationProcessorType> getProcessorTypes(File classesDir) {
            File incrementalProcessorDeclaration = new File(classesDir, INCREMENTAL_PROCESSOR_DECLARATION);
            if (!incrementalProcessorDeclaration.isFile()) {
                return Collections.emptyMap();
            }
            List<String> lines = readLines(incrementalProcessorDeclaration);
            return parseIncrementalProcessors(lines);
        }

        private List<String> readLines(File file) {
            try {
                return Files.readLines(file, Charsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private List<AnnotationProcessorDeclaration> detectProcessorsInJar(File jar) {
            try {
                ZipFile zipFile = new ZipFile(jar);
                try {
                    return detectProcessorsInZipFile(zipFile);
                } finally {
                    zipFile.close();
                }
            } catch (IOException e) {
                DeprecationLogger.nagUserWith("Malformed jar [" + jar.getName() + "] found on compile classpath. Gradle 5.0 will no longer allow malformed jars on the compile classpath.");
                return Collections.emptyList();
            }
        }

        private List<AnnotationProcessorDeclaration> detectProcessorsInZipFile(ZipFile zipFile) throws IOException {
            return toProcessorDeclarations(getProcessorClassNames(zipFile), getProcessorTypes(zipFile));
        }

        private List<String> getProcessorClassNames(ZipFile zipFile) throws IOException {
            ZipEntry processorDeclaration = zipFile.getEntry(PROCESSOR_DECLARATION);
            if (processorDeclaration == null) {
                return Collections.emptyList();
            }
            return readLines(zipFile, processorDeclaration);
        }

        private Map<String, IncrementalAnnotationProcessorType> getProcessorTypes(ZipFile zipFile) throws IOException {
            ZipEntry incrementalProcessorDeclaration = zipFile.getEntry(INCREMENTAL_PROCESSOR_DECLARATION);
            if (incrementalProcessorDeclaration == null) {
                return Collections.emptyMap();
            }
            List<String> lines = readLines(zipFile, incrementalProcessorDeclaration);
            return parseIncrementalProcessors(lines);
        }

        private List<String> readLines(ZipFile zipFile, ZipEntry zipEntry) throws IOException {
            InputStream in = zipFile.getInputStream(zipEntry);
            try {
                return CharStreams.readLines(new InputStreamReader(in, Charsets.UTF_8));
            } finally {
                in.close();
            }
        }

        private Map<String, IncrementalAnnotationProcessorType> parseIncrementalProcessors(List<String> lines) {
            Map<String, IncrementalAnnotationProcessorType> types = Maps.newHashMap();
            for (String line : lines) {
                List<String> parts = Splitter.on('=').splitToList(line);
                IncrementalAnnotationProcessorType type = parseProcessorType(parts);
                types.put(parts.get(0), type);
            }
            return types;
        }

        private IncrementalAnnotationProcessorType parseProcessorType(List<String> parts) {
            return Enums.getIfPresent(IncrementalAnnotationProcessorType.class, parts.get(1).toUpperCase()).or(IncrementalAnnotationProcessorType.UNKNOWN);
        }

        private List<AnnotationProcessorDeclaration> toProcessorDeclarations(List<String> processorNames, Map<String, IncrementalAnnotationProcessorType> processorTypes) {
            if (processorNames.isEmpty()) {
                return Collections.emptyList();
            }
            ImmutableList.Builder<AnnotationProcessorDeclaration> processors = ImmutableList.builder();
            for (String name : processorNames) {
                IncrementalAnnotationProcessorType type = processorTypes.get(name);
                type = type != null ? type : IncrementalAnnotationProcessorType.UNKNOWN;
                processors.add(new AnnotationProcessorDeclaration(name, type));
            }
            return processors.build();
        }
    }
}
