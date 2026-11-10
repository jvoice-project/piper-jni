/*
 * #%L
 * piper-jni
 * %%
 * Copyright (C) 2023 - 2026 Contributors to piper-jni
 * %%
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
 * #L%
 */
package io.github.jvoiceproject.piperjni.internal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NativeLibraryLoader}.
 *
 * @author Florian Hotze - Initial contribution
 */
public class NativeLibraryLoaderTest {

    @Test
    public void testPlatformResourcePaths() {
        String resourceDir = NativeLibraryLoader.getPlatformResourceDirectory();
        assertNotNull(resourceDir);
        assertTrue(
                resourceDir.startsWith("/native/"),
                "Resource directory should start with /native/");

        String resourcePath = NativeLibraryLoader.getPlatformResourcePath();
        assertNotNull(resourcePath);
        assertTrue(
                resourcePath.startsWith(resourceDir + "/"),
                "Resource path should be inside resource directory");

        String libFileName = NativeLibraryLoader.getLibraryFileName();
        assertNotNull(libFileName);
        assertTrue(libFileName.contains("piper-jni"), "Library filename should contain piper-jni");

        String extension = NativeLibraryLoader.getLibraryExtension();
        assertTrue(
                List.of(".so", ".dylib", ".dll").contains(extension),
                "Extension should be valid native extension");
    }

    @Test
    public void testPlatformDependentLibraries() {
        List<String> deps = NativeLibraryLoader.getPlatformDependentLibraries();
        assertNotNull(deps);
        assertFalse(deps.isEmpty(), "Platform dependent libraries should not be empty");
    }

    @Test
    public void testCustomLibraryPathNonExistent() {
        String propKey = "piper.library.path";
        String previous = System.getProperty(propKey);
        try {
            System.setProperty(propKey, "/non/existent/path/libpiper-jni.so");
            assertThrows(FileNotFoundException.class, NativeLibraryLoader::locateLibrary);
        } finally {
            if (previous != null) {
                System.setProperty(propKey, previous);
            } else {
                System.clearProperty(propKey);
            }
        }
    }

    @Test
    public void testCustomLibraryPathDirectoryMissingLibrary() throws IOException {
        String propKey = "piper.library.path";
        String previous = System.getProperty(propKey);
        Path emptyDir = Files.createTempDirectory("piper-test-empty-lib-");
        try {
            System.setProperty(propKey, emptyDir.toString());
            assertThrows(FileNotFoundException.class, NativeLibraryLoader::locateLibrary);
        } finally {
            Files.deleteIfExists(emptyDir);
            if (previous != null) {
                System.setProperty(propKey, previous);
            } else {
                System.clearProperty(propKey);
            }
        }
    }

    @Test
    public void testCustomESpeakDataPathNotADirectory() throws IOException {
        String propKey = "piper.espeak.data.path";
        String previous = System.getProperty(propKey);
        Path tempFile = Files.createTempFile("piper-test-espeak-", ".tmp");
        try {
            System.setProperty(propKey, tempFile.toString());
            assertThrows(
                    FileNotFoundException.class, NativeLibraryLoader::locateOrExtractESpeakNGData);
        } finally {
            Files.deleteIfExists(tempFile);
            if (previous != null) {
                System.setProperty(propKey, previous);
            } else {
                System.clearProperty(propKey);
            }
        }
    }

    @Test
    public void testCustomESpeakDataPathValidDirectory() throws IOException {
        String propKey = "piper.espeak.data.path";
        String previous = System.getProperty(propKey);
        Path tempDir = Files.createTempDirectory("piper-test-espeak-dir-");
        try {
            System.setProperty(propKey, tempDir.toString());
            assertEquals(
                    tempDir.toAbsolutePath(), NativeLibraryLoader.locateOrExtractESpeakNGData());
        } finally {
            Files.deleteIfExists(tempDir);
            if (previous != null) {
                System.setProperty(propKey, previous);
            } else {
                System.clearProperty(propKey);
            }
        }
    }

    @Test
    public void testLegacyLibraryDirProperty() throws IOException {
        String propKey = "io.github.jvoiceproject.piperjni.libdir";
        String previous = System.getProperty(propKey);
        Path tempDir = Files.createTempDirectory("piper-test-legacy-libdir-");
        Path dummyLib = tempDir.resolve(NativeLibraryLoader.getLibraryFileName());
        Files.createFile(dummyLib);
        try {
            System.setProperty(propKey, tempDir.toString());
            assertEquals(dummyLib.toAbsolutePath(), NativeLibraryLoader.locateLibrary());
        } finally {
            Files.deleteIfExists(dummyLib);
            Files.deleteIfExists(tempDir);
            if (previous != null) {
                System.setProperty(propKey, previous);
            } else {
                System.clearProperty(propKey);
            }
        }
    }

    @Test
    public void testPlatformDetectSupportedAndUnsupported() {
        var linuxX64 = NativeLibraryLoader.Platform.detect("Linux", "amd64");
        assertEquals("/native/linux-x86-64/libpiper-jni.so", linuxX64.resourcePath());

        var macArm64 = NativeLibraryLoader.Platform.detect("Mac OS X", "aarch64");
        assertEquals("/native/macos-aarch64/libpiper-jni.dylib", macArm64.resourcePath());

        var winX64 = NativeLibraryLoader.Platform.detect("Windows 11", "x86_64");
        assertEquals("/native/windows-x86-64/piper-jni.dll", winX64.resourcePath());

        assertThrows(
                UnsupportedOperationException.class,
                () -> NativeLibraryLoader.Platform.detect("FreeBSD", "x86_64"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> NativeLibraryLoader.Platform.detect("Linux", "riscv64"));
    }

    @Test
    public void testExtractZipToRejectsZipSlip() throws IOException {
        Path tempZip = Files.createTempFile("piper-test-zipslip-", ".zip");
        Path destDir = Files.createTempDirectory("piper-test-zipdest-");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                zos.putNextEntry(new ZipEntry("../evil.txt"));
                zos.write("malicious".getBytes());
                zos.closeEntry();
            }
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () -> NativeLibraryLoader.extractZipTo(tempZip, destDir));
            assertTrue(ex.getMessage().contains("Bad zip entry"));
        } finally {
            Files.deleteIfExists(tempZip);
            Files.deleteIfExists(destDir);
        }
    }
}
