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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.naming.ConfigurationException;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @author Miguel Álvarez Díez - Initial contribution
 */
public class PiperJNITest {
    @BeforeAll
    public static void before() throws IOException {
        PiperJNI.loadLibrary();
    }

    @Test
    public void getPiperVersion() {
        var version = PiperJNI.getPiperVersion();
        assertNotNull(version, "version is defined");
        System.out.println("Piper version: " + version);
    }

    @Test
    public void createPiperVoice() throws ConfigurationException, FileNotFoundException {
        String voiceModel = System.getenv("VOICE_MODEL");
        String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
        if (voiceModel == null || voiceModel.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL is required");
        }
        if (voiceModelConfig == null || voiceModelConfig.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL_CONFIG is required");
        }
        try (var voice = PiperJNI.loadVoice(Paths.get(voiceModel), Path.of(voiceModelConfig))) {
            assertNotNull(voice);
            assertTrue(voice.getUsesESpeakPhonemes());
        }
    }

    @Test
    public void createAudioData() throws ConfigurationException, IOException {
        String voiceModel = System.getenv("VOICE_MODEL");
        String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
        String textToSpeak = System.getenv("TEXT_TO_SPEAK");
        String outputDir = System.getenv("OUTPUT_DIR");
        if (voiceModel == null || voiceModel.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL is required");
        }
        if (voiceModelConfig == null || voiceModelConfig.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL_CONFIG is required");
        }
        if (textToSpeak == null || textToSpeak.isBlank()) {
            throw new ConfigurationException("env var TEXT_TO_SPEAK is required");
        }
        if (outputDir == null || outputDir.isBlank()) {
            throw new ConfigurationException("env var OUTPUT_DIR is required");
        }
        try (var voice = PiperJNI.loadVoice(Paths.get(voiceModel), Path.of(voiceModelConfig), 0)) {
            assertNotNull(voice);
            int sampleRate = voice.getSampleRate();
            short[] samples = voice.textToAudio(textToSpeak);
            assertNotEquals(0, samples.length);
            Path outPath = Path.of(outputDir, "test.wav");
            createWAVFile(List.of(samples), sampleRate, outPath);
            verifyAudioFile(outPath);
        }
    }

    @Test
    public void streamAudioData() throws ConfigurationException, IOException {
        String voiceModel = System.getenv("VOICE_MODEL");
        String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
        String textToSpeak = System.getenv("TEXT_TO_SPEAK");
        String outputDir = System.getenv("OUTPUT_DIR");
        if (voiceModel == null || voiceModel.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL is required");
        }
        if (voiceModelConfig == null || voiceModelConfig.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL_CONFIG is required");
        }
        if (textToSpeak == null || textToSpeak.isBlank()) {
            throw new ConfigurationException("env var TEXT_TO_SPEAK is required");
        }
        if (outputDir == null || outputDir.isBlank()) {
            throw new ConfigurationException("env var OUTPUT_DIR is required");
        }
        try (var voice = PiperJNI.loadVoice(Paths.get(voiceModel), Path.of(voiceModelConfig))) {
            assertNotNull(voice);
            int sampleRate = voice.getSampleRate();
            final ArrayList<short[]> audioSamplesChunks = new ArrayList<>();
            voice.textToAudio(textToSpeak, audioSamplesChunks::add);
            assertFalse(audioSamplesChunks.isEmpty());
            assertNotEquals(0, audioSamplesChunks.get(0).length);
            Path outPath = Path.of(outputDir, "test-stream.wav");
            createWAVFile(audioSamplesChunks, sampleRate, outPath);
            verifyAudioFile(outPath);
        }
    }

