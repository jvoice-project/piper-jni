#include "io_github_jvoiceproject_piperjni_internal_PiperBinding.h"
#include "piper.h"
#include "piper_impl.hpp"
#include <json.hpp>
#include <onnxruntime_cxx_api.h>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <new>
#include <string>
#include <string_view>
#include <vector>
#include <cctype>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#include <cwctype>
#elif defined(__APPLE__)
#include <dlfcn.h>
#include <mach-o/dyld.h>
#else
#include <dlfcn.h>
#include <link.h>
#endif

#if !defined(PIPER_ORT_API_VERSION)
#error "PIPER_ORT_API_VERSION must be defined by the build (derived from <onnxruntime.version> in pom.xml)"
#endif

// The ONNX Runtime headers must match the version configured in pom.xml, otherwise the C++ wrapper
// may call into API entries that the minimum supported runtime does not provide.
// The minimum ONNX Runtime version itself is enforced by the Java layer (NativeLibraryLoader).
static_assert(PIPER_ORT_API_VERSION == ORT_API_VERSION,
              "ONNX Runtime headers do not match <onnxruntime.version> from pom.xml");

/// Set (with release semantics) once the ONNX Runtime API table has been initialized.
static std::atomic<bool> ort_initialized{false};

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
    if (!mid_options_ctor) return JNI_ERR;
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

