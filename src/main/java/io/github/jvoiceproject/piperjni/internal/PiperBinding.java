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

import io.github.jvoiceproject.piperjni.PiperSynthesizeOptions;

import java.io.IOException;

/**
 * JNI binding for Piper.
 *
 * @see <a
 *     href="https://github.com/OHF-Voice/piper1-gpl/blob/v1.4.2/libpiper/include/piper.h">piper.h</a>
 * @author Florian Hotze - Initial contribution
 */
public final class PiperBinding {
    private PiperBinding() {}

    // Mirrored from piper.h:

    /** Success status code. */
    public static final int PIPER_OK = 0;

    /** Synthesis complete status code. */
    public static final int PIPER_DONE = 1;

    /** Generic error status code. */
    public static final int PIPER_ERR_GENERIC = -1;

    /**
     * Create a Piper text-to-speech synthesizer from a voice model.
     *
     * @param modelPath path to ONNX voice model file
     * @param configPath path to JSON voice config file or null
     * @param espeakDataPath path to the espeak-ng data directory
     * @return pointer address to {@code piper_synthesizer*} instance, or 0 on failure
     * @throws IOException if the model or the configuration file doesn't exist
     */
    public static native long piper_create(
            String modelPath, String configPath, String espeakDataPath)
            throws IOException, IllegalArgumentException;

    /**
     * Free resources for Piper synthesizer.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     */
    public static native void piper_free(long handle);

    /**
     * Get the default synthesis options for a Piper synthesizer.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     * @return synthesis options from voice config
     */
    public static native PiperSynthesizeOptions piper_default_synthesize_options(long handle);

    /**
     * Start text-to-speech synthesis.
     *
     * <p>This function clears state, phonemizes text, and maps phonemes to ids using eSpeak NG. As
     * eSpeak NG depends heavily on mutable process-wide global variables, lookup tables, and
     * internal buffers, invocations need to be synchronized globally.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     * @param text text to synthesize into audio
     * @param options synthesis options or null for defaults
     * @return {@link #PIPER_OK} or error code
     */
    public static native int piper_synthesize_start(
            long handle, String text, PiperSynthesizeOptions options);

    /**
     * Synthesize the next chunk of audio.
     *
     * <p>This function does not call eSpeak NG, so it is safe to synchronize on a
     * synthesizer-instance level.
     *
     * <p>{@link #piper_synthesize_start(long, String, PiperSynthesizeOptions)} must be called
     * before this function. Each call to this function will return a new audio chunk, invalidating
     * the memory of the previous chunk. The final audio chunk will have {@code is_last = true}.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     * @return a new {@link PiperAudioChunk}, or {@code null} when synthesis is complete
     * @throws IOException if audio synthesis fails
     */
    public static native PiperAudioChunk piper_synthesize_next(long handle) throws IOException;

    // Additional methods not mirrored from piper.h

    /**
     * Get the voice sample rate in Hertz directly from the synthesizer configuration.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     * @return sample rate in Hz
     */
    public static native int piper_get_sample_rate(long handle);

    /**
     * Whether the synthesizer uses eSpeak phonemes.
     *
     * @param handle pointer to {@code piper_synthesizer*} instance
     * @return {@code true} if eSpeak phonemes are used, {@code false} otherwise
     */
    public static native boolean piper_uses_espeak_phonemes(long handle);

    /**
     * Get the Piper library version string.
     *
     * @return piper library version
     */
    public static native String piper_get_version();
}
