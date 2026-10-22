// AntiSocial's offline-only bridge. Runtime: QVAC/ggml MIT; Bonsai data: Apache 2.0.
// This module has no networking, model acquisition, tools, or controller actions.
#include "text_runtime.h"
#include "json_grammar.h"
#include "kv_position_profile.h"
#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace antisocial_ai {
using json = nlohmann::ordered_json;
namespace {
constexpr size_t max_instruction_cache_bytes = 128 * 1024 * 1024;
struct InstructionState {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> data;
    uint64_t used = 0;
};
struct State {
    std::mutex job;
    std::atomic<bool> cancelled{false};
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_chat_templates_ptr templates;
    std::vector<ggml_backend_dev_t> devices;
    std::string route;
    std::string device;
    std::string cache_profile;
    std::string position_profile;
    json position_recipe;
    int capacity = 0;
    int threads = 0;
    int generated = 0;
    int input_tokens = 0;
    bool json_mode = false;
    std::string sampling_profile = "prism_non_thinking_reference_v1";
    std::vector<InstructionState> instructions;
    uint64_t instruction_clock = 0;
    bool instruction_cache_hit = false;
    int instruction_prefix_tokens = 0;
    int prefilled_tokens = 0;
    size_t instruction_cache_budget = 0;
    size_t instruction_cache_attempt_bytes = 0;
    double elapsed_ms = 0;
    std::string finish = "not_started";
    ~State() {
        templates.reset();
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::once_flag initialized;
std::mutex lifecycle_mutex;
std::mutex registry_mutex;
std::unordered_map<int64_t, std::shared_ptr<State>> registry;
std::atomic<int64_t> next_handle{1};
void initialize() {
    std::call_once(initialized, [] { ggml_backend_load_all(); llama_backend_init(); });
}
std::shared_ptr<State> state(int64_t handle) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto found = registry.find(handle);
    if (found == registry.end()) throw std::runtime_error("Local text model is closed");
    return found->second;
}
bool aborted(void * data) { return static_cast<State *>(data)->cancelled.load(); }
void check_cancel(State & s) {
    if (s.cancelled.load()) throw std::runtime_error("Local text request cancelled");
}
std::string meta(llama_model * model, const char * key) {
    std::vector<char> buffer(256);
    int n = llama_model_meta_val_str(model, key, buffer.data(), buffer.size());
    if (n < 0) return "";
    if (static_cast<size_t>(n) >= buffer.size()) {
        buffer.resize(static_cast<size_t>(n) + 1);
        llama_model_meta_val_str(model, key, buffer.data(), buffer.size());
    }
    return std::string(buffer.data());
}
void clear(State & s) {
    if (s.context) llama_memory_clear(llama_get_memory(s.context), true);
}
struct ClearOnExit { State & s; ~ClearOnExit() { clear(s); } };
struct JobOutcome {
    State & s;
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();
    bool success = false;
    ~JobOutcome() {
        s.elapsed_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        if (!success) s.finish = s.cancelled.load() ? "cancelled" : "error";
    }
};
bool repeating(const std::vector<llama_token> & tokens) {
    // Stop a sustained repeated phrase, without treating a repeated normal word as a loop.
    constexpr size_t block = 12;
    if (tokens.size() < block * 3) return false;
    auto end = tokens.end();
    return std::equal(end - block, end, end - block * 2) &&
           std::equal(end - block, end, end - block * 3);
}
}

std::string capabilities() {
    initialize();
    json devices = json::array();
    bool vulkan = false;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto d = ggml_backend_dev_get(i);
        const std::string backend = ggml_backend_reg_name(ggml_backend_dev_backend_reg(d));
        size_t available = 0, total = 0;
        ggml_backend_dev_memory(d, &available, &total);
        bool vk = backend == "Vulkan" && (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU || ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_IGPU);
        vulkan = vulkan || vk;
        devices.push_back({{"name", ggml_backend_dev_name(d)}, {"backend", backend},
            {"available_bytes", available}, {"total_bytes", total}, {"gpu", vk}});
    }
    return json({{"runtime", "QVAC Bonsai"}, {"revision", ANTISOCIAL_QVAC_REVISION},
        {"cpu", true}, {"vulkan", vulkan}, {"devices", devices},
        {"instruction_cache_max_bytes", max_instruction_cache_bytes}, {"packed_bonsai", true}, {"json_object_grammar", true}, {"advanced_cache_qualified", false},
        {"cache_candidates", {"pq4_protected_v2", "pq3_protected_v2", "pq4_pq3_protected_v2", "signed4_protected_v2", "planar4_protected_v2", "iso4_protected_v2"}},
        {"networking", false}, {"maximum_context", 8192}}).dump();
}

