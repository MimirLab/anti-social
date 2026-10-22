// Adapted from QVAC bc474701 examples/speculative-simple (MIT).
// QVAC/ggml license and copyright notices are retained in runtime-notices.
// Development-only: count first proposals independently of replayed commits.
#include "nlohmann/json.hpp"
#include "kv_position_profile.h"
#include <fstream>
#include <cstdlib>
#include <stdexcept>
#include "arg.h"
#include "common.h"
#include "sampling.h"
#include "chat.h"
#include "speculative.h"
#include "log.h"
#include "llama.h"

#include <algorithm>
#include <clocale>
#include <cstdio>
#include <cstring>
#include <cinttypes>
#include <string>
#include <vector>
#include <utility>
#include <memory>

namespace {
struct BackendLifetime { ~BackendLifetime() { llama_backend_free(); } };
struct BatchLifetime {
    llama_batch value;
    explicit BatchLifetime(int size) : value(llama_batch_init(size, 0, 1)) {}
    ~BatchLifetime() { llama_batch_free(value); }
};
nlohmann::ordered_json buffers(const llama_context * ctx) {
    auto rows = nlohmann::ordered_json::array();
    if (ctx) for (const auto & [type, sizes] : llama_get_memory_breakdown(ctx)) {
        rows.push_back({{"buffer", ggml_backend_buft_name(type)}, {"model_bytes", sizes.model},
            {"context_bytes", sizes.context}, {"compute_bytes", sizes.compute}});
    }
    return rows;
}
}

