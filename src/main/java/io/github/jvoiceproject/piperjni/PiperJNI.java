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

import io.github.jvoiceproject.piperjni.internal.NativeLibraryLoader;
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
public final class PiperJNI {

    private PiperJNI() {}

    /**
     * Get the Piper version.
     *
     * @return Piper library version.
     */
    public static String getPiperVersion() {
        assertRegistered();
        return PiperBinding.piper_version();
    }

    /**
     * Loads Piper voice model with configuration options.
     *
     * @param config voice configuration options
     * @return a {@link PiperVoice} instance
     * @throws IllegalStateException if the native library is not loaded
     * @throws FileNotFoundException if the model or configuration file doesn't exist
     */
    public static synchronized PiperVoice loadVoice(PiperVoiceConfig config)
            throws IllegalStateException, FileNotFoundException {
        assertRegistered();
        java.util.Objects.requireNonNull(config, "PiperVoiceConfig is required");
        if (config.modelPath() == null
                || !Files.exists(config.modelPath())
                || Files.isDirectory(config.modelPath())) {
            throw new FileNotFoundException("Model file is required");
        }
        if (config.configPath() == null
                || !Files.exists(config.configPath())
                || Files.isDirectory(config.configPath())) {
            throw new FileNotFoundException("Model config file is required");
        }

        PiperVoiceConfig effectiveConfig = config;
        Path espeakDataPath = NativeLibraryLoader.getLoadedESpeakNGData();
        if (config.espeakDataPath() == null && espeakDataPath != null) {
            effectiveConfig = config.withEspeakDataPath(espeakDataPath);
        }
        return new PiperVoice(effectiveConfig);
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
        return loadVoice(modelPath, modelConfigPath, null);
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @param speakerId speaker id for multi-speaker voices, or <code>null</code> for model default
     * @return a {@link PiperVoice} instance
     * @throws IllegalStateException if the native library is not loaded
     * @throws FileNotFoundException if the model or the configuration file doesn't exist
     */
    public static PiperVoice loadVoice(Path modelPath, Path modelConfigPath, Integer speakerId)
            throws IllegalStateException, FileNotFoundException {
        return loadVoice(
                new PiperVoiceConfig(modelPath)
                        .withConfigPath(modelConfigPath)
                        .withSpeakerId(speakerId));
    }

    /**
     * Loads the native library. Must be called before any other method.
     *
     * @throws IOException when unable to extract the native library or resources
     * @throws UnsupportedOperationException if the operating system or architecture is not
     *     supported
     * @throws UnsatisfiedLinkError if the native library or its dependencies cannot be linked
     */
    public static synchronized void loadLibrary() throws IOException {
        NativeLibraryLoader.load();
    }

    /**
     * Ensures the native library is loaded.
     *
     * @throws IllegalStateException if the native library is not loaded
     */
    private static void assertRegistered() throws IllegalStateException {
        if (!NativeLibraryLoader.isLoaded()) {
            throw new IllegalStateException("Piper library not loaded");
        }
    }
}