int64_t open(const std::string & path, const std::string & backend, int context, int threads, const std::string & cache_profile, const std::string & position_profile) {
    initialize();
    std::lock_guard<std::mutex> lifecycle(lifecycle_mutex);
    {
        std::lock_guard<std::mutex> guard(registry_mutex);
        if (!registry.empty()) throw std::runtime_error("A local text model is already open");
    }
    if (path.empty() || path.find('\0') != std::string::npos) throw std::runtime_error("Invalid local model path");
    if (backend != "auto" && backend != "cpu" && backend != "vulkan") throw std::runtime_error("Unknown text backend");
    if (context < 512 || context > 8192 || threads < 1 || threads > 4) throw std::runtime_error("Unsupported local memory/thread plan");
    if (position_profile != "native" && position_profile != "yarn2") throw std::runtime_error("Unknown positional profile");
    auto s = std::make_shared<State>();
    if (cache_profile != "f16_reference" && cache_profile != "q8_reference" && cache_profile != "q4_reference" && cache_profile != "pq4_protected_v2" && cache_profile != "pq3_protected_v2" && cache_profile != "pq4_pq3_protected_v2" && cache_profile != "signed4_protected_v2" && cache_profile != "planar4_protected_v2" && cache_profile != "iso4_protected_v2") throw std::runtime_error("Unknown KV profile");
    s->cache_profile = cache_profile;
    s->position_profile = position_profile;
    s->capacity = context;
    s->threads = threads;
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mtp = false;
    params.check_tensors = true;
    params.devices = nullptr;
    params.progress_callback = [](float, void * p) { return !static_cast<State *>(p)->cancelled.load(); };
    params.progress_callback_user_data = s.get();
    s->route = "cpu";
    s->device = "CPU";
    if (backend != "cpu") {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto d = ggml_backend_dev_get(i);
            if (std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(d))) == "Vulkan" &&
                (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU || ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_IGPU)) {
                s->devices = {d, nullptr};
                params.devices = s->devices.data();
                params.n_gpu_layers = 999;
                params.split_mode = LLAMA_SPLIT_MODE_NONE;
                s->route = "vulkan";
                s->device = ggml_backend_dev_name(d);
                break;
            }
        }
        if (backend == "vulkan" && s->route != "vulkan") throw std::runtime_error("Vulkan text backend is unavailable on this device");
    }
    s->model = llama_model_load_from_file(path.c_str(), params);
    if (!s->model) throw std::runtime_error("Unable to load the verified Bonsai model with this backend");
    const std::string transform_version = meta(s->model, "prism.hadamard.version");
    if ((transform_version != "1" && transform_version != "2") || meta(s->model, "general.architecture") != "qwen35")
        throw std::runtime_error("The installed model does not match the supported Bonsai graph");
    auto cp = llama_context_default_params();
    configure_position_profile(cp, s->model, position_profile);
    cp.n_ctx = static_cast<uint32_t>(context);
    cp.n_batch = 64;
    cp.n_ubatch = 32;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.type_k = GGML_TYPE_F16;
    cp.type_v = GGML_TYPE_F16;
    if (cache_profile == "q8_reference") cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
    if (cache_profile == "q4_reference") cp.type_k = cp.type_v = GGML_TYPE_Q4_0;
    if (cache_profile.find("protected_v") != std::string::npos) {
        cp.type_k = cache_profile == "pq3_protected_v2" ? GGML_TYPE_PQ3_0 : GGML_TYPE_PQ4_0;
        cp.type_v = (cache_profile == "pq3_protected_v2" || cache_profile == "pq4_pq3_protected_v2") ? GGML_TYPE_PQ3_0 : GGML_TYPE_PQ4_0;
        cp.kv_rotation = cache_profile == "signed4_protected_v2" ? 1 : cache_profile == "planar4_protected_v2" ? 2 : cache_profile == "iso4_protected_v2" ? 3 : 0;
        cp.kv_protected_recent = 256;
        cp.kv_protected_initial = 16;
    }
    cp.flash_attn_type = cp.kv_protected_recent ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.abort_callback = aborted;
    cp.abort_callback_data = s.get();
    cp.offload_kqv = s->route == "vulkan";
    cp.op_offload = s->route == "vulkan";
    cp.no_perf = false;
    s->context = llama_init_from_model(s->model, cp);
    if (!s->context) throw std::runtime_error("Not enough compatible memory for the local text model");
    s->position_recipe = resolved_position_recipe(s->context, s->model);
    s->templates = common_chat_templates_init(s->model, "");
    if (!common_chat_templates_support_enable_thinking(s->templates.get()))
        throw std::runtime_error("The installed model has no supported direct-answer template");
    int64_t handle = next_handle.fetch_add(1);
    std::lock_guard<std::mutex> guard(registry_mutex);
    registry.emplace(handle, s);
    return handle;
}

