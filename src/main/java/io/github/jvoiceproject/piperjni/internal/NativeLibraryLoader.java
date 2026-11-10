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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads and provides the native piper-jni library and dependent assets.
 *
 * @author Florian Hotze - Initial contribution
 */
public final class NativeLibraryLoader {
    private static final String LIB_NAME = "piper-jni";

    private static volatile boolean loaded;
    private static volatile Path espeakNGDir;

    private NativeLibraryLoader() {}

    /**
     * Loads the native library and its dependencies if not already loaded.
     *
     * @throws IOException if extracting resources or reading files fails
     * @throws UnsupportedOperationException if OS or architecture is not supported
     * @throws UnsatisfiedLinkError if native libraries cannot be linked
     * @throws LinkageError if version validation fails
     */
    public static void load() throws IOException {
        if (!loaded) {
            synchronized (NativeLibraryLoader.class) {
                if (!loaded) {
                    loadLibraries();
                    espeakNGDir = locateOrExtractESpeakNGData();
                    loaded = true;
                }
            }
        }
    }

    /**
     * Checks whether the native library is loaded.
     *
     * @return {@code true} if the native library is loaded, {@code false} otherwise
     */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void loadLibraries() throws IOException {
        Path libraryPath = locateLibrary();
        loadDependencies(libraryPath.getParent());
        System.load(libraryPath.toAbsolutePath().toString());
        validateVersion(libraryPath);
    }

    private static void validateVersion(Path source) {
        String version;
        try {
            version = PiperBinding.piper_version();
        } catch (Throwable t) {
            throw new LinkageError(
                    "Failed to query piper version from '" + source + "': " + t.getMessage(), t);
        }
        if (version == null || version.isBlank()) {
            throw new LinkageError(
                    "Failed to query piper version from '" + source + "': version is empty");
        }
    }

    static Path locateLibrary() throws IOException {
        String customPath = System.getProperty("piper.library.path");
        if (customPath == null || customPath.isBlank()) {
            customPath = System.getProperty("io.github.jvoiceproject.piperjni.libdir");
        }
        if (customPath != null && !customPath.isBlank()) {
            Path p = Path.of(customPath);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            if (Files.isDirectory(p)) {
                Path direct = p.resolve(getLibraryFileName());
                if (Files.isRegularFile(direct)) {
                    return direct.toAbsolutePath();
                }
                throw new FileNotFoundException(
                        "Configured native library directory does not contain '"
                                + getLibraryFileName()
                                + "': "
                                + customPath);
            }
            throw new FileNotFoundException(
                    "Configured native library path does not exist: " + customPath);
        }

        return extractFromResources();
    }

