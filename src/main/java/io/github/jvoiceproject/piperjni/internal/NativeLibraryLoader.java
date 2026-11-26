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
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ai.onnxruntime.OrtEnvironment;

/**
 * Loads and provides the native piper-jni library and dependent assets.
 *
 * @author Florian Hotze - Initial contribution
 */
public final class NativeLibraryLoader {
    private static final String LIB_NAME = "piper-jni";
    private static final String ORT_PROPERTIES = "onnxruntime.properties";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Minimum ONNX Runtime version required at runtime, generated from {@code
     * <onnxruntime.version>} in {@code pom.xml}.
     */
    static final String MIN_ONNX_RUNTIME_VERSION = readMinOnnxRuntimeVersion();

    private static volatile boolean loaded;
    private static volatile Path espeakNGDir;
    private static volatile String onnxRuntimeVersion;

    /**
     * Path of the native library once {@link System#load(String)} succeeded. Kept so that a retry
     * of {@link #load()} after a later failure does not load a second copy of the native library.
     * Guarded by the class lock.
     */
    private static Path nativeLibraryPath;

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

    /**
     * Returns the ONNX Runtime version bound when {@link #load()} was called, or {@code null} if
     * not loaded.
     *
     * @return loaded ONNX Runtime version string, or {@code null}
     */
    public static String getLoadedOnnxRuntimeVersion() {
        return onnxRuntimeVersion;
    }

    /**
     * Loads the native library and initializes ONNX Runtime. Each stage is performed only once, so
     * calling this again after a failure only retries the stages that have not completed yet. Must
     * be called while holding the class lock.
     */
    private static void loadLibraries() throws IOException {
        if (onnxRuntimeVersion == null) {
            LinkageError ortJavaFailure = ensureOnnxRuntimeLoaded();
            loadNativeLibrary();
            String ortVersion;
            try {
                ortVersion = PiperBinding.piper_init_onnxruntime();
            } catch (UnsatisfiedLinkError e) {
                UnsatisfiedLinkError error =
                        new UnsatisfiedLinkError(
                                e.getMessage()
                                        + " (piper-jni requires ONNX Runtime "
                                        + MIN_ONNX_RUNTIME_VERSION
                                        + " or newer)");
                error.initCause(e);
                if (ortJavaFailure != null) {
                    error.addSuppressed(ortJavaFailure);
                }
                throw error;
            }
            if (ortVersion == null || ortVersion.isBlank()) {
                throw new LinkageError("Failed to initialize ONNX Runtime API: version is empty");
            }
            checkOnnxRuntimeVersion(ortVersion);
            onnxRuntimeVersion = ortVersion;
        }
        validateVersion(nativeLibraryPath);
    }

    private static Path loadNativeLibrary() throws IOException {
        if (nativeLibraryPath == null) {
            Path libraryPath = locateLibrary();
            System.load(libraryPath.toAbsolutePath().toString());
            nativeLibraryPath = libraryPath;
        }
        return nativeLibraryPath;
    }

    /**
     * Makes the ONNX Runtime Java API ({@code com.microsoft.onnxruntime:onnxruntime}) load its
     * native library into the process, so that piper-jni can bind to it. Which library is loaded
     * can be controlled with ONNX Runtime's own system properties {@code onnxruntime.native.path}
     * and {@code onnxruntime.native.onnxruntime.path}.
     *
     * @return {@code null} on success, or the error if the ONNX Runtime Java API is not available
     *     or failed to initialize; in that case piper-jni falls back to discovering an ONNX Runtime
     *     library that has been loaded into the process by other means
     */
    private static LinkageError ensureOnnxRuntimeLoaded() {
        String version;
        try {
            version = OrtJava.loadAndGetVersion();
        } catch (LinkageError e) {
            return e;
        }
        // Fail early, before loading the native library, if the ONNX Runtime is too old
        checkOnnxRuntimeVersion(version);
        return null;
    }

    /** Isolates references to the optional ONNX Runtime Java API. */
    private static final class OrtJava {
        private OrtJava() {}

        static String loadAndGetVersion() {
            return OrtEnvironment.getEnvironment().getVersion();
        }
    }

    /**
     * Checks that the given ONNX Runtime version is at least {@link #MIN_ONNX_RUNTIME_VERSION}.
     *
     * @param version ONNX Runtime version string
     * @throws UnsatisfiedLinkError if the version is older than the minimum or cannot be parsed
     */
    static void checkOnnxRuntimeVersion(String version) {
        if (compareVersions(version, MIN_ONNX_RUNTIME_VERSION) < 0) {
            throw new UnsatisfiedLinkError(
                    "ONNX Runtime version '"
                            + version
                            + "' is older than the minimum version "
                            + MIN_ONNX_RUNTIME_VERSION
                            + " required by piper-jni");
        }
    }

    /**
     * Compares two {@code major.minor[.patch]} version strings; trailing suffixes are ignored.
     *
     * @throws UnsatisfiedLinkError if a version cannot be parsed
     */
    static int compareVersions(String a, String b) {
        int[] va = parseVersion(a);
        int[] vb = parseVersion(b);
        for (int i = 0; i < va.length; i++) {
            int c = Integer.compare(va[i], vb[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        Matcher m = version == null ? null : VERSION_PATTERN.matcher(version.trim());
        if (m == null || !m.find()) {
            throw new UnsatisfiedLinkError(
                    "Unable to parse ONNX Runtime version '" + version + "'");
        }
        try {
            return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) != null ? Integer.parseInt(m.group(3)) : 0
            };
        } catch (NumberFormatException e) {
            UnsatisfiedLinkError error =
                    new UnsatisfiedLinkError(
                            "Unable to parse ONNX Runtime version '" + version + "'");
            error.initCause(e);
            throw error;
        }
    }

    private static String readMinOnnxRuntimeVersion() {
        Properties props = new Properties();
        try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(ORT_PROPERTIES)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Resource " + ORT_PROPERTIES + " not found on classpath");
            }
            props.load(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + ORT_PROPERTIES, e);
        }
        String version = props.getProperty("onnxruntime.min.version");
        if (version == null || version.isBlank() || version.startsWith("${")) {
            throw new IllegalStateException(
                    "Invalid onnxruntime.min.version in " + ORT_PROPERTIES + ": " + version);
        }
        parseVersion(version);
        return version.trim();
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

    static String getLibraryFileName() {
        return Platform.current().libraryFileName();
    }

    static String getLibraryExtension() {
        return Platform.current().ext();
    }

    record Platform(String os, String arch, String ext, String libPrefix) {
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

            if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
                os = "linux";
                ext = ".so";
                prefix = "lib";
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                os = "macos";
                ext = ".dylib";
                prefix = "lib";
            } else if (osName.contains("win")) {
                os = "windows";
                ext = ".dll";
                prefix = "";
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

            return new Platform(os, arch, ext, prefix);
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