int count_tokens(int64_t handle, const std::string & system, const std::string & user) {
    auto s = state(handle);
    std::unique_lock<std::mutex> guard(s->job, std::try_to_lock);
    if (!guard.owns_lock()) throw std::runtime_error("Local text model is already processing a request");
    if (system.size() + user.size() > 524288) throw std::runtime_error("Text planning request is too large");
    common_chat_templates_inputs input;
    input.use_jinja = true;
    input.enable_thinking = false;
    input.chat_template_kwargs["enable_thinking"] = "false";
    input.messages.push_back({"system", system});
    input.messages.push_back({"user", user});
    const auto prompt = common_chat_templates_apply(s->templates.get(), input).prompt;
    int required = llama_tokenize(llama_model_get_vocab(s->model), prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, true, true);
    if (required >= 0) throw std::runtime_error("Unexpected tokenizer size contract");
    return -required;
}

namespace {
std::vector<llama_token> instruction_prefix(State & s, const std::string & system, const std::vector<llama_token> & actual) {
    if (system.empty()) return {};
    const std::string marker="__ANTISOCIAL_PREFIX_BOUNDARY_893F1A__";
    common_chat_templates_inputs input;
    input.use_jinja=true;input.enable_thinking=false;input.chat_template_kwargs["enable_thinking"]="false";
    input.messages.push_back({"system",system});input.messages.push_back({"user",marker});
    const auto rendered=common_chat_templates_apply(s.templates.get(),input).prompt;
    const auto boundary=rendered.find(marker);
    if (boundary==std::string::npos || rendered.find(marker,boundary+marker.size())!=std::string::npos) return {};
    const auto prefix=rendered.substr(0,boundary);
    auto vocab=llama_model_get_vocab(s.model);
    int needed=llama_tokenize(vocab,prefix.data(),int(prefix.size()),nullptr,0,true,true);
    if (needed>=0) return {};
    std::vector<llama_token> tokens(size_t(-needed));
    int count=llama_tokenize(vocab,prefix.data(),int(prefix.size()),tokens.data(),int(tokens.size()),true,true);
    if (count<=1 || actual.size()<2) return {};
    // Drop the final prefix token (including any automatic EOS), then retain
    // only matching whole batches. No reader token is captured and the fresh
    // and restored paths use the exact same 32-token prefill boundaries.
    size_t n=0,limit=std::min(size_t(count-1),actual.size()-1);
    while(n<limit && tokens[n]==actual[n])++n;
    n=n/32*32;tokens.resize(n);return tokens;
}
void decode_range(State & s, const std::vector<llama_token> & tokens, size_t start, size_t end) {
    for(size_t offset=start;offset<end;offset+=32) {
        check_cancel(s);int n=int(std::min(size_t(32),end-offset));
        auto batch=llama_batch_get_one(const_cast<llama_token *>(tokens.data()+offset),n);
        if(llama_decode(s.context,batch)!=0){check_cancel(s);throw std::runtime_error("Local text prefill failed");}
        s.prefilled_tokens+=n;
    }
}
size_t instruction_bytes(const State & s) {
    size_t bytes = 0;
    for (const auto & entry : s.instructions) bytes += entry.data.size() + entry.tokens.size() * sizeof(llama_token);
    return bytes;
}
void evict_instruction(State & s) {
    s.instructions.erase(std::min_element(s.instructions.begin(), s.instructions.end(),
        [](const auto & a, const auto & b) { return a.used < b.used; }));
}
void prefill_with_instructions(State & s, const std::string & system, const std::vector<llama_token> & tokens, size_t budget) {
    s.instruction_cache_hit = false;
    s.instruction_prefix_tokens = 0;
    s.prefilled_tokens = 0;
    s.instruction_cache_budget = budget;
    s.instruction_cache_attempt_bytes = 0;
    while (!s.instructions.empty() && instruction_bytes(s) > budget) evict_instruction(s);
    // A zero budget actively discards the cache, including empty snapshots.
    if (!budget) s.instructions.clear();
    std::vector<llama_token> prefix;
    if (budget) {
        try { prefix = instruction_prefix(s, system, tokens); }
        catch (const std::exception &) { check_cancel(s); }
    }
    const size_t n = prefix.size();
    for (auto it = s.instructions.begin(); it != s.instructions.end(); ++it) {
        if (!n || it->tokens != prefix) continue;
        try {
            check_cancel(s);
            const auto restored = llama_state_seq_set_data(s.context, it->data.data(), it->data.size(), 0);
            check_cancel(s);
            if (restored == it->data.size()) { s.instruction_cache_hit = true; it->used = ++s.instruction_clock; }
        } catch (const std::exception &) { check_cancel(s); }
        if (!s.instruction_cache_hit) { s.instructions.erase(it); clear(s); }
        break;
    }
    s.instruction_prefix_tokens = static_cast<int>(n);
    if (!s.instruction_cache_hit) {
        decode_range(s, tokens, 0, n);
        if (n) {
            try {
                // Save the full hybrid/recurrent sequence state, not just KV.
                // This happens before any reader-supplied token is decoded.
                llama_synchronize(s.context);
                check_cancel(s);
                const size_t bytes = llama_state_seq_get_size(s.context, 0);
                s.instruction_cache_attempt_bytes = bytes;
                const size_t token_bytes = prefix.size() * sizeof(llama_token);
                if (bytes && token_bytes <= budget && bytes <= budget - token_bytes) {
                    const size_t required = bytes + token_bytes;
                    while (!s.instructions.empty() && (s.instructions.size() >= 4 || instruction_bytes(s) > budget - required)) evict_instruction(s);
                    InstructionState saved;
                    saved.tokens = prefix;
                    saved.data.resize(bytes);
                    saved.used = ++s.instruction_clock;
                    if (llama_state_seq_get_data(s.context, saved.data.data(), bytes, 0) == bytes)
                        s.instructions.push_back(std::move(saved));
                }
            } catch (const std::exception &) { check_cancel(s); } // Optional acceleration; fresh prefill remains valid.
        }
    }
    decode_range(s, tokens, n, tokens.size());
}
}