JNIEXPORT jlong JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1create_1with_1options
  (JNIEnv *env, jclass, jstring modelPath, jstring configPath, jstring espeakDataPath, jstring g2pwModelDir) {
    try {
        if (!ort_initialized.load(std::memory_order_acquire)) {
            throwJava(env, "java/lang/IllegalStateException", "ONNX Runtime API is not initialized");
            return 0;
        }
        JNIString cModelPath(env, modelPath);
        JNIString cConfigPath(env, configPath);
        JNIString cEspeakDataPath(env, espeakDataPath);
        JNIString cG2pwModelDir(env, g2pwModelDir);

        piper_create_options opts;
        piper_init_create_options(&opts);
        opts.model_path = cModelPath.get();
        opts.config_path = cConfigPath.get();
        opts.espeak_data_path = cEspeakDataPath.get();
        opts.g2pw_model_dir = cG2pwModelDir.get();

        piper_synthesizer *synth = piper_create_with_options(&opts);
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

        // Any status other than OK or DONE is an error
        if (res != PIPER_OK && res != PIPER_DONE) {
            throwJava(env, "java/io/IOException", "Failed to synthesize next chunk");
            return nullptr;
        }
        // No samples remaining: end of stream
        if (chunk.num_samples == 0 || chunk.samples == nullptr) {
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

        // Delete local references so we don't prevent GC
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
        return (jboolean)(s->phoneme_type == PhonemeType::Espeak && !s->espeak_voice.empty());
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1version
  (JNIEnv *env, jclass) {
    return env->NewStringUTF(piper_version());
}

namespace {

using OrtGetApiBaseFn = const OrtApiBase *(ORT_API_CALL *)(void);

static std::mutex ort_init_mutex;
static std::string ort_loaded_version; // guarded by ort_init_mutex

#if defined(_WIN32)
/// Whether the file name of a loaded module looks like an ONNX Runtime library (case-insensitive).
static bool is_ort_library_name(const std::wstring &path) {
    size_t sep = path.find_last_of(L"\\/");
    std::wstring base = (sep == std::wstring::npos) ? path : path.substr(sep + 1);
    for (wchar_t &c : base) {
        c = static_cast<wchar_t>(std::towlower(c));
    }
    return base.find(L"onnxruntime") != std::wstring::npos;
}
#else
/// Whether the file name of a loaded library looks like an ONNX Runtime library.
/// The name is not required to be exactly libonnxruntime.so/.dylib, since ONNX Runtime Java can be
/// pointed to an arbitrary file via -Donnxruntime.native.onnxruntime.path; the OrtGetApiBase
/// symbol lookup decides in the end.
static bool is_ort_library_name(const char *path) noexcept {
    std::string_view view(path);
    size_t slash = view.find_last_of('/');
    std::string_view base = (slash == std::string_view::npos) ? view : view.substr(slash + 1);
    constexpr std::string_view needle = "onnxruntime";
    for (size_t i = 0; i + needle.size() <= base.size(); ++i) {
        size_t j = 0;
        while (j < needle.size() &&
               std::tolower(static_cast<unsigned char>(base[i + j])) == needle[j]) {
            ++j;
        }
        if (j == needle.size()) {
            return true;
        }
    }
    return false;
}

/// Looks up OrtGetApiBase in an already-loaded library, without loading anything new.
static OrtGetApiBaseFn lookup_in_loaded_library(const char *name) {
    void *handle = dlopen(name, RTLD_LAZY | RTLD_NOLOAD);
    if (!handle) {
        return nullptr;
    }
    void *sym = dlsym(handle, "OrtGetApiBase");
    if (!sym) {
        dlclose(handle);
        return nullptr;
    }
    // Keep the reference so the library cannot be unloaded while we use its API table.
    return reinterpret_cast<OrtGetApiBaseFn>(sym);
}
#endif

/// Finds OrtGetApiBase in an ONNX Runtime library that is already loaded in the current process.
static OrtGetApiBaseFn discover_loaded_ort_library(std::string &errorOut) {
#if defined(_WIN32)
    HANDLE process = GetCurrentProcess();
    std::vector<HMODULE> modules(256);
    for (;;) {
        DWORD needed = 0;
        DWORD size = static_cast<DWORD>(modules.size() * sizeof(HMODULE));
        if (!K32EnumProcessModules(process, modules.data(), size, &needed)) {
            DWORD lastError = GetLastError();
            errorOut = "Failed to enumerate loaded modules (Win32 error " +
                std::to_string(lastError) + ")";
            return nullptr;
        }
        if (needed <= size) {
            modules.resize(needed / sizeof(HMODULE));
            break;
        }
        modules.resize(needed / sizeof(HMODULE));
    }
    std::wstring name(MAX_PATH, L'\0');
    for (HMODULE mod : modules) {
        DWORD len;
        for (;;) {
            len = GetModuleFileNameW(mod, name.data(), static_cast<DWORD>(name.size()));
            if (len == 0 || len < name.size()) {
                break;
            }
            name.resize(name.size() * 2);
        }
        if (len == 0 || !is_ort_library_name(std::wstring(name.data(), len))) {
            continue;
        }
        if (FARPROC sym = GetProcAddress(mod, "OrtGetApiBase")) {
            return reinterpret_cast<OrtGetApiBaseFn>(sym);
        }
    }
    errorOut = "ONNX Runtime library (onnxruntime.dll) is not loaded in the current process";
    return nullptr;
#else
    std::vector<std::string> candidates;
#if defined(__APPLE__)
    uint32_t imageCount = _dyld_image_count();
    for (uint32_t i = 0; i < imageCount; ++i) {
        const char *imageName = _dyld_get_image_name(i);
        if (imageName && is_ort_library_name(imageName)) {
            candidates.emplace_back(imageName);
        }
    }
#else
    // Only collect names inside the callback: glibc holds the loader's write lock while running it,
    // and calling dlopen() from there can deadlock against a concurrent dlopen() on another thread.
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *data) -> int {
        if (!info->dlpi_name || info->dlpi_name[0] == '\0' || !is_ort_library_name(info->dlpi_name)) {
            return 0;
        }
        try {
            static_cast<std::vector<std::string> *>(data)->emplace_back(info->dlpi_name);
        } catch (...) {
            return 1; // never let a C++ exception unwind through libc frames
        }
        return 0;
    }, &candidates);
#endif
    for (const std::string &candidate : candidates) {
        if (OrtGetApiBaseFn fn = lookup_in_loaded_library(candidate.c_str())) {
            return fn;
        }
    }
    if (void *sym = dlsym(RTLD_DEFAULT, "OrtGetApiBase")) {
        return reinterpret_cast<OrtGetApiBaseFn>(sym);
    }
#if defined(__APPLE__)
    errorOut = "ONNX Runtime library (libonnxruntime.dylib) is not loaded in the current process";
#else
    errorOut = "ONNX Runtime library (libonnxruntime.so) is not loaded in the current process";
#endif
    return nullptr;
#endif
}

} // namespace

JNIEXPORT jstring JNICALL Java_io_github_jvoiceproject_piperjni_internal_PiperBinding_piper_1init_1onnxruntime
  (JNIEnv *env, jclass) {
    try {
        std::lock_guard<std::mutex> lock(ort_init_mutex);

        // Initialization happens at most once per process
        if (ort_initialized.load(std::memory_order_acquire)) {
            return env->NewStringUTF(ort_loaded_version.c_str());
        }

        std::string errorMsg;
        OrtGetApiBaseFn getApiBase = discover_loaded_ort_library(errorMsg);
        if (!getApiBase) {
            throwJava(env, "java/lang/UnsatisfiedLinkError", errorMsg.c_str());
            return nullptr;
        }

        const OrtApiBase *apiBase = getApiBase();
        if (!apiBase) {
            throwJava(env, "java/lang/UnsatisfiedLinkError", "OrtGetApiBase() returned null");
            return nullptr;
        }

        const char *versionStr = apiBase->GetVersionString ? apiBase->GetVersionString() : nullptr;
        std::string version = versionStr ? versionStr : "";

        const OrtApi *api = apiBase->GetApi(ORT_API_VERSION);
        if (!api) {
            std::string msg = std::string("ONNX Runtime version '") +
                (version.empty() ? "unknown" : version) +
                "' does not support ORT API version " + std::to_string(ORT_API_VERSION);
            throwJava(env, "java/lang/UnsatisfiedLinkError", msg.c_str());
            return nullptr;
        }

        Ort::InitApi(api);
        ort_loaded_version = version;
        ort_initialized.store(true, std::memory_order_release);
        return env->NewStringUTF(ort_loaded_version.c_str());
    } catch (...) {
        swallow_cpp_exception_and_throw_java(env);
        return nullptr;
    }
}
