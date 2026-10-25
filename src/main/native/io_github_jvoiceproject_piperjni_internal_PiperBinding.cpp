#include "io_github_jvoiceproject_piperjni_internal_PiperBinding.h"
#include "piper.h"
#include "piper_impl.hpp"
#include <json.hpp>
#include <onnxruntime_cxx_api.h>
#include <new>
#include <filesystem>

/// Java exception helper
static void throwJava(JNIEnv *env, const char *className, const char *message) {
    if (env->ExceptionCheck()) {
        return; // An exception is already pending; don't overwrite it
    }
    jclass cls = env->FindClass(className);
    if (cls) {
        env->ThrowNew(cls, message ? message : "");
        env->DeleteLocalRef(cls);
    }
    // if cls is null, NoClassDefFoundError was already thrown
}

/// Swallows C++ exception and throws "matching" Java exception
static void swallow_cpp_exception_and_throw_java(JNIEnv *env) {
    try {
        throw;
    // OOM C++ exception
    } catch (const std::bad_alloc& e) {
        throwJava(env, "java/lang/OutOfMemoryError", e.what());

    // Missing or unreadable files (std::filesystem or nlohmann/json parsing empty/missing config)
    } catch (const std::filesystem::filesystem_error& e) {
        throwJava(env, "java/io/IOException", e.what());
    } catch (const nlohmann::json::parse_error& e) {
        throwJava(env, "java/io/IOException", e.what());

    // Corrupt/invalid voice config entries
    } catch (const nlohmann::json::exception& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());

    // Missing ONNX model file or general runtime/inference errors
    } catch (const Ort::Exception& e) {
        std::string_view msg = e.what();
        if (msg.find("NO_SUCHFILE") != std::string_view::npos ||
            msg.find("No such file") != std::string_view::npos) {
            throwJava(env, "java/io/IOException", e.what());
        } else {
            throwJava(env, "java/lang/RuntimeException", e.what());
        }

    // Fallback for all other standard C++ exceptions
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/RuntimeException", e.what());

    // Catch-all for non-std native faults
    } catch (...) {
        throwJava(env, "java/lang/Error", "Unknown native exception");
    }
}

// RAII Wrapper for JNI String
class JNIString {
    JNIEnv* env;
    jstring jstr;
    const char* cstr;

public:
    JNIString(JNIEnv* env, jstring jstr) : env(env), jstr(jstr), cstr(nullptr) {
        if (env && jstr) {
            cstr = env->GetStringUTFChars(jstr, nullptr);
            if (!cstr) {
                throw std::bad_alloc();
            }
        }
    }
    ~JNIString() {
        if (env && jstr && cstr) {
            env->ReleaseStringUTFChars(jstr, cstr);
        }
    }
    JNIString(const JNIString&) = delete; // disable copy constructor
    JNIString& operator=(const JNIString&) = delete; // disable copy assignment operator

    const char* get() const { return cstr; }
    operator const char*() const { return cstr; }
};

// Cached class and constructor IDs for PiperAudioChunk
static jclass cls_chunk = nullptr;
static jmethodID mid_chunk_ctor = nullptr;

// Cached class and method IDs for PiperSynthesizeOptions
static jclass cls_options = nullptr;
static jmethodID mid_options_ctor = nullptr;
static jmethodID mid_opt_speakerId = nullptr;
static jmethodID mid_opt_lengthScale = nullptr;
static jmethodID mid_opt_noiseScale = nullptr;
static jmethodID mid_opt_noiseWScale = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK) {
        return JNI_ERR;
    }

    // Cache PiperAudioChunk class and constructor IDs
    jclass local_cls_chunk = env->FindClass("io/github/jvoiceproject/piperjni/internal/PiperAudioChunk");
    if (!local_cls_chunk) return JNI_ERR;
    cls_chunk = reinterpret_cast<jclass>(env->NewGlobalRef(local_cls_chunk));
    mid_chunk_ctor = env->GetMethodID(cls_chunk, "<init>", "([FIIZ)V");
    if (!mid_chunk_ctor) return JNI_ERR;

    // Cache PiperSynthesizeOptions class and methods IDs
    jclass local_cls_opt = env->FindClass("io/github/jvoiceproject/piperjni/PiperSynthesizeOptions");
    if (!local_cls_opt) return JNI_ERR;
    cls_options = reinterpret_cast<jclass>(env->NewGlobalRef(local_cls_opt));
    mid_options_ctor = env->GetMethodID(cls_options, "<init>", "(IFFF)V");
    mid_opt_speakerId = env->GetMethodID(cls_options, "speakerId", "()I");
    mid_opt_lengthScale = env->GetMethodID(cls_options, "lengthScale", "()F");
    mid_opt_noiseScale = env->GetMethodID(cls_options, "noiseScale", "()F");
    mid_opt_noiseWScale = env->GetMethodID(cls_options, "noiseWScale", "()F");

    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) == JNI_OK) {
        if (cls_chunk) {
            env->DeleteGlobalRef(cls_chunk);
            cls_chunk = nullptr;
        }
        if (cls_options) {
            env->DeleteGlobalRef(cls_options);
            cls_options = nullptr;
        }
    }
}