std::string generate(int64_t handle, const std::string & system, const std::string & user, int max_tokens, uint32_t seed, bool json_mode, int instruction_cache_bytes, const std::string & sampling_profile) {
    auto s = state(handle);
    std::unique_lock<std::mutex> guard(s->job, std::try_to_lock);
    if (!guard.owns_lock()) throw std::runtime_error("Local text model is already processing a request");
    if (max_tokens < 1 || max_tokens > 1024 || system.size() + user.size() > 524288)
        throw std::runtime_error("Local text request exceeds its bounded input/output plan");
    if (sampling_profile != "prism_non_thinking_reference_v1" && sampling_profile != "context_greedy_v1")
        throw std::runtime_error("Unknown sampling profile");
    if (sampling_profile == "context_greedy_v1" && !json_mode)
        throw std::runtime_error("The experimental Context profile requires structured output");
    check_cancel(*s);
    clear(*s);
    ClearOnExit clear_after{*s};
    s->finish = "running";
    s->json_mode = json_mode;
    s->sampling_profile = sampling_profile;
    s->generated = 0;
    JobOutcome outcome{*s};
    common_chat_templates_inputs input;
    input.use_jinja = true;
    input.enable_thinking = false;
    input.chat_template_kwargs["enable_thinking"] = "false";
    input.messages.push_back({"system", system});
    input.messages.push_back({"user", user});
    const std::string prompt = common_chat_templates_apply(s->templates.get(), input).prompt;
    auto vocab = llama_model_get_vocab(s->model);
    int required = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, true, true);
    if (required >= 0) throw std::runtime_error("Unexpected tokenizer size contract");
    std::vector<llama_token> tokens(static_cast<size_t>(-required));
    int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), static_cast<int>(tokens.size()), true, true);
    if (count <= 0) throw std::runtime_error("The text request has no model tokens");
    tokens.resize(static_cast<size_t>(count));
    s->input_tokens = count;
    if (count + max_tokens > static_cast<int>(llama_n_ctx(s->context))) throw std::runtime_error("Text is too long for this device's active context");
    const size_t budget = json_mode ? static_cast<size_t>(std::clamp(instruction_cache_bytes, 0, static_cast<int>(max_instruction_cache_bytes))) : 0;
    prefill_with_instructions(*s, system, tokens, budget);
    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    // Prism's non-thinking vendor-reference profile, kept separate from future task profiles.
    if (sampling_profile == "prism_non_thinking_reference_v1")
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, 1.5f));
    // Apply grammar to the full candidate vocabulary before truncation. The
    // chain owns it; llama_sampler_sample accepts exactly once automatically.
    if (json_mode) {
        auto * grammar = llama_sampler_init_grammar(vocab, json_object_grammar, "root");
        if (!grammar) throw std::runtime_error("The local JSON grammar could not be initialized");
        llama_sampler_chain_add(sampler.get(), grammar);
    }
    if (sampling_profile == "context_greedy_v1") {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));
    }
    std::vector<llama_token> output_tokens;
    std::string output;
    s->finish = "length";
    for (int i = 0; i < max_tokens; ++i) {
        check_cancel(*s);
        llama_token token = llama_sampler_sample(sampler.get(), s->context, -1);
        if (llama_vocab_is_eog(vocab, token)) { s->finish = "end"; break; }
        output_tokens.push_back(token);
        if (repeating(output_tokens)) { s->finish = "repetition"; break; }
        std::vector<char> piece(256);
        int n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (n < 0) { piece.resize(static_cast<size_t>(-n)); n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false); }
        if (n < 0) throw std::runtime_error("Unable to decode the generated text");
        output.append(piece.data(), static_cast<size_t>(n));
        ++s->generated;
        if (i + 1 < max_tokens) {
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(s->context, batch) != 0) { check_cancel(*s); throw std::runtime_error("Local text decoding failed"); }
        }
    }
    check_cancel(*s);
    if (json_mode) {
        // A token/repetition limit may interrupt a valid prefix. Never repair
        // it or publish it as a complete structured result.
        auto object = json::parse(output, nullptr, false);
        if (object.is_discarded() || !object.is_object())
            throw std::runtime_error("The local model did not finish a JSON object within its output limit");
    }
    outcome.success = true;
    return output;
}

