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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The class {@link PiperVoice} represents a loaded voice model.
 *
 * <p>This class is fully thread-safe. All methods are safe to be called concurrently. Note that
 * only one text-to-speech synthesis can be performed at a time, so if you need to synthesize
 * multiple texts concurrently, you need multiple instances of {@link PiperVoice}.
 *
 * @author Miguel Álvarez Díez - Initial contribution
 * @author Florian Hotze - Refactored to use {@link PiperBinding} Made thread-safe
 */
public class PiperVoice implements AutoCloseable {

    private final long nativeHandle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock synthesisLock = new ReentrantLock();
    private final PiperSynthesizeOptions options;

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
            throw new FileNotFoundException("Failed to load voice model from " + modelPath);
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
    }

    /**
     * Get default synthesis options for this voice.
     *
     * @return the default synthesis options
     * @throws IllegalStateException if the voice is already closed
     */
    public PiperSynthesizeOptions getOptions() throws IllegalStateException {
        rwLock.readLock().lock();
        try {
            assertOpen();
            return options;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Whether the voice uses eSpeak phonemes.
     *
     * @return true if the voice uses eSpeak phonemes, false otherwise
     * @throws IllegalStateException if the voice is already closed
     */
    public boolean getUsesESpeakPhonemes() throws IllegalStateException {
        rwLock.readLock().lock();
        try {
            assertOpen();
            return PiperBinding.piper_uses_espeak_phonemes(nativeHandle);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Get the generated audio sample rate in Hz (e.g. 16 kHz, 22.05 kHz, 24 kHz).
     *
     * @return the audio sample rate in Hz for this voice model
     * @throws IllegalStateException if the voice is already closed
     */
    public int getSampleRate() throws IllegalStateException {
        rwLock.readLock().lock();
        try {
            assertOpen();
            return PiperBinding.piper_get_sample_rate(nativeHandle);
        } finally {
            rwLock.readLock().unlock();
        }
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
     * @throws IOException if audio synthesis fails
     */
    public short[] textToAudio(String text, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        return textToAudioImpl(text, null, options);
    }

    /**
     * Convert text to audio using the provided voice and emit audio segments asynchronously.
     *
     * <p>Audio segments are delivered to the {@link AudioCallback} as raw 16-bit signed PCM mono
     * audio samples. The sample rate depends on the voice model configuration and can be retrieved
     * via {@link PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @param audioCallback Callback for each generated audio segment
     * @throws IllegalStateException if the voice is already closed
     * @throws IOException if audio synthesis fails
     */
    public void textToAudio(String text, AudioCallback audioCallback)
            throws IllegalStateException, IOException {
        textToAudioImpl(text, audioCallback, null);
    }

    /**
     * Convert text to audio using the provided voice and emit audio segments asynchronously.
     *
     * <p>Audio segments are delivered to the {@link AudioCallback} as raw 16-bit signed PCM mono
     * audio samples. The sample rate depends on the voice model configuration and can be retrieved
     * via {@link PiperVoice#getSampleRate()}.
     *
     * @param text text to speak
     * @param audioCallback Callback for each generated audio segment
     * @param options custom synthesis options or null for defaults
     * @throws IllegalStateException if the voice is already closed
     * @throws IOException if audio synthesis fails
     */
    public void textToAudio(
            String text, AudioCallback audioCallback, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        textToAudioImpl(text, audioCallback, options);
    }

    /**
     * Implements the audio synthesis loop for both synchronous and asynchronous modes, using {@link
     * PiperBinding#piper_synthesize_start} and {@link PiperBinding#piper_synthesize_next}.
     *
     * <p>A {@link PiperVoice} instance can only synthesize audio for one text at a time. Audio
     * synthesis is synchronized on an instance-level lock.
     *
     * @param text text to speak
     * @param audioCallback callback for audio segments (for asynchronous mode), or null for
     *     synchronous mode
     * @param options custom synthesis options or null for defaults
     * @return an array of 16-bit signed PCM mono audio samples ({@code short[]}) in synchronous
     *     mode, or null in asynchronous mode
     * @throws IllegalStateException if the voice is already closed
     * @throws IOException if audio synthesis fails
     */
    private short[] textToAudioImpl(
            String text, AudioCallback audioCallback, PiperSynthesizeOptions options)
            throws IllegalStateException, IOException {
        Objects.requireNonNull(text, "Text can not be null");
        if (text.isBlank()) {
            return new short[] {};
        }

        PiperSynthesizeOptions effectiveOptions = options != null ? options : this.options;

        rwLock.readLock().lock();
        synthesisLock.lock();
        try {
            assertOpen();

            int startRes =
                    PiperBinding.piper_synthesize_start(nativeHandle, text, effectiveOptions);
            if (startRes != PiperBinding.PIPER_OK) {
                throw new IOException("Failed to start synthesis (error " + startRes + ")");
            }

            PiperAudioChunk chunk;

            if (audioCallback != null) {
                while ((chunk = PiperBinding.piper_synthesize_next(nativeHandle)) != null) {
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
                while ((chunk = PiperBinding.piper_synthesize_next(nativeHandle)) != null) {
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
            synthesisLock.unlock();
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            rwLock.writeLock().lock();
            try {
                if (nativeHandle != 0) {
                    PiperBinding.piper_free(nativeHandle);
                }
            } finally {
                rwLock.writeLock().unlock();
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
