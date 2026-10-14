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

/**
 * Options for synthesis.
 *
 * @param speakerId id of speaker to use (multi-speaker models only), 0 is the first speaker
 * @param lengthScale how fast the text is spoken (0.5 = 2x fast, 2.0 = 2x slow, default 1.0)
 * @param noiseScale controls how much noise is added during synthesis (recommend 0.667 for
 *     single-speaker, 0.333 for multi-speaker models)
 * @param noiseWScale controls how much phonemes vary in length during synthesis (recommend 0.8 for
 *     single-speaker, 0.333 for multi-speaker models)
 * @see <a
 *     href="https://github.com/OHF-Voice/piper1-gpl/blob/v1.4.2/libpiper/include/piper.h">piper.h</a>
 * @author Florian Hotze - Initial contribution
 */
public record PiperSynthesizeOptions(
        int speakerId, float lengthScale, float noiseScale, float noiseWScale) {

    /**
     * Return a copy of this options object with a modified speaker ID.
     *
     * @param newSpeakerId new speaker ID
     * @return new {@link PiperSynthesizeOptions} instance
     */
    public PiperSynthesizeOptions withSpeakerId(int newSpeakerId) {
        return new PiperSynthesizeOptions(newSpeakerId, lengthScale, noiseScale, noiseWScale);
    }

    /**
     * Return a copy of this options object with a modified length scale.
     *
     * @param newLengthScale new length scale
     * @return new {@link PiperSynthesizeOptions} instance
     */
    public PiperSynthesizeOptions withLengthScale(float newLengthScale) {
        return new PiperSynthesizeOptions(speakerId, newLengthScale, noiseScale, noiseWScale);
    }

    /**
     * Return a copy of this options object with a modified noise scale.
     *
     * @param newNoiseScale new noise scale
     * @return new {@link PiperSynthesizeOptions} instance
     */
    public PiperSynthesizeOptions withNoiseScale(float newNoiseScale) {
        return new PiperSynthesizeOptions(speakerId, lengthScale, newNoiseScale, noiseWScale);
    }

    /**
     * Return a copy of this options object with a modified phoneme noise scale.
     *
     * @param newNoiseWScale new noise_w scale
     * @return new {@link PiperSynthesizeOptions} instance
     */
    public PiperSynthesizeOptions withNoiseWScale(float newNoiseWScale) {
        return new PiperSynthesizeOptions(speakerId, lengthScale, noiseScale, newNoiseWScale);
    }
}