std::string diagnostics(int64_t handle) {
    auto s = state(handle);
    std::lock_guard<std::mutex> guard(s->job);
    char description[256] = {};
    llama_model_desc(s->model, description, sizeof(description));
    return json({{"runtime_revision", ANTISOCIAL_QVAC_REVISION}, {"model", description},
        {"parameter_count", llama_model_n_params(s->model)}, {"model_bytes", llama_model_size(s->model)},
        {"selected_backend", s->route}, {"selected_device", s->device},
        {"operator_cpu_fallback_possible", s->route != "cpu"}, {"context", llama_n_ctx(s->context)},
        {"threads", s->threads}, {"cache", s->cache_profile}, {"cache_implementation", "antisocial-composed-kv-v2"},
        {"checkpoint_recipe", "antisocial-hybrid-state-v3"}, {"positioning", s->position_profile}, {"position_recipe", s->position_recipe}, {"thinking", false},
        {"sampling_profile", s->sampling_profile}, {"output_constraint", s->json_mode ? "json_object" : "freeform"}, {"input_tokens", s->input_tokens},
        {"instruction_cache_hit", s->instruction_cache_hit}, {"instruction_prefix_tokens", s->instruction_prefix_tokens},
        {"prefilled_tokens", s->prefilled_tokens}, {"instruction_cache_bytes", instruction_bytes(*s)},
        {"instruction_cache_budget", s->instruction_cache_budget}, {"instruction_cache_attempt_bytes", s->instruction_cache_attempt_bytes},
        {"generated_tokens", s->generated}, {"elapsed_ms", s->elapsed_ms}, {"finish", s->finish}}).dump();
}
void cancel(int64_t handle) { state(handle)->cancelled.store(true); }
void reset(int64_t handle) {
    auto s = state(handle);
    s->cancelled.store(true);
    std::lock_guard<std::mutex> guard(s->job);
    clear(*s);
    s->cancelled.store(false);
    s->finish = "reset";
    s->json_mode = false;
}
void close(int64_t handle) {
    std::lock_guard<std::mutex> lifecycle(lifecycle_mutex);
    std::shared_ptr<State> s;
    {
        std::lock_guard<std::mutex> guard(registry_mutex);
        auto found = registry.find(handle);
        if (found == registry.end()) return;
        s = found->second;
        registry.erase(found);
    }
    s->cancelled.store(true);
    {
        std::lock_guard<std::mutex> guard(s->job);
        clear(*s);
    }
    s.reset();
}
}
