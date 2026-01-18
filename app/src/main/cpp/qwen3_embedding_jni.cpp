#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <thread>
#include <cmath>

#include "llama.h"

namespace {
    const char * kLogTag = "QwenEmbeddingJNI";

    struct EmbeddingState {
        llama_model * model = nullptr;
        llama_context * ctx = nullptr;
        int32_t n_embd = 0;
        uint32_t n_ctx = 0;
        std::mutex mutex;
    };

    std::mutex g_backend_mutex;
    bool g_backend_ready = false;

    void ensure_backend() {
        std::lock_guard<std::mutex> lock(g_backend_mutex);
        if (g_backend_ready) {
            return;
        }
        llama_backend_init();
        g_backend_ready = true;
    }

    void log_error(const char * message) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
    }

    void log_error(const std::string & message) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
    }

    std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, int32_t max_tokens) {
        if (text.empty()) {
            return {};
        }
        std::vector<llama_token> tokens(max_tokens);
        int32_t count = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            max_tokens,
            true,
            false
        );
        if (count < 0) {
            count = -count;
            if (count > max_tokens) {
                count = max_tokens;
            }
        }
        tokens.resize(static_cast<size_t>(count));
        return tokens;
    }

    void normalize_l2(std::vector<float> & values) {
        float sum = 0.0f;
        for (float v : values) {
            sum += v * v;
        }
        if (sum <= 0.0f) {
            return;
        }
        float inv = 1.0f / std::sqrt(sum);
        for (float & v : values) {
            v *= inv;
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_kaoyanassistant_services_LocalEmbeddingEngine_nativeInit(
    JNIEnv * env,
    jobject /* thiz */,
    jstring model_path
) {
    ensure_backend();

    const char * path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (!path_chars) {
        log_error("Model path is null");
        return 0;
    }
    std::string model_path_str(path_chars);
    env->ReleaseStringUTFChars(model_path, path_chars);

    auto * state = new EmbeddingState();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    state->model = llama_model_load_from_file(model_path_str.c_str(), mparams);
    if (!state->model) {
        log_error("Failed to load model");
        delete state;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_LAST;
    cparams.n_ctx = 4096;
    cparams.n_batch = 4096;
    cparams.n_ubatch = 4096;
    cparams.n_seq_max = 1;
    cparams.n_threads = std::max(1u, std::thread::hardware_concurrency());
    cparams.n_threads_batch = cparams.n_threads;
    cparams.kv_unified = true;

    state->ctx = llama_init_from_model(state->model, cparams);
    if (!state->ctx) {
        log_error("Failed to init context");
        llama_model_free(state->model);
        delete state;
        return 0;
    }

    llama_set_embeddings(state->ctx, true);

    state->n_embd = llama_model_n_embd(state->model);
    state->n_ctx = llama_n_ctx(state->ctx);

    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_kaoyanassistant_services_LocalEmbeddingEngine_nativeEmbed(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jstring text
) {
    if (handle == 0) {
        log_error("Embedding handle is null");
        return nullptr;
    }
    auto * state = reinterpret_cast<EmbeddingState *>(handle);
    if (!state->ctx || !state->model) {
        log_error("Embedding state is invalid");
        return nullptr;
    }
    const char * text_chars = env->GetStringUTFChars(text, nullptr);
    if (!text_chars) {
        log_error("Embedding text is null");
        return nullptr;
    }
    std::string text_str(text_chars);
    env->ReleaseStringUTFChars(text, text_chars);

    std::lock_guard<std::mutex> lock(state->mutex);
    const llama_vocab * vocab = llama_model_get_vocab(state->model);
    const int32_t max_tokens = static_cast<int32_t>(state->n_ctx);
    std::vector<llama_token> tokens = tokenize(vocab, text_str, max_tokens);
    if (tokens.empty()) {
        log_error("Tokenization produced empty tokens");
        return nullptr;
    }

    if (static_cast<uint32_t>(tokens.size()) > state->n_ctx) {
        tokens.resize(state->n_ctx);
    }

    llama_memory_clear(llama_get_memory(state->ctx), true);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }

    int32_t decode_result = llama_decode(state->ctx, batch);
    llama_batch_free(batch);

    if (decode_result != 0) {
        log_error("llama_decode failed");
        return nullptr;
    }

    float * embd = llama_get_embeddings_seq(state->ctx, 0);
    if (!embd) {
        log_error("Failed to get embeddings");
        return nullptr;
    }

    std::vector<float> output(static_cast<size_t>(state->n_embd));
    for (int32_t i = 0; i < state->n_embd; ++i) {
        output[static_cast<size_t>(i)] = embd[i];
    }
    normalize_l2(output);

    jfloatArray result = env->NewFloatArray(state->n_embd);
    if (!result) {
        log_error("Failed to allocate float array");
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, state->n_embd, output.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_kaoyanassistant_services_LocalEmbeddingEngine_nativeRelease(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) {
        return;
    }
    auto * state = reinterpret_cast<EmbeddingState *>(handle);
    if (state->ctx) {
        llama_free(state->ctx);
        state->ctx = nullptr;
    }
    if (state->model) {
        llama_model_free(state->model);
        state->model = nullptr;
    }
    delete state;
}
