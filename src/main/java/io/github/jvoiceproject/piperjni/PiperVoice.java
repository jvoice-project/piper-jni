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

import java.nio.file.Path;

/**
 * The class {@link PiperVoice} represents a loaded voice model.
 *
 * @author Miguel Álvarez Díez - Initial contribution
 */
public class PiperVoice extends PiperJNI.JNIRef {

    private final PiperJNI piper;

    /**
     * Creates a new voice instance.
     *
     * @param piper the PiperJNI instance
     * @param espeakDataPath the eSpeak NG data directory path
     * @param modelPath the voice model path
     * @param modelConfigPath the voice model config path
     * @param speakerId the speaker id
     */
    protected PiperVoice(
            PiperJNI piper,
            String espeakDataPath,
            Path modelPath,
            Path modelConfigPath,
            long speakerId) {
        super(
                piper.loadVoice(
                        espeakDataPath,
                        modelPath.toAbsolutePath().toString(),
                        modelConfigPath.toAbsolutePath().toString(),
                        speakerId));
        this.piper = piper;
    }

    /**
     * Whether the voice uses eSpeak phonemes.
     *
     * @return true if the voice uses eSpeak phonemes, false otherwise
     */
    public boolean getUsesESpeakPhonemes() {
        assertAvailable();
        return piper.voiceUsesESpeakPhonemes(this.ref);
    }

    /**
     * Get the generated audio sample rate in Hz (e.g. 16 kHz, 22.05 kHz, 24 kHz).
     *
     * @return the audio sample rate in Hz for this voice model
     */
    public int getSampleRate() {
        assertAvailable();
        return piper.voiceSampleRate(this.ref);
    }

    @Override
    public void close() {
        if (!isReleased()) {
            piper.freeVoice(this.ref);
            release();
        }
    }
}
