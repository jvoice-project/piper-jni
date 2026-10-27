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
 * Chunk of synthesized audio samples.
 *
 * @param samples raw float audio samples array
 * @param numSamples number of valid samples in the {@link #samples} array
 * @param sampleRate sample rate in Hertz
 * @param isLast true if this is the last audio chunk in the synthesis stream
 * @see <a
 *     href="https://github.com/OHF-Voice/piper1-gpl/blob/v1.8.0/libpiper/include/piper.h">piper.h</a>
 * @author Florian Hotze - Initial contribution
 */
public record PiperAudioChunk(float[] samples, int numSamples, int sampleRate, boolean isLast) {}
