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

import io.github.jvoiceproject.piperjni.internal.AudioConverter;
import io.github.jvoiceproject.piperjni.internal.NativeUtils;
import io.github.jvoiceproject.piperjni.internal.PiperAudioChunk;
import io.github.jvoiceproject.piperjni.internal.PiperBinding;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Piper JNI
 *
 * @author Miguel Álvarez Díez - Initial contribution
 * @author Florian Hotze - Refactored to use {@link PiperBinding}
 */
public class PiperJNI implements AutoCloseable {

    private static boolean libraryLoaded;
    private Path currentESpeakDataPath;
    private boolean initialized;

    /**
     * Creates a new Piper instance.
     *
     * @throws IOException if library registration fails
     */
    public PiperJNI() throws IOException {
        loadLibrary();
    }

    /**
     * Get Piper version.
     *
     * @return piper library version.
     */
    public String getPiperVersion() {
        assertRegistered();
        return PiperBinding.piper_get_version();
    }

    /**
     * Initializes the Piper instance configuration.
     *
     * <p>Must be called before using the instance.
     *
     * @throws IOException if initialization fails.
     */
    public void initialize() throws IOException {
        initialize(true);
    }

    /**
     * Initializes the Piper instance configuration.
     *
     * <p>Must be called before using the instance.
     *
     * @param useESpeakPhonemes Support voices with ESpeak phonemes.
     * @throws IOException if initialization fails.
     */
    public void initialize(boolean useESpeakPhonemes) throws IOException {
        assertRegistered();
        if (useESpeakPhonemes) {
            Path path = NativeUtils.getESpeakNGData();
            if (path == null) {
                this.currentESpeakDataPath = null;
            } else {
                this.currentESpeakDataPath = path.toAbsolutePath();
            }
        } else {
            this.currentESpeakDataPath = null;
        }
        this.initialized = true;
    }

