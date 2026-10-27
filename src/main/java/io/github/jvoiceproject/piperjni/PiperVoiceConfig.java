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
import java.util.Objects;

/**
 * Configuration options for loading a Piper voice model using {@link
 * PiperJNI#loadVoice(PiperVoiceConfig)}.
 *
 * <p>Exposes creation options introduced in libpiper v1.7.0+/v1.8.0, allowing custom paths for
 * voice configuration, eSpeak NG data, and G2PW pinyin models.
 *
 * @param modelPath path to ONNX voice model file (required)
 * @param configPath path to JSON voice config file or null
 * @param espeakDataPath path to the espeak-ng data directory or null
 * @param g2pwModelDir optional path to g2pw / pinyin dictionary directory or null
 * @param speakerId id of speaker to use (multi-speaker models only) or null for model default
 * @see <a
 *     href="https://github.com/OHF-Voice/piper1-gpl/blob/v1.8.0/libpiper/include/piper.h">piper.h</a>
 * @author Florian Hotze - Initial contribution
 */
public record PiperVoiceConfig(
        Path modelPath,
        Path configPath,
        Path espeakDataPath,
        Path g2pwModelDir,
        Integer speakerId) {

    /** Compact constructor validating required fields and defaulting speakerId to 0 if null. */
    public PiperVoiceConfig {
        Objects.requireNonNull(modelPath, "Model path cannot be null");
        if (speakerId == null) {
            speakerId = 0;
        }
    }

    /**
     * Create a voice configuration with the required model path and default options.
     *
     * @param modelPath path to ONNX voice model file
     */
    public PiperVoiceConfig(Path modelPath) {
        this(modelPath, null, null, null, 0);
    }

    /**
     * Return a copy of this configuration with a modified model path.
     *
     * @param newModelPath new model path
     * @return new {@link PiperVoiceConfig} instance
     */
    public PiperVoiceConfig withModelPath(Path newModelPath) {
        return new PiperVoiceConfig(
                newModelPath, configPath, espeakDataPath, g2pwModelDir, speakerId);
    }

    /**
     * Return a copy of this configuration with a modified voice config path.
     *
     * @param newConfigPath new config path or null for modelPath + <code>.json</code>
     * @return new {@link PiperVoiceConfig} instance
     */
    public PiperVoiceConfig withConfigPath(Path newConfigPath) {
        return new PiperVoiceConfig(
                modelPath, newConfigPath, espeakDataPath, g2pwModelDir, speakerId);
    }

    /**
     * Return a copy of this configuration with a modified eSpeak NG data directory path.
     *
     * @param newEspeakDataPath new eSpeak NG data path or null
     * @return new {@link PiperVoiceConfig} instance
     */
    public PiperVoiceConfig withEspeakDataPath(Path newEspeakDataPath) {
        return new PiperVoiceConfig(
                modelPath, configPath, newEspeakDataPath, g2pwModelDir, speakerId);
    }

    /**
     * Return a copy of this configuration with a modified G2PW model directory path.
     *
     * @param newG2pwModelDir new G2PW model directory path or null
     * @return new {@link PiperVoiceConfig} instance
     */
    public PiperVoiceConfig withG2pwModelDir(Path newG2pwModelDir) {
        return new PiperVoiceConfig(
                modelPath, configPath, espeakDataPath, newG2pwModelDir, speakerId);
    }

    /**
     * Return a copy of this configuration with a modified speaker ID.
     *
     * @param newSpeakerId new speaker ID or null for model default
     * @return new {@link PiperVoiceConfig} instance
     */
    public PiperVoiceConfig withSpeakerId(Integer newSpeakerId) {
        return new PiperVoiceConfig(
                modelPath,
                configPath,
                espeakDataPath,
                g2pwModelDir,
                // -1 was the previous default value, null is the current one
                // the default voice is usually indexed as 0, so set 0 then
                newSpeakerId != null && newSpeakerId < 0 ? Integer.valueOf(0) : newSpeakerId);
    }
}