int main(int argc, char ** argv) try {
    std::setlocale(LC_NUMERIC, "C");
    const auto wall_start = ggml_time_us();

    common_params params;

    common_init();

    if (!common_params_parse(argc, argv, params, LLAMA_EXAMPLE_SPECULATIVE)) {
        return 1;
    }

    if (params.n_predict < 1 || params.n_predict > 512) {
        LOG_ERR("%s: this bounded probe requires --n-predict in 1..512\n", __func__);
        return 1;
    }

    const auto output_limits = common_speculative_get_output_limits(
            params.n_batch, params.n_parallel, common_speculative_n_max(&params.speculative));
    params.n_outputs_max = output_limits.total;
    params.n_outputs_max_per_seq = output_limits.per_seq;

    // init llama.cpp
    llama_backend_init();
    BackendLifetime backend_lifetime;
    llama_numa_init(params.numa);

    llama_model * model_tgt = NULL;

    llama_context * ctx_tgt = NULL;

    // load the target model
    const std::string rollback = std::getenv("ANTISOCIAL_SPEC_ROLLBACK") ? std::getenv("ANTISOCIAL_SPEC_ROLLBACK") : "recurrent";
    if (rollback != "recurrent" && rollback != "checkpoint") throw std::runtime_error("Unknown rollback recipe");
    auto target_params = params;
    // The upstream speculator reserves a recurrent state per proposal position.
    // Measure that fast path against one explicit snapshot plus replay, which
    // is a materially different phone memory/latency tradeoff.
    if (rollback == "checkpoint") target_params.speculative.types.clear();
    auto llama_init_tgt = common_init_from_params(target_params);

    if (!llama_init_tgt || !llama_init_tgt->model() || !llama_init_tgt->context())
        throw std::runtime_error("Target initialization failed");

    model_tgt = llama_init_tgt->model();
    ctx_tgt   = llama_init_tgt->context();

    const llama_vocab * vocab = llama_model_get_vocab(model_tgt);

    // load the draft model (if any) - this also creates the MTP draft context when MTP speculation is enabled
    common_speculative_init_result_ptr spec_init;

    {
        common_params params_dft = common_base_params_to_speculative(params);

        spec_init = common_speculative_init_from_params(params_dft, model_tgt, ctx_tgt);
        if (!spec_init) throw std::runtime_error("Draft initialization failed");

        params.speculative.draft.ctx_tgt = ctx_tgt;
        params.speculative.draft.ctx_dft = spec_init->context();
    }

    llama_context * ctx_dft = params.speculative.draft.ctx_dft;

    // check if the context supports partial sequence removal
    const bool use_ckpt_tgt = common_context_can_seq_rm(ctx_tgt) == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    const bool use_ckpt_dft = ctx_dft && common_context_can_seq_rm(ctx_dft) == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;

    if (use_ckpt_tgt) {
        LOG_INF("speculative decoding will use checkpoints (context does not support partial sequence removal)\n");
    }

    // Tokenize the prompt
    if (const char * chat_path = std::getenv("ANTISOCIAL_SPEC_CHAT_PATH")) {
        std::ifstream input(chat_path);
        if (!input) throw std::runtime_error("Unable to read Context chat fixture");
        const auto request = nlohmann::json::parse(input);
        auto templates = common_chat_templates_init(model_tgt, "");
        if (!common_chat_templates_support_enable_thinking(templates.get()))
            throw std::runtime_error("Direct-answer template unavailable");
        common_chat_templates_inputs chat;
        chat.use_jinja = true; chat.enable_thinking = false;
        chat.chat_template_kwargs["enable_thinking"] = "false";
        chat.messages.push_back({"system", request.at("system").get<std::string>()});
        chat.messages.push_back({"user", request.at("user").get<std::string>()});
        params.prompt = common_chat_templates_apply(templates.get(), chat).prompt;
    }
    std::vector<llama_token> inp;
    inp = common_tokenize(ctx_tgt, params.prompt, true, true);
    if (inp.size() < 2) throw std::runtime_error("Prompt must contain at least two tokens");

    if (llama_n_ctx(ctx_tgt) < (uint32_t) inp.size()) {
        LOG_ERR("%s: the prompt exceeds the context size (%d tokens, ctx %d)\n", __func__, (int) inp.size(), llama_n_ctx(ctx_tgt));

        return 1;
    }

    LOG("\n\n");

    for (auto id : inp) {
        LOG("%s", common_token_to_piece(ctx_tgt, id).c_str());
    }

    int n_predict = 0;
    int n_drafted = 0;
    int n_accept  = 0;
    int64_t raw_proposed = 0, raw_accepted_prefix = 0, verification_tokens = 0, rollback_rounds = 0;
    int64_t verification_rounds = 0;
    nlohmann::ordered_json proposal_rounds = nlohmann::ordered_json::array();
    std::string generated_text;
    int emitted_tokens = 0;

    // used to determine end of generation
    bool has_eos = false;

    llama_seq_id seq_id = 0;

    // ================================================
    // everything until here is standard initialization
    // the relevant stuff for speculative decoding starts here

    const auto t_enc_start = ggml_time_us();

    // target model sampling context
    common_sampler_ptr smpl(common_sampler_init(model_tgt, params.sampling));

    // init the speculator
    const auto & params_spec = params.speculative;

    std::unique_ptr<common_speculative, decltype(&common_speculative_free)> spec_owner(
        common_speculative_init(params.speculative, 1), common_speculative_free);
    auto * spec = spec_owner.get();

    const bool plain = std::all_of(params.speculative.types.begin(), params.speculative.types.end(),
        [](auto type) { return type == COMMON_SPECULATIVE_TYPE_NONE; });
    if (spec == nullptr && !plain) {
        LOG_ERR("%s", "failed to initialize speculative decoding\n");
        return 1;
    }

    // eval the prompt on the target and feed it to the speculative implementation(s)
    {
        // Match the bounded phone prefill rather than allocate a logical batch
        // large enough for the entire prompt (and every captured layer input).
        const int chunk_size = std::min(llama_n_batch(ctx_tgt), llama_n_ubatch(ctx_tgt));
        BatchLifetime prompt_owner(chunk_size);
        auto & batch_prompt = prompt_owner.value;
        for (size_t offset = 0; offset < inp.size() - 1; offset += chunk_size) {
            common_batch_clear(batch_prompt);
            const auto end = std::min(inp.size() - 1, offset + chunk_size);
            for (size_t i = offset; i < end; ++i) common_batch_add(batch_prompt, inp[i], i, { seq_id }, false);
            if (llama_decode(ctx_tgt, batch_prompt) != 0) throw std::runtime_error("Target prefill failed");
            if (spec && !common_speculative_process(spec, batch_prompt))
                throw std::runtime_error("Failed to inject speculative prefill chunk");
        }
    }

    // note: keep the last token separate!
    llama_token id_last = inp.back();

    // all tokens currently in the target context
    llama_tokens prompt_tgt(inp.begin(), inp.end() - 1);
    prompt_tgt.reserve(llama_n_ctx(ctx_tgt));

    int n_past = inp.size() - 1;

    if (spec) common_speculative_begin(spec, seq_id, prompt_tgt);

    BatchLifetime batch_owner(llama_n_batch(ctx_tgt));
    auto & batch_tgt = batch_owner.value;

    llama_tokens draft;

    common_prompt_checkpoint ckpt;

    const auto t_enc_end = ggml_time_us();

    const auto t_dec_start = ggml_time_us();

    while (true) {
        if (++verification_rounds > 16LL * std::max(params.n_predict, 512) + 64) throw std::runtime_error("Verification replay limit exceeded");
        bool fresh_proposal = false;
        // generate or reuse draft tokens
        //
        // this is the most important part of the speculation. the more probable tokens that are provided here
        // the better the performance will be. in theory, this computation can be performed asynchronously and even
        // offloaded to a remote device. it doesn't even have to be based on an LLM. instead, it can provide tokens
        // from a cache or lookup tables.
        //
        if (draft.empty()) {
            ckpt.update_pos(
                    prompt_tgt.size(),
                    llama_memory_seq_pos_min(llama_get_memory(ctx_tgt), seq_id),
                    llama_memory_seq_pos_max(llama_get_memory(ctx_tgt), seq_id));

            if (use_ckpt_dft) {
                ckpt.update_dft(ctx_dft, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
            }

            // determine the max draft that fits the remaining context and generation budget
            int n_draft_max = (int) llama_n_ctx(ctx_tgt) - n_past - 2;
            if (params.n_predict >= 0) {
                n_draft_max = std::min(n_draft_max, params.n_predict - n_predict - 1);
            }
            n_draft_max = std::max(n_draft_max, 0);

            // generate a new draft
            if (spec) common_speculative_get_draft_params(spec, seq_id) = {
                /* .drafting   = */ true,
                /* .n_max      = */ n_draft_max,
                /* .pos0       = */ n_past,
                /* .id_last    = */ id_last,
                /* .prompt     = */ &prompt_tgt,
                /* .result     = */ &draft, // output
            };
            if (spec) common_speculative_draft(spec);
            fresh_proposal = spec != nullptr;
            raw_proposed += draft.size();

            // save a checkpoint of the target context before evaluating the draft
            // this allows us to restore the state if partial draft acceptance occurs
            if (!draft.empty()) {
                if (use_ckpt_tgt) {
                    ckpt.update_tgt(ctx_tgt, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
                }
            }

            // reset the draft context to the checkpoint before verification
            if (ctx_dft) {
                if (use_ckpt_dft) {
                    ckpt.load_dft(ctx_dft, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
                }

                llama_memory_seq_rm(llama_get_memory(ctx_dft), seq_id, ckpt.pos_max + 1, -1);
            }
        } else {
            // we have a previous (partial) draft to reuse from checkpoint restoration
            if (use_ckpt_tgt) {
                GGML_ASSERT(!ckpt.empty());
            }
        }

        // always have a token to evaluate from before - id_last
        common_batch_clear(batch_tgt);
        common_batch_add  (batch_tgt, id_last, n_past++, { seq_id }, true);

        // evaluate the target model on [id_last, draft0, draft1, ..., draftN-1]
        {
            for (size_t i = 0; i < draft.size(); ++i) {
                common_batch_add(batch_tgt, draft[i], n_past + i, { seq_id }, true);
            }

            //LOG_DBG("target batch: %s\n", string_from(ctx_tgt, batch_tgt).c_str());

            verification_tokens += batch_tgt.n_tokens;
            if (llama_decode(ctx_tgt, batch_tgt) != 0) throw std::runtime_error("Target verification failed");
        }

        // feed the batch to the speculative implementation(s) - this drives the draft model, MTP, Eagle3, etc.
        if (spec && !common_speculative_process(spec, batch_tgt)) {
            throw std::runtime_error("Failed to process speculative verification batch");
        }

        // only save the sampler sampler state if we use checkpoints
        common_sampler_ptr smpl_save;
        if (use_ckpt_tgt) {
            smpl_save.reset(common_sampler_clone(smpl.get()));
        }

        // save the size of the draft being verified
        const size_t n_draft = draft.size();

        // sample from the full target batch and return the accepted tokens based on the target sampler
        //
        // for each token to be accepted, the sampler would have to sample that same token
        // in such cases, instead of decoding the sampled token as we normally do, we simply continue with the
        // available logits from the batch and sample the next token until we run out of logits or the sampler
        // disagrees with the draft
        //
        auto ids = common_sampler_sample_and_accept_n(smpl.get(), ctx_tgt, draft);
        if (ids.empty()) throw std::runtime_error("Target sampler returned an empty verification result");
        if (fresh_proposal) {
            raw_accepted_prefix += ids.size() - 1;
            proposal_rounds.push_back({{"proposed", n_draft}, {"first_accepted_prefix", ids.size() - 1}});
        }

        //LOG_DBG("ids: %s\n", string_from(ctx_tgt, ids).c_str());

        GGML_ASSERT(ids.size() > 0); // there will always be at least one accepted token

        // check for partial draft acceptance:
        // if the context doesn't support partial sequence removal, restore the checkpoint
        // and make the accepted tokens the new partial draft for the next iteration
        if (use_ckpt_tgt && ids.size() - 1 < n_draft) {
            ++rollback_rounds;
            LOG_DBG("partial acceptance: %zu < %zu, restoring checkpoint\n", ids.size() - 1, n_draft);

            draft = std::move(ids);

            {
                ckpt.load_tgt(ctx_tgt, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);

                llama_memory_seq_rm(llama_get_memory(ctx_tgt), seq_id, ckpt.pos_max + 1, -1);
            }

            if (ctx_dft) {
                ckpt.load_dft(ctx_dft, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);

                llama_memory_seq_rm(llama_get_memory(ctx_dft), seq_id, ckpt.pos_max + 1, -1);
            }

            prompt_tgt.resize(ckpt.n_tokens);
            smpl = std::move(smpl_save);

            n_past = (int) prompt_tgt.size();

            continue;
        }

        if (spec) common_speculative_accept(spec, seq_id, ids.size() - 1);

        // full acceptance: consume the draft and commit accepted tokens
        n_past    += ids.size() - 1;
        n_drafted += n_draft; // note: we ignore the discarded small drafts
        n_accept  += ids.size() - 1;
        n_predict += ids.size();

        // process the accepted tokens and update contexts
        //
        // this is the standard token post-processing that we normally do
        // in this case, we do it for a group of accepted tokens at once
        //
        for (size_t i = 0; i < ids.size(); ++i) {
            prompt_tgt.push_back(id_last);

            id_last = ids[i];

            if (llama_vocab_is_eog(vocab, id_last)) {
                has_eos = true;
                break;
            }

            const std::string token_str = common_token_to_piece(ctx_tgt, id_last);
            generated_text += token_str;
            ++emitted_tokens;

            if (params.use_color && i + 1 < ids.size()) {
                LOG("\u001b[%dm%s\u001b[37m", (36 - 0 % 6), token_str.c_str());
            } else {
                LOG("%s", token_str.c_str());
            }
        }

        LOG_DBG("accepted %d/%d draft tokens, the last target token is: (%d)\n", (int) ids.size() - 1, (int) draft.size(), id_last);

        // clear the draft since it has been consumed
        draft.clear();

        {
            LOG_DBG("clear kv cache from any extra tokens, n_past = %d\n", n_past);

            llama_memory_seq_rm(llama_get_memory(ctx_tgt), seq_id, n_past, -1);

            if (ctx_dft) {
                llama_memory_seq_rm(llama_get_memory(ctx_dft), seq_id, n_past, -1);
            }
        }

        if ((params.n_predict >= 0 && n_predict >= params.n_predict) || has_eos) {
            break;
        }
    }

    llama_synchronize(ctx_tgt);
    if (ctx_dft) llama_synchronize(ctx_dft);
    auto t_dec_end = ggml_time_us();
    if (const char * metrics_path = std::getenv("ANTISOCIAL_SPEC_METRICS_PATH")) {
        const auto report = nlohmann::ordered_json{
            {"scope", "Initial native speculative viability; not a complete Context job or idle-device speed qualification"},
            {"target", params.model.path}, {"draft", params.speculative.draft.mparams.path},
            {"context", llama_n_ctx(ctx_tgt)}, {"input_tokens", inp.size()}, {"generated_tokens", n_predict},
            {"target_batch", llama_n_batch(ctx_tgt)}, {"target_ubatch", llama_n_ubatch(ctx_tgt)},
            {"emitted_tokens", emitted_tokens}, {"text", generated_text},
            {"requested_proposal_depth", params_spec.draft.n_max},
            {"rollback_recipe", rollback}, {"target_recurrent_rollback_slots", llama_n_rs_seq(ctx_tgt)},
            {"target_buffers", buffers(ctx_tgt)}, {"draft_buffers", buffers(ctx_dft)},
            {"buffer_accounting_limit", "Target/draft borrowed weights can overlap; breakdown excludes some host allocations and is not a unique-physical-memory sum. External process observation remains separate."},
            {"requested_target_cache_types", {int(params.cache_type_k), int(params.cache_type_v)}},
            {"raw_proposed_tokens", raw_proposed}, {"first_accepted_prefix_tokens", raw_accepted_prefix},
            {"first_acceptance", raw_proposed ? nlohmann::ordered_json(double(raw_accepted_prefix)/raw_proposed) : nlohmann::ordered_json(nullptr)},
            {"verification_forward_tokens", verification_tokens}, {"verification_rounds", verification_rounds},
            {"rollback_rounds", rollback_rounds}, {"proposal_rounds", proposal_rounds},
            {"target_position_recipe", antisocial_ai::resolved_position_recipe(ctx_tgt, model_tgt)},
            {"draft_position_recipe", ctx_dft ? antisocial_ai::resolved_position_recipe(ctx_dft, llama_get_model(ctx_dft)) : nlohmann::ordered_json(nullptr)},
            {"cold_to_generation_complete_ms", double(t_dec_end-wall_start)/1000},
            {"prefill_ms", double(t_enc_end-t_enc_start)/1000}, {"decode_including_replay_ms", double(t_dec_end-t_dec_start)/1000}};
        std::ofstream metrics(metrics_path); metrics << report.dump(2) << std::endl;
        if (!metrics) throw std::runtime_error("Unable to retain speculative evidence");
    }

    const int n_input = inp.size();

    LOG("\n\n");

    LOG_INF("encoded %4d tokens in %8.3f seconds, speed: %8.3f t/s\n", n_input,   (t_enc_end - t_enc_start) / 1e6f, inp.size() / ((t_enc_end - t_enc_start) / 1e6f));
    LOG_INF("decoded %4d tokens in %8.3f seconds, speed: %8.3f t/s\n", n_predict, (t_dec_end - t_dec_start) / 1e6f, n_predict  / ((t_dec_end - t_dec_start) / 1e6f));

    LOG_INF("\n");
    LOG_INF("n_draft   = %d\n", params_spec.draft.n_max);
    LOG_INF("n_predict = %d\n", n_predict);
    LOG_INF("n_drafted = %d\n", n_drafted);
    LOG_INF("n_accept  = %d\n", n_accept);
    LOG_INF("committed-round diagnostic only: accept = %.3f%%\n", n_drafted ? 100.0f * n_accept / n_drafted : 0.f);

    LOG_INF("\n");
    LOG_INF("draft:\n\n");
    if (spec) common_speculative_print_stats(spec);

    LOG_INF("\n");
    LOG_INF("target:\n\n");
    common_perf_print(ctx_tgt, smpl.get());

    LOG("\n\n");

    return 0;
} catch (const std::exception & error) {
    std::fprintf(stderr, "Speculative probe failed: %s\n", error.what());
    return 1;
}