    /** Unload current configuration (if any). */
    public void terminate() {
        this.currentESpeakDataPath = null;
        this.initialized = false;
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @return a {@link PiperVoice} instance
     * @throws FileNotFoundException if models or config doesn't exist
     * @throws NotInitialized if piper was not initialized
     */
    public PiperVoice loadVoice(Path modelPath, Path modelConfigPath)
            throws FileNotFoundException, NotInitialized {
        return loadVoice(modelPath, modelConfigPath, -1);
    }

    /**
     * Loads Piper voice model and configuration.
     *
     * @param modelPath model file path (<code>.onnx</code>)
     * @param modelConfigPath model configuration file path (<code>.onnx.json</code>)
     * @param speakerId speaker id for multi-speaker voices or <code>-1</code>
     * @return a {@link PiperVoice} instance
     * @throws FileNotFoundException if model or configuration doesn't exist
     * @throws NotInitialized if Piper was not initialized
     */
    public PiperVoice loadVoice(Path modelPath, Path modelConfigPath, long speakerId)
            throws FileNotFoundException, NotInitialized {
        assertRegistered();
        assertInitialized();
        if (modelPath == null || !Files.exists(modelPath) || Files.isDirectory(modelPath)) {
            throw new FileNotFoundException("Model file is required");
        }
        if (modelConfigPath == null
                || !Files.exists(modelConfigPath)
                || Files.isDirectory(modelConfigPath)) {
            throw new FileNotFoundException("Model config file is required");
        }
        return new PiperVoice(modelPath, modelConfigPath, currentESpeakDataPath, (int) speakerId);
    }

    /**
     * Convert text to audio using the provided voice synchronously.
     *
     * <p>The returned audio data is formatted as raw 16-bit signed PCM mono audio samples. The
     * sample rate depends on the voice model configuration and can be retrieved via {@link
     * PiperVoice#getSampleRate()}.
     *
     * @param voice {@link PiperVoice} instance to use
     * @param text text to speak
     * @return an array of 16-bit signed PCM mono audio samples ({@code short[]})
     * @throws IOException if generation fails
     * @throws NotInitialized if Piper is not initialized
     */
    public short[] textToAudio(PiperVoice voice, String text) throws IOException, NotInitialized {
        return textToAudioImpl(voice, text, null);
    }

    /**
     * Convert text to audio using the provided voice and emit audio segments asynchronously.
     *
     * <p>Audio segments are delivered to the {@link AudioCallback} as raw 16-bit signed PCM mono
     * audio samples. The sample rate depends on the voice model configuration and can be retrieved
     * via {@link PiperVoice#getSampleRate()}.
     *
     * @param voice {@link PiperVoice} instance to use
     * @param text text to speak
     * @param audioCallback Callback for each generated audio segment
     * @throws IOException if generation fails
     * @throws NotInitialized if Piper is not initialized
     */
    public void textToAudio(PiperVoice voice, String text, AudioCallback audioCallback)
            throws IOException, NotInitialized {
        textToAudioImpl(voice, text, audioCallback);
    }

    private short[] textToAudioImpl(PiperVoice voice, String text, AudioCallback audioCallback)
            throws IOException, NotInitialized {
        assertRegistered();
        assertInitialized();
        if (voice == null) {
            throw new NullPointerException("Voice can not be null");
        }
        if (text == null) {
            throw new NullPointerException("Text can not be null");
        }
        if (text.isBlank()) {
            return new short[] {};
        }

        voice.assertOpen();

        synchronized (voice) {
            long synth = voice.getNativePointer();

            int startRes =
                    PiperBinding.piper_synthesize_start(synth, text, voice.getDefaultOptions());
            if (startRes != PiperBinding.PIPER_OK) {
                throw new IOException("Failed to start synthesis (error " + startRes + ")");
            }

            PiperAudioChunk chunk;

            if (audioCallback != null) {
                while ((chunk = PiperBinding.piper_synthesize_next(synth)) != null) {
                    if (chunk.numSamples() > 0 && chunk.samples() != null) {
                        if (chunk.numSamples() != chunk.samples().length) {
                            throw new IOException(
                                    "Synthesis returned invalid number of samples: "
                                            + chunk.numSamples());
                        }
                        short[] pcmChunk = AudioConverter.floatToPcm16(chunk.samples());
                        audioCallback.onAudio(pcmChunk);
                    }
                }
                return null;
            } else {
                List<short[]> chunks = new ArrayList<>();
                int totalSamples = 0;
                while ((chunk = PiperBinding.piper_synthesize_next(synth)) != null) {
                    if (chunk.numSamples() > 0 && chunk.samples() != null) {
                        if (chunk.numSamples() != chunk.samples().length) {
                            throw new IOException(
                                    "Synthesis returned invalid number of samples: "
                                            + chunk.numSamples());
                        }
                        short[] pcmChunk = AudioConverter.floatToPcm16(chunk.samples());
                        chunks.add(pcmChunk);
                        totalSamples += pcmChunk.length;
                    }
                }

                short[] fullAudio = new short[totalSamples];
                int offset = 0;
                for (short[] c : chunks) {
                    System.arraycopy(c, 0, fullAudio, offset, c.length);
                    offset += c.length;
                }
                return fullAudio;
            }
        }
    }

    /**
     * Checks if Piper is initialized.
     *
     * @return <code>true</code> if Piper was initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Loads the native library, should be called at first.
     *
     * @throws IOException when unable to load the native library
     */
    private void loadLibrary() throws IOException {
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
        libraryLoaded = true;
    }

    private static void assertRegistered() {
        if (!libraryLoaded) {
            throw new RuntimeException("Native library is unavailable.");
        }
    }

    private void assertInitialized() throws NotInitialized {
        if (!isInitialized()) {
            throw new NotInitialized();
        }
    }

    @Override
    public void close() {
        terminate();
    }

    /** Callback for streamed audio segments. */
    public interface AudioCallback {
        /**
         * Called once on each generated voice segment.
         *
         * @param audioSamples an array of raw 16-bit signed PCM mono audio samples
         */
        void onAudio(short[] audioSamples);
    }

    /** Emitted if Piper instance was not initialized by calling the {@link #initialize} method. */
    public static class NotInitialized extends Exception {
        private NotInitialized() {
            super("Piper not initialized");
        }
    }
}