    private static Path extractFromResources() throws IOException {
        Platform platform = Platform.current();
        String resourcePath = platform.resourcePath();
        if (NativeLibraryLoader.class.getResource(resourcePath) == null) {
            throw new FileNotFoundException(
                    "Bundled native library not found on classpath: "
                            + resourcePath
                            + "\n"
                            + "Build the native library first or specify -Dpiper.library.path=...");
        }

        Path targetDir = createManagedTempDir("piper-jni-native-");
        try {
            for (String depName : platform.deps()) {
                copyResource(platform.resourceDir() + "/" + depName, targetDir.resolve(depName));
            }
            Path target = targetDir.resolve(platform.libraryFileName());
            copyResource(resourcePath, target);
            return target.toAbsolutePath();
        } catch (Throwable t) {
            deleteQuietly(targetDir);
            throw t;
        }
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found on classpath: " + resource);
            }
            try (OutputStream os = Files.newOutputStream(target)) {
                is.transferTo(os);
            }
        }
    }

    private static void loadDependencies(Path libDir) {
        if (libDir == null || !Files.isDirectory(libDir)) {
            return;
        }
        for (String depName : getPlatformDependentLibraries()) {
            Path depPath = libDir.resolve(depName);
            if (Files.isRegularFile(depPath)) {
                System.load(depPath.toAbsolutePath().toString());
            }
        }
    }

    /**
     * Returns the eSpeak NG data directory path that was resolved when {@link #load()} was called,
     * or {@code null} if not loaded or not available.
     *
     * @return eSpeak NG data directory path, or {@code null}
     */
    public static Path getLoadedESpeakNGData() {
        return espeakNGDir;
    }

    /**
     * Get the eSpeak NG data directory path, locating or extracting it if needed.
     *
     * @return eSpeak NG data directory path, or {@code null} if not found
     * @throws IOException if extracting eSpeak NG data fails
     */
    public static Path getESpeakNGData() throws IOException {
        Path path = espeakNGDir;
        if (path == null || !Files.exists(path)) {
            synchronized (NativeLibraryLoader.class) {
                path = espeakNGDir;
                if (path == null || !Files.exists(path)) {
                    path = locateOrExtractESpeakNGData();
                    espeakNGDir = path;
                }
            }
        }
        return path;
    }

    static Path locateOrExtractESpeakNGData() throws IOException {
        String customPath = System.getProperty("piper.espeak.data.path");
        if (customPath != null && !customPath.isBlank()) {
            Path p = Path.of(customPath);
            if (!Files.isDirectory(p)) {
                throw new FileNotFoundException(
                        "Configured eSpeak NG data directory does not exist or is not a directory: "
                                + customPath);
            }
            return p.toAbsolutePath();
        }

        String zipResource = "/espeak-ng-data.zip";
        if (NativeLibraryLoader.class.getResource(zipResource) == null) {
            return null;
        }

        Path tempDir = createManagedTempDir("piper-jni-espeak-");
        try {
            Path tempZip = tempDir.resolve("espeak-ng-data.zip");
            copyResource(zipResource, tempZip);
            Path destDir = tempDir.resolve("espeak-ng-data");
            extractZipTo(tempZip, destDir);
            Files.deleteIfExists(tempZip);
            return destDir.toAbsolutePath();
        } catch (Throwable t) {
            deleteQuietly(tempDir);
            throw t;
        }
    }

    static void extractZipTo(Path archiveFile, Path destPath) throws IOException {
        Files.createDirectories(destPath);
        Path normalizedDest = destPath.toAbsolutePath().normalize();
        try (ZipFile archive = new ZipFile(archiveFile.toFile())) {
            var entries = archive.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                Path entryDest = normalizedDest.resolve(entry.getName()).normalize();
                if (!entryDest.startsWith(normalizedDest)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryDest);
                } else {
                    Files.createDirectories(entryDest.getParent());
                    Files.copy(
                            archive.getInputStream(entry),
                            entryDest,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path createManagedTempDir(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        try {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> deleteQuietly(dir),
                                    "piper-jni-cleanup-" + dir.getFileName()));
        } catch (IllegalStateException e) {
            deleteQuietly(dir);
            throw new IOException(
                    "Failed to register cleanup hook because JVM is shutting down", e);
        }
        return dir;
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }

    static String getPlatformResourceDirectory() {
        return Platform.current().resourceDir();
    }

    static String getPlatformResourcePath() {
        return Platform.current().resourcePath();
    }

    static List<String> getPlatformDependentLibraries() {
        return Platform.current().deps();
    }

    static String getLibraryFileName() {
        return Platform.current().libraryFileName();
    }

    static String getLibraryExtension() {
        return Platform.current().ext();
    }

    record Platform(String os, String arch, String ext, String libPrefix, List<String> deps) {
        private static volatile Platform current;

        static Platform current() {
            Platform p = current;
            if (p == null) {
                p = detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
                current = p;
            }
            return p;
        }

        static Platform detect(String rawOsName, String rawArchName) {
            String osName = rawOsName.toLowerCase(Locale.ROOT);
            String archName = rawArchName.toLowerCase(Locale.ROOT);

            String os;
            String ext;
            String prefix;
            List<String> deps;

            if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
                os = "linux";
                ext = ".so";
                prefix = "lib";
                deps = List.of("libonnxruntime_providers_shared.so", "libonnxruntime.so");
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                os = "macos";
                ext = ".dylib";
                prefix = "lib";
                deps = List.of("libonnxruntime.dylib");
            } else if (osName.contains("win")) {
                os = "windows";
                ext = ".dll";
                prefix = "";
                deps = List.of("onnxruntime_providers_shared.dll", "onnxruntime.dll");
            } else {
                throw new UnsupportedOperationException("Unsupported operating system: " + osName);
            }

            String arch;
            if (archName.equals("x86_64")
                    || archName.equals("amd64")
                    || archName.equals("x86-64")) {
                arch = "x86-64";
            } else if (archName.equals("aarch64") || archName.equals("arm64")) {
                arch = "aarch64";
            } else {
                throw new UnsupportedOperationException("Unsupported architecture: " + archName);
            }

            return new Platform(os, arch, ext, prefix, deps);
        }

        String resourceDir() {
            return "/native/" + os + "-" + arch;
        }

        String libraryFileName() {
            return libPrefix + LIB_NAME + ext;
        }

        String resourcePath() {
            return resourceDir() + "/" + libraryFileName();
        }
    }
}
