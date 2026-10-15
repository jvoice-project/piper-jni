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

/**
 * Audio conversion utilities for converting between PCM sample formats.
 *
 * @author Florian Hotze - Initial contribution
 */
public final class AudioConverter {
    private AudioConverter() {}

    /**
     * Converts float PCM audio samples in range [-1.0f, 1.0f] to 16-bit signed PCM samples.
     *
     * @param samples array of float samples
     * @return 16-bit signed PCM short array
     */
    public static short[] floatToPcm16(float[] samples) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float val = samples[i];
            val = Math.max(-1.0f, Math.min(1.0f, val));
            pcm[i] = (short) (val * 32767.0f);
        }
        return pcm;
    }
}
