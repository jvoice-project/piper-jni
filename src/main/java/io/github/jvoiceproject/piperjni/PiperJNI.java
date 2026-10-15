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
package io.github.jvoiceproject.piperjni;

import io.github.jvoiceproject.piperjni.internal.NativeUtils;
import io.github.jvoiceproject.piperjni.internal.PiperBinding;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Piper JNI
 *
 * <p>This class is thread-safe.
 *
 * @author Miguel Álvarez Díez - Initial contribution
 * @author Florian Hotze - Refactored to use {@link PiperBinding} Made thread-safe
 */
public class PiperJNI {

    private static volatile boolean libraryLoaded;
    private static volatile Path eSpeakDataPath;

    /**
     * Get the Piper version.
     *
     * @return Piper library version.
     */
    public static String getPiperVersion() {
        assertRegistered();
        return PiperBinding.piper_get_version();
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @return a {@link PiperVoice} instance
     * @throws IllegalStateException if the native library is not loaded
     * @throws FileNotFoundException if the model or the configuration file doesn't exist
     */
    public static PiperVoice loadVoice(Path modelPath, Path modelConfigPath)
            throws IllegalStateException, FileNotFoundException {
        return loadVoice(modelPath, modelConfigPath, -1);
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @param speakerId speaker id for multi-speaker voices or <code>-1</code>
     * @return a {@link PiperVoice} instance
     * @throws IllegalStateException if the native library is not loaded
     * @throws FileNotFoundException if the model or the configuration file doesn't exist
     */
    public static PiperVoice loadVoice(Path modelPath, Path modelConfigPath, int speakerId)
            throws IllegalStateException, FileNotFoundException {
        return loadVoice(modelPath, modelConfigPath, speakerId, true);
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @param speakerId speaker id for multi-speaker voices or <code>-1</code>
     * @param useESpeakPhonemes whether to use eSpeak phonemes or not
     * @return a {@link PiperVoice} instance
     * @throws IllegalStateException if the native library is not loaded
     * @throws FileNotFoundException if the model or the configuration file doesn't exist
     */
    public static PiperVoice loadVoice(
            Path modelPath, Path modelConfigPath, int speakerId, boolean useESpeakPhonemes)
            throws IllegalStateException, FileNotFoundException {
        assertRegistered();
        if (modelPath == null || !Files.exists(modelPath) || Files.isDirectory(modelPath)) {
            throw new FileNotFoundException("Model file is required");
        }
        if (modelConfigPath == null
                || !Files.exists(modelConfigPath)
                || Files.isDirectory(modelConfigPath)) {
            throw new FileNotFoundException("Model config file is required");
        }
        return new PiperVoice(
                modelPath, modelConfigPath, useESpeakPhonemes ? eSpeakDataPath : null, speakerId);
    }

    /**
     * Loads the native library. Must be called before any other method.
     *
     * @throws IOException when unable to load the native library
     */
    public static synchronized void loadLibrary() throws IOException {
        if (libraryLoaded) {
            return;
        }

        String bundleLibraryPath = null;
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        if (osName.contains("win")) {
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                NativeUtils.loadLibraryResource("/win-amd64/onnxruntime_providers_shared.dll");
                NativeUtils.loadLibraryResource("/win-amd64/onnxruntime.dll");
                bundleLibraryPath = "/win-amd64/piper-jni.dll";
            }
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                NativeUtils.loadLibraryResource("/debian-amd64/libonnxruntime_providers_shared.so");
                NativeUtils.loadLibraryResource("/debian-amd64/libonnxruntime.so");
                bundleLibraryPath = "/debian-amd64/libpiper-jni.so";
            } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                NativeUtils.loadLibraryResource("/debian-arm64/libonnxruntime_providers_shared.so");
                NativeUtils.loadLibraryResource("/debian-arm64/libonnxruntime.so");
                bundleLibraryPath = "/debian-arm64/libpiper-jni.so";
            }
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                NativeUtils.loadLibraryResource("/macos-amd64/libonnxruntime.dylib");
                bundleLibraryPath = "/macos-amd64/libpiper-jni.dylib";
            } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                NativeUtils.loadLibraryResource("/macos-arm64/libonnxruntime.dylib");
                bundleLibraryPath = "/macos-arm64/libpiper-jni.dylib";
            }
        }
        if (bundleLibraryPath == null) {
            throw new FileNotFoundException(
                    "piper-jni: Unsupported platform " + osName + " - " + osArch + ".");
        }
        NativeUtils.loadLibraryResource(bundleLibraryPath);

        Path path = NativeUtils.getESpeakNGData();
        if (path == null) {
            eSpeakDataPath = null;
        } else {
            eSpeakDataPath = path.toAbsolutePath();
        }

        libraryLoaded = true;
    }

    /**
     * Ensures the native library is loaded.
     *
     * @throws IllegalStateException if the native library is not loaded
     */
    private static void assertRegistered() throws IllegalStateException {
        if (!libraryLoaded) {
            throw new IllegalStateException("Piper library not loaded");
        }
    }
}
