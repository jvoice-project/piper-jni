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

import io.github.jvoiceproject.piperjni.internal.PiperBinding;

import java.io.FileNotFoundException;
import java.nio.file.Path;

/**
 * The class {@link PiperVoice} represents a loaded voice model.
 *
 * @author Miguel Álvarez Díez - Initial contribution
 * @author Florian Hotze - Refactored to use {@link PiperBinding}
 */
public class PiperVoice implements AutoCloseable {

    private long nativeHandle;
    private boolean closed = false;
    private final PiperSynthesizeOptions defaultOptions;

    /**
     * Creates a new voice instance.
     *
     * @param modelPath the voice model path
     * @param modelConfigPath the voice model config path
     * @param espeakDataPath the eSpeak NG data directory path or null if eSpeak phonemes shouldn't be used
     * @param speakerId the speaker id
     * @throws java.io.FileNotFoundException if loading the voice model fails
     */
    protected PiperVoice(Path modelPath, Path modelConfigPath, Path espeakDataPath, int speakerId)
            throws FileNotFoundException {
        Objects.requireNonNull(modelPath, "Model path can not be null");
        Objects.requireNonNull(modelConfigPath, "Model config path can not be null");
        String effectiveESpeakDataPath = espeakDataPath != null ? espeakDataPath.toAbsolutePath().toString() : null;

        try {
            this.nativeHandle =
                    PiperBinding.piper_create(
                            modelPath.toAbsolutePath().toString(),
                            modelConfigPath.toAbsolutePath().toString(),
        } catch (Exception e) {
                            effectiveESpeakDataPath);
            throw new FileNotFoundException("Failed to load voice model from " + modelPath);
        }
        if (this.nativeHandle == 0) {
            throw new FileNotFoundException("Failed to load voice model from " + modelPath);
        }
        PiperSynthesizeOptions initialOptions =
                PiperBinding.piper_default_synthesize_options(this.nativeHandle);
        if (speakerId > -1 && initialOptions != null) {
            this.defaultOptions = initialOptions.withSpeakerId(speakerId);
        } else {
            this.defaultOptions = initialOptions;
        }
    }

    /**
     * Get the native synthesizer pointer address.
     *
     * @return the native pointer address
     */
    long getNativePointer() {
        assertOpen();
        return nativeHandle;
    }

    /**
     * Get default synthesis options for this voice.
     *
     * @return the default synthesis options
     */
    public PiperSynthesizeOptions getDefaultOptions() {
        assertOpen();
        return defaultOptions;
    }

    /**
     * Whether the voice uses eSpeak phonemes.
     *
     * @return true if the voice uses eSpeak phonemes, false otherwise
     */
    public boolean getUsesESpeakPhonemes() {
        assertOpen();
        return PiperBinding.piper_uses_espeak_phonemes(nativeHandle);
    }

    /**
     * Get the generated audio sample rate in Hz (e.g. 16 kHz, 22.05 kHz, 24 kHz).
     *
     * @return the audio sample rate in Hz for this voice model
     */
    public int getSampleRate() {
        assertOpen();
        return PiperBinding.piper_get_sample_rate(nativeHandle);
    }

    @Override
    public void close() {
        if (!closed) {
            if (nativeHandle != 0) {
                PiperBinding.piper_free(nativeHandle);
                nativeHandle = 0;
            }
            closed = true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    void assertOpen() {
        if (closed) {
            throw new IllegalStateException("PiperVoice has already been closed.");
        }
    }
}