JNIEXPORT jlong JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1create
  (JNIEnv *env, jclass, jstring modelPath, jstring configPath, jstring espeakDataPath) {
    try {
        JNIString cModelPath(env, modelPath);
        JNIString cModelConfigPath(env, configPath);
        JNIString cEspeakDataPath(env, espeakDataPath);

        piper_synthesizer *synth = piper_create(cModelPath, cModelConfigPath, cEspeakDataPath);
        return reinterpret_cast<jlong>(synth);
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return 0;
    }
}

JNIEXPORT void JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1free
  (JNIEnv *env, jclass, jlong synth) {
    if (synth != 0) {
        try {
            piper_free(reinterpret_cast<piper_synthesizer *>(synth));
        } catch (...) {
            swallow_cpp_exception_and_throw_java(env);
        }
    }
}

JNIEXPORT jobject JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1default_1synthesize_1options
  (JNIEnv *env, jclass, jlong synth) {
    if (synth == 0) return nullptr;
    try {
        piper_synthesizer *s = reinterpret_cast<piper_synthesizer *>(synth);
        piper_synthesize_options options = piper_default_synthesize_options(s);

        return env->NewObject(cls_options, mid_options_ctor,
                              (jint)options.speaker_id,
                              (jfloat)options.length_scale,
                              (jfloat)options.noise_scale,
                              (jfloat)options.noise_w_scale);
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1synthesize_1start
  (JNIEnv *env, jclass, jlong synth, jstring text, jobject optionsObj) {
    if (synth == 0 || text == nullptr) {
        return io_github_jvoiceproject_piperjni_internal_PiperBinding_PIPER_ERR_GENERIC;
    }

    try {
        JNIString cText(env, text);
        piper_synthesize_options options;
        piper_synthesize_options *optionsPtr = nullptr;

        if (optionsObj != nullptr) {
            options.speaker_id = env->CallIntMethod(optionsObj, mid_opt_speakerId);
            options.length_scale = env->CallFloatMethod(optionsObj, mid_opt_lengthScale);
            options.noise_scale = env->CallFloatMethod(optionsObj, mid_opt_noiseScale);
            options.noise_w_scale = env->CallFloatMethod(optionsObj, mid_opt_noiseWScale);
            optionsPtr = &options;
            // Call***Method do not throw C++ exceptions. Return on pending exception.
            if (env->ExceptionCheck()) return io_github_jvoiceproject_piperjni_internal_PiperBinding_PIPER_ERR_GENERIC;
        }

        return piper_synthesize_start(reinterpret_cast<piper_synthesizer *>(synth), cText, optionsPtr);
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return io_github_jvoiceproject_piperjni_internal_PiperBinding_PIPER_ERR_GENERIC;
    }
}

JNIEXPORT jobject JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1synthesize_1next
  (JNIEnv *env, jclass, jlong synth) {
    if (synth == 0) return nullptr;

    try {
        piper_synthesizer *s = reinterpret_cast<piper_synthesizer *>(synth);
        piper_audio_chunk chunk;
        int res = piper_synthesize_next(s, &chunk);

        if (res == PIPER_DONE) {
            return nullptr;
        }
        if (res != PIPER_OK) {
            throwJava(env, "java/io/IOException", "Failed to synthesize next chunk");
            return nullptr;
        }

        jsize count = static_cast<jsize>(chunk.num_samples);
        jfloatArray samples = env->NewFloatArray(count);
        if (!samples) {
            // NewFloatArray already posted an OutOfMemoryError in Java
            return nullptr;
        }

        // Copy native float data into JVM array
        if (count > 0 && chunk.samples != nullptr) {
            env->SetFloatArrayRegion(samples, 0, count, chunk.samples);
        }

        jobject chunk_obj = env->NewObject(cls_chunk, mid_chunk_ctor, samples,
                                    static_cast<jint>(chunk.num_samples),
                                    static_cast<jint>(chunk.sample_rate),
                                    static_cast<jboolean>(chunk.is_last));

        // Delete local reference so we don't prevent GC
        env->DeleteLocalRef(samples);

        return chunk_obj;
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1get_1sample_1rate
  (JNIEnv *env, jclass, jlong synth) {
    if (synth == 0) return 0;
    try {
        return reinterpret_cast<piper_synthesizer *>(synth)->sample_rate;
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return 0;
    }
}

JNIEXPORT jboolean JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1uses_1espeak_1phonemes
  (JNIEnv *env, jclass, jlong synth) {
    if (synth == 0) return JNI_FALSE;
    try {
        piper_synthesizer *s = reinterpret_cast<piper_synthesizer *>(synth);
        return (jboolean) !s->espeak_voice.empty();
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1get_1version
  (JNIEnv *env, jclass) {
    return env->NewStringUTF(_PIPER_VERSION);
}
