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

import io.github.jvoiceproject.piperjni.PiperJNI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying runtime dynamic resolution of ONNX Runtime from the Maven dependency
 * ({@code com.microsoft.onnxruntime:onnxruntime}) without linking or bundling ONNX Runtime in
 * piper-jni's native library.
 *
 * @author Florian Hotze - Initial contribution
 */
public class OnnxRuntimeIntegrationTest {

    @BeforeAll
    public static void beforeAll() throws IOException {
        PiperJNI.loadLibrary();
    }

    @Test
    public void testOnnxRuntimeVersionMatchesMavenArtifact() {
        String boundVersion = NativeLibraryLoader.getLoadedOnnxRuntimeVersion();
        assertNotNull(boundVersion, "Loaded ONNX Runtime version should not be null");
        assertFalse(boundVersion.isBlank(), "Loaded ONNX Runtime version should not be blank");

        String javaOrtVersion = OrtEnvironment.getEnvironment().getVersion();
        assertEquals(
                javaOrtVersion,
                boundVersion,
                "piper-jni should bind to the exact ONNX Runtime version loaded by"
                        + " com.microsoft.onnxruntime:onnxruntime");
    }

    @Test
    public void testPiperInitOnnxRuntimeIsIdempotent() {
        String first = NativeLibraryLoader.getLoadedOnnxRuntimeVersion();
        String second = PiperBinding.piper_init_onnxruntime();
        assertEquals(first, second, "Repeated auto-discovery init should return the same version");
    }

    @Test
    public void testCoexistenceWithJavaOrtEnvironmentAndSynthesis() throws Exception {
        // Use Java ONNX Runtime API directly in the same JVM alongside PiperJNI
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        assertNotNull(env);
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                OnnxTensor tensor = OnnxTensor.createTensor(env, new float[] {1.0f, 2.0f, 3.0f})) {
            assertNotNull(opts);
            assertEquals(3L, tensor.getInfo().getShape()[0]);

            // Synthesize audio with PiperJNI while Java OrtEnvironment & OnnxTensor are active
            String voiceModel = System.getenv("VOICE_MODEL");
            String voiceModelConfig = System.getenv("VOICE_MODEL_CONFIG");
            String textToSpeak = System.getenv("TEXT_TO_SPEAK");
            assertNotNull(voiceModel, "VOICE_MODEL env var is required");
            assertNotNull(voiceModelConfig, "VOICE_MODEL_CONFIG env var is required");
            assertNotNull(textToSpeak, "TEXT_TO_SPEAK env var is required");

            try (var voice = PiperJNI.loadVoice(Path.of(voiceModel), Path.of(voiceModelConfig))) {
                short[] samples = voice.textToAudio(textToSpeak);
                assertNotNull(samples);
                assertNotEquals(0, samples.length);
            }
        }
    }

    @Test
    public void testNativeLibraryHasNoLinkTimeOnnxRuntimeDependency() throws Exception {
        Path piperLib = NativeLibraryLoader.locateLibrary();
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (osName.contains("nux") || osName.contains("nix")) {
            command = List.of("ldd", piperLib.toAbsolutePath().toString());
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            command = List.of("otool", "-L", piperLib.toAbsolutePath().toString());
        } else {
            // Skip OS linker inspection on platforms without ldd/otool
            return;
        }

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "Command " + command + " failed:\n" + output);
        assertFalse(
                output.toLowerCase(Locale.ROOT).contains("onnxruntime"),
                "piper-jni native library must not have a link-time dependency on"
                        + " onnxruntime:\n"
                        + output);
    }
}
