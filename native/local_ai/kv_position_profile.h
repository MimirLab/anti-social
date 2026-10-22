#pragma once
// Shared controls for isolated quality experiments; Android callers use native.
#include "llama.h"
#include "llama-context.h"
#include "llama-model.h"
#include "nlohmann/json.hpp"
#include <cmath>
#include <stdexcept>
#include <string>

namespace antisocial_ai {
inline void configure_position_profile(llama_context_params & cp, const llama_model * model, const std::string & profile) {
    if (profile == "native") return;
    if (profile != "yarn2") throw std::runtime_error("Unknown positional profile");
    const float scale = llama_model_rope_freq_scale_train(model);
    const int32_t window = llama_model_n_ctx_train(model);
    if (!std::isfinite(scale) || scale <= 0 || window <= 0) throw std::runtime_error("Invalid model positional metadata");
    cp.rope_scaling_type = LLAMA_ROPE_SCALING_TYPE_YARN;
    cp.rope_freq_scale = scale * 0.5f;
    // A model may already extend a smaller native window with YaRN. Preserve
    // that anchor when testing another factor; the advertised window is not
    // necessarily its original training window (e.g. the 9B DFlash2 drafter).
    cp.yarn_orig_ctx = model->hparams.n_ctx_orig_yarn > 0 ? model->hparams.n_ctx_orig_yarn : window;
    cp.yarn_ext_factor = 1.f;
}

inline nlohmann::ordered_json resolved_position_recipe(const llama_context * ctx, const llama_model * model) {
    const auto & cp = ctx->get_cparams(); const auto & hp = model->hparams;
    return {{"type", int(cp.rope_scaling_type)}, {"base", cp.rope_freq_base},
        {"frequency_scale", cp.rope_freq_scale}, {"original_context", cp.n_ctx_orig_yarn},
        {"extension_factor", cp.yarn_ext_factor}, {"attention_factor", cp.yarn_attn_factor},
        {"beta_fast", cp.yarn_beta_fast}, {"beta_slow", cp.yarn_beta_slow},
        {"rope_type", int(hp.rope_type)}, {"sections", hp.rope_sections}, {"rotary_dimensions", hp.n_rot()}};
}
}
