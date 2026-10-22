// Actual-model state compatibility across positional and cache recipes.
#include "llama.h"
#include "llama-context.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
using json = nlohmann::ordered_json;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
static void require(bool value, const char * message) { if (!value) throw std::runtime_error(message); }

int main(int argc, char ** argv) { try {
    require(argc == 3, "model corpus");
    ggml_backend_load_all(); llama_backend_init();
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0; mp.load_mtp = false;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1], mp), llama_model_free);
    require(bool(model), "Model load failed");
    const uint32_t native_window = llama_model_n_ctx_train(model.get());
    auto vocab = llama_model_get_vocab(model.get());
    std::ifstream file(argv[2]); std::string text((std::istreambuf_iterator<char>(file)), {});
    int n = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    require(n >= 64, "Corpus too short"); std::vector<llama_token> tokens(n);
    require(llama_tokenize(vocab, text.data(), text.size(), tokens.data(), n, true, true) == n, "Tokenization failed");
    auto make = [&](llama_context_params cp) {
        Context ctx(llama_init_from_model(model.get(), cp), llama_free);
        require(bool(ctx), "Context failed"); return ctx;
    };
    auto decode = [&](llama_context * ctx, int offset) {
        auto batch = llama_batch_get_one(tokens.data() + offset, 32);
        require(llama_decode(ctx, batch) == 0, "Decode failed");
    };
    auto save = [&](llama_context * ctx, bool full) {
        const size_t size = full ? llama_state_get_size(ctx) : llama_state_seq_get_size(ctx, 0);
        std::vector<uint8_t> data(size);
        require((full ? llama_state_get_data(ctx, data.data(), size) : llama_state_seq_get_data(ctx, data.data(), size, 0)) == size, "Save failed");
        return data;
    };
    auto load = [&](llama_context * ctx, const std::vector<uint8_t> & data, bool full) {
        return full ? llama_state_set_data(ctx, data.data(), data.size()) : llama_state_seq_set_data(ctx, data.data(), data.size(), 0);
    };
    json cases = json::array(); int rejections = 0;
    for (bool packed : {false, true}) {
        auto cp = llama_context_default_params(); cp.n_ctx = 1024; cp.n_batch = cp.n_ubatch = 32;
        cp.n_threads = cp.n_threads_batch = 4; cp.n_seq_max = 1; cp.offload_kqv = cp.op_offload = false;
        cp.type_k = cp.type_v = packed ? GGML_TYPE_PQ4_0 : GGML_TYPE_F16;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        if (packed) { cp.kv_protected_initial = 16; cp.kv_protected_recent = 256; }
        auto original = make(cp); decode(original.get(), 0);
        auto sequence = save(original.get(), false), full = save(original.get(), true);
        decode(original.get(), 32);
        const auto * values = llama_get_logits_ith(original.get(), -1);
        std::vector<float> expected(values, values + llama_vocab_n_tokens(vocab));
        for (bool entire : {false, true}) {
            const auto & snapshot = entire ? full : sequence;
            require(load(original.get(), snapshot, entire) == snapshot.size(), "Identical recipe rejected");
            decode(original.get(), 32); values = llama_get_logits_ith(original.get(), -1);
            for (size_t i = 0; i < expected.size(); ++i) require(std::isfinite(values[i]) && std::abs(values[i] - expected[i]) < 1e-5f, "Same recipe replay changed logits");
        }
        original.reset();
        std::vector<std::string> changes = {"base", "yarn2", "origin", "extension", "attention", "beta_fast", "beta_slow", "capacity", "cache_type"};
        if (packed) changes.insert(changes.end(), {"initial", "recent", "rotation", "calibration"});
        for (const auto & change : changes) {
            auto other_cp = cp; std::vector<float> scales(256, 1.f);
            if (change == "base") other_cp.rope_freq_base = 11000000.f;
            if (change == "yarn2") { other_cp.rope_scaling_type = LLAMA_ROPE_SCALING_TYPE_YARN; other_cp.rope_freq_scale = 0.5f; other_cp.yarn_orig_ctx = native_window; }
            if (change == "origin") other_cp.yarn_orig_ctx = native_window / 2;
            if (change == "extension") other_cp.yarn_ext_factor = 0.5f;
            if (change == "attention") other_cp.yarn_attn_factor = 0.75f;
            if (change == "beta_fast") other_cp.yarn_beta_fast = 16.f;
            if (change == "beta_slow") other_cp.yarn_beta_slow = 2.f;
            if (change == "capacity") other_cp.n_ctx = 2048;
            if (change == "cache_type") other_cp.type_v = packed ? GGML_TYPE_PQ3_0 : GGML_TYPE_Q8_0;
            if (change == "initial") other_cp.kv_protected_initial = 8;
            if (change == "recent") other_cp.kv_protected_recent = 128;
            if (change == "rotation") other_cp.kv_rotation = 2;
            if (change == "calibration") { scales[17] = 1.25f; other_cp.kv_channel_scale = scales.data(); other_cp.kv_channel_scale_count = scales.size(); }
            auto other = make(other_cp); decode(other.get(), 0);
            for (bool entire : {false, true}) {
                const auto before = save(other.get(), entire);
                require(load(other.get(), entire ? full : sequence, entire) == 0, "Changed recipe accepted");
                require(save(other.get(), entire) == before, "Rejected recipe altered live hybrid state");
                ++rejections;
            }
            cases.push_back({{"cache", packed ? "pq4" : "f16"}, {"changed", change}, {"sequence_and_full_rejected_without_mutation", true}});
        }
        require(llama_model_n_ctx_train(model.get()) == native_window, "Context override mutated the model's native window");
        auto fresh = make(cp); const auto & resolved = fresh->get_cparams();
        require(resolved.n_ctx_orig_yarn == native_window && resolved.rope_freq_scale == 1.f, "YaRN override leaked into a later native context");
    }
    std::cout << json({{"passed", true}, {"rejections_without_mutation", rejections}, {"native_context_unchanged", native_window}, {"cases", cases}}).dump(2) << '\n';
    return 0;
} catch (const std::exception & e) { std::cerr << e.what() << '\n'; return 1; } }
