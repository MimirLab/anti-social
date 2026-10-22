#pragma once
#include <cstdint>
#include <string>

namespace antisocial_ai {
std::string capabilities();
int64_t open(const std::string & path, const std::string & backend, int context, int threads, const std::string & cache_profile = "f16_reference", const std::string & position_profile = "native");
// A nonzero cache budget is reserved for app-allowlisted compiled instructions.
std::string generate(int64_t handle, const std::string & system, const std::string & user, int max_tokens, uint32_t seed, bool json_mode = false, int instruction_cache_bytes = 0, const std::string & sampling_profile = "prism_non_thinking_reference_v1");
int count_tokens(int64_t handle, const std::string & system, const std::string & user);
std::string diagnostics(int64_t handle);
void cancel(int64_t handle);
void reset(int64_t handle);
void close(int64_t handle);
}
