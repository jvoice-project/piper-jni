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
import io.github.jvoiceproject.piperjni.internal.PiperAudioChunk;
import io.github.jvoiceproject.piperjni.internal.PiperBinding;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The class {@link PiperVoice} represents a loaded voice model.
 *
 * <p>This class is fully thread-safe. All methods are safe to be called concurrently. Note that
 * only one text-to-speech synthesis can be performed at a time per voice instance.
 *
 * @author Miguel Álvarez Díez - Initial contribution
 * @author Florian Hotze - Refactored to use {@link PiperBinding}; Made thread-safe
 */
public class PiperVoice implements AutoCloseable {

    /**
     * eSpeak NG, which is used internally by Piper, depends heavily on mutable process-wide global
     * variables, lookup tables, and internal buffers. Phoneme synthesis needs to be synchronized
     * globally across voices to protect these resources.
     */
    private static final ReentrantLock globalESpeakLock = new ReentrantLock();

    private final long nativeHandle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean freed = new AtomicBoolean(false);

    /**
     * Synthesis lock to ensure only one synthesis operation is performed at a time on this voice
     * instance.
     */
    private final ReentrantLock instanceSynthesisLock = new ReentrantLock();

    private final PiperSynthesizeOptions options;
    private final boolean usesESpeakPhonemes;
    private final int sampleRate;