    @Test
    public void createAudioDataWithOptions() throws ConfigurationException, IOException {
        String voiceModel = System.getenv("VOICE_MODEL");
        String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
        String textToSpeak = System.getenv("TEXT_TO_SPEAK");
        if (voiceModel == null || voiceModel.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL is required");
        }
        if (voiceModelConfig == null || voiceModelConfig.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL_CONFIG is required");
        }
        if (textToSpeak == null || textToSpeak.isBlank()) {
            throw new ConfigurationException("env var TEXT_TO_SPEAK is required");
        }
        try (var voice = PiperJNI.loadVoice(Paths.get(voiceModel), Path.of(voiceModelConfig))) {
            assertNotNull(voice);
            PiperSynthesizeOptions defaultOpts = voice.getOptions();
            assertNotNull(defaultOpts);

            // Test speaking faster (lengthScale = 0.5f)
            PiperSynthesizeOptions fastOpts = defaultOpts.withLengthScale(0.5f);
            short[] samplesFast = voice.textToAudio(textToSpeak, fastOpts);
            assertNotEquals(0, samplesFast.length);

            // Test speaking slower (lengthScale = 2.0f)
            PiperSynthesizeOptions slowOpts = defaultOpts.withLengthScale(2.0f);
            short[] samplesSlow = voice.textToAudio(textToSpeak, slowOpts);
            assertNotEquals(0, samplesSlow.length);

            // Slower speech should produce more audio samples than faster speech
            assertTrue(
                    samplesSlow.length > samplesFast.length,
                    "Slower speech should generate more samples than faster speech");
        }
    }

    @Test
    public void closedVoiceThrows() throws ConfigurationException, FileNotFoundException {
        String voiceModel = System.getenv("VOICE_MODEL");
        String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
        if (voiceModel == null || voiceModel.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL is required");
        }
        if (voiceModelConfig == null || voiceModelConfig.isBlank()) {
            throw new ConfigurationException("env var VOICE_MODEL_CONFIG is required");
        }
        var voice = PiperJNI.loadVoice(Paths.get(voiceModel), Path.of(voiceModelConfig));
        voice.close();
        assertThrows(IllegalStateException.class, voice::getSampleRate);
        assertThrows(IllegalStateException.class, voice::getUsesESpeakPhonemes);
        assertThrows(IllegalStateException.class, voice::getOptions);
        assertThrows(IllegalStateException.class, () -> voice.textToAudio("Hello"));
    }

    private void createWAVFile(List<short[]> sampleChunks, long sampleRate, Path outFilePath) {
        javax.sound.sampled.AudioFormat jAudioFormat;
        ByteBuffer byteBuffer;
        int numSamples = sampleChunks.stream().map(c -> c.length).reduce(0, Integer::sum);
        jAudioFormat =
                new javax.sound.sampled.AudioFormat(
                        javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                        sampleRate,
                        16,
                        1,
                        2,
                        sampleRate,
                        false);
        byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var chunk : sampleChunks) {
            for (var sample : chunk) {
                byteBuffer.putShort(sample);
            }
        }
        AudioInputStream audioInputStreamTemp =
                new AudioInputStream(
                        new ByteArrayInputStream(byteBuffer.array()), jAudioFormat, numSamples);
        try {
            FileOutputStream audioFileOutputStream = new FileOutputStream(outFilePath.toFile());
            AudioSystem.write(
                    audioInputStreamTemp, AudioFileFormat.Type.WAVE, audioFileOutputStream);
            audioFileOutputStream.close();
        } catch (IOException e) {
            System.err.println("Unable to store sample: " + e.getMessage());
        }
    }

    private void verifyAudioFile(Path path) throws IOException {
        assertTrue(Files.exists(path), "Audio file should exist");
        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(path.toFile())) {
            assertNotEquals(0, audioInputStream.getFrameLength(), "Audio file should have frames");
            byte[] bytes = audioInputStream.readAllBytes();
            boolean hasAudio = false;
            for (byte b : bytes) {
                if (b != 0) {
                    hasAudio = true;
                    break;
                }
            }
            assertTrue(hasAudio, "Audio file should contain non-silent audio data");
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file format", e);
        }
    }
}