    /**
     * Creates a new voice instance.
     *
     * @param modelPath the voice model path
     * @param modelConfigPath the voice model config path
     * @param espeakDataPath the eSpeak NG data directory path or null if eSpeak phonemes shouldn't
     *     be used
     * @param speakerId the speaker id
     * @throws FileNotFoundException if loading the voice model fails
     * @throws IllegalArgumentException if the voice model config file is invalid
     */
    protected PiperVoice(Path modelPath, Path modelConfigPath, Path espeakDataPath, int speakerId)
            throws FileNotFoundException {
        Objects.requireNonNull(modelPath, "Model path can not be null");
        Objects.requireNonNull(modelConfigPath, "Model config path can not be null");
        String effectiveESpeakDataPath =
                espeakDataPath != null ? espeakDataPath.toAbsolutePath().toString() : null;

        try {
            this.nativeHandle =
                    PiperBinding.piper_create(
                            modelPath.toAbsolutePath().toString(),
                            modelConfigPath.toAbsolutePath().toString(),
                            effectiveESpeakDataPath);
        } catch (IOException e) {
            throw (FileNotFoundException)
                    new FileNotFoundException("Failed to load voice model from " + modelPath)
                            .initCause(e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to load voice model from " + modelPath, e);
        }
        if (this.nativeHandle == 0) {
            throw new FileNotFoundException("Failed to load voice model from " + modelPath);
        }

        PiperSynthesizeOptions initialOptions =
                PiperBinding.piper_default_synthesize_options(this.nativeHandle);
        if (speakerId > -1 && initialOptions != null) {
            this.options = initialOptions.withSpeakerId(speakerId);
        } else {
            this.options = initialOptions;
        }

        usesESpeakPhonemes = PiperBinding.piper_uses_espeak_phonemes(nativeHandle);

        if (usesESpeakPhonemes && effectiveESpeakDataPath == null) {
            close();
            throw new IllegalArgumentException(
                    "eSpeak phonemes are required by the model, but eSpeak data directory is not"
                            + " set");
        }

        sampleRate = PiperBinding.piper_get_sample_rate(nativeHandle);
    }

    /**
     * Get default synthesis options for this voice.
     *
     * @return the default synthesis options
     * @throws IllegalStateException if the voice is already closed
     */
    public PiperSynthesizeOptions getOptions() throws IllegalStateException {
        assertOpen();
        return options;
    }

    /**
     * Whether the voice uses eSpeak phonemes.
     *
     * @return true if the voice uses eSpeak phonemes, false otherwise
     * @throws IllegalStateException if the voice is already closed
     */
    public boolean getUsesESpeakPhonemes() throws IllegalStateException {
        assertOpen();
        return usesESpeakPhonemes;
    }

    /**
     * Get the generated audio sample rate in Hz (e.g. 16 kHz, 22.05 kHz, 24 kHz).
     *
     * @return the audio sample rate in Hz for this voice model
     * @throws IllegalStateException if the voice is already closed
     */
    public int getSampleRate() throws IllegalStateException {
        assertOpen();
        return sampleRate;
    }

    /**
     * Convert text to audio using the provided voice synchronously.
     *
     * <p>The returned audio data is formatted as raw 16-bit signed PCM mono audio samples. The
     * sample rate depends on the voice model configuration and can be retrieved via {@link
     * PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @return an array of 16-bit signed PCM mono audio samples ({@code short[]})
     * @throws IllegalStateException if the voice is already closed
     * @throws IllegalStateException if nested or re-entrant synthesis calls are attempted
     * @throws IOException if audio synthesis fails
     */
    public short[] textToAudio(String text) throws IllegalStateException, IOException {
        return textToAudioImpl(text, null, null);
    }

    /**
     * Convert text to audio using the provided voice synchronously.
     *
     * <p>The returned audio data is formatted as raw 16-bit signed PCM mono audio samples. The
     * sample rate depends on the voice model configuration and can be retrieved via {@link
     * PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @param options custom synthesis options or null for defaults
     * @return an array of 16-bit signed PCM mono audio samples ({@code short[]})
     * @throws IllegalStateException if the voice is already closed
     * @throws IllegalStateException if nested or re-entrant synthesis calls are attempted
     * @throws IOException if audio synthesis fails
     */
    public short[] textToAudio(String text, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        return textToAudioImpl(text, null, options);
    }

    /**
     * Convert text to audio using the provided voice and streams audio segments.
     *
     * <p>Audio segments are delivered to the {@link AudioCallback} as raw 16-bit signed PCM mono
     * audio samples. The sample rate depends on the voice model configuration and can be retrieved
     * via {@link PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @param audioCallback Callback for each generated audio segment
     * @throws IllegalStateException if the voice is already closed
     * @throws IllegalStateException if nested or re-entrant synthesis calls are attempted
     * @throws IOException if audio synthesis fails
     */
    public void textToAudio(String text, AudioCallback audioCallback)
            throws IllegalStateException, IOException {
        textToAudioImpl(text, audioCallback, null);
    }

    /**
     * Convert text to audio using the provided voice and streams audio segments.
     *
     * <p>Audio segments are delivered to the {@link AudioCallback} as raw 16-bit signed PCM mono
     * audio samples. The sample rate depends on the voice model configuration and can be retrieved
     * via {@link PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @param audioCallback Callback for each generated audio segment
     * @param options custom synthesis options or null for defaults
     * @throws IllegalStateException if the voice is already closed
     * @throws IllegalStateException if nested or re-entrant synthesis calls are attempted
     * @throws IOException if audio synthesis fails
     */
    public void textToAudio(
            String text, AudioCallback audioCallback, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        textToAudioImpl(text, audioCallback, options);
    }

    /**
     * Implements the audio synthesis loop for both synchronous and streaming modes, using {@link
     * PiperBinding#piper_synthesize_start} and {@link PiperBinding#piper_synthesize_next}.
     *
     * <p>A {@link PiperVoice} instance can only synthesize audio for one text at a time. Audio
     * synthesis is synchronized on an instance-level lock.
     *
     * @param text text to speak
     * @param audioCallback callback for audio segments (for streaming mode), or null for
     *     synchronous mode
     * @param options custom synthesis options or null for defaults
     * @return an array of 16-bit signed PCM mono audio samples ({@code short[]}) in synchronous
     *     mode, or null in streaming mode
     * @throws IllegalStateException if the voice is already closed
     * @throws IllegalStateException if nested or re-entrant synthesis calls are attempted
     * @throws IOException if audio synthesis fails
     */
    private short[] textToAudioImpl(
            String text, AudioCallback audioCallback, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        Objects.requireNonNull(text, "Text can not be null");

        PiperSynthesizeOptions effectiveOptions = options != null ? options : this.options;

        if (instanceSynthesisLock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "Nested or re-entrant synthesis calls are not supported.");
        }

        instanceSynthesisLock.lock();
        try {
            assertOpen();
            if (text.isBlank()) {
                return new short[] {};
            }

            int startRes;
            if (usesESpeakPhonemes) {
                globalESpeakLock.lock();
                try {
                    assertOpen();
                    startRes =
                            PiperBinding.piper_synthesize_start(
                                    nativeHandle, text, effectiveOptions);
                } finally {
                    globalESpeakLock.unlock();
                }
            } else {
                startRes =
                        PiperBinding.piper_synthesize_start(nativeHandle, text, effectiveOptions);
            }

            if (startRes != PiperBinding.PIPER_OK) {
                throw new IOException("Failed to start synthesis (error " + startRes + ")");
            }

            PiperAudioChunk chunk;

            if (audioCallback != null) {
                while (!closed.get()
                        && (chunk = PiperBinding.piper_synthesize_next(nativeHandle)) != null) {
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
                while (!closed.get()
                        && (chunk = PiperBinding.piper_synthesize_next(nativeHandle)) != null) {
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
        } finally {
            try {
                // close() was called from within AudioCallback while synthesis was running, perform
                // the deferred free
                if (closed.get()) {
                    freeNativeHandle();
                }
            } finally {
                instanceSynthesisLock.unlock();
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (instanceSynthesisLock.isHeldByCurrentThread()) {
                // close() was called from within AudioCallback on the synthesis loop, let
                // textToAudioImpl finally free it
                return;
            }

            instanceSynthesisLock.lock();
            try {
                freeNativeHandle();
            } finally {
                instanceSynthesisLock.unlock();
            }
        }
    }

    private void freeNativeHandle() {
        if (freed.compareAndSet(false, true)) {
            if (nativeHandle != 0) {
                PiperBinding.piper_free(nativeHandle);
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    void assertOpen() throws IllegalStateException {
        if (closed.get()) {
            throw new IllegalStateException("PiperVoice has already been closed.");
        }
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
}
