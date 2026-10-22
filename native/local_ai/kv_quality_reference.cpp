// JSON-lines fixture runner through the production template/sampler/constraints.
#include "text_runtime.h"
#include "nlohmann/json.hpp"
#include <iostream>
#include <stdexcept>
using json = nlohmann::ordered_json;
int main(int argc, char ** argv) {
    int64_t handle = 0;
    try {
        if (argc != 6 && argc != 7) throw std::runtime_error("model backend cache_profile context position_profile [threads]");
        handle = antisocial_ai::open(argv[1], argv[2], std::stoi(argv[4]), argc == 7 ? std::stoi(argv[6]) : 4, argv[3], argv[5]);
        for (std::string line; std::getline(std::cin, line);) {
            json response;
            try {
                const auto request = json::parse(line);
                const auto op = request.at("op").get<std::string>();
                const auto system = request.value("system", std::string{}), user = request.value("user", std::string{});
                if (op == "metadata") {
                    response = json::parse(antisocial_ai::capabilities());
                    // The active handle, not the advertised model maximum,
                    // determines the real ContextEngine planning window.
                    response.update(json::parse(antisocial_ai::diagnostics(handle)));
                }
                else if (op == "count") response = {{"count", antisocial_ai::count_tokens(handle, system, user)}};
                else if (op == "generate") {
                    const auto result = antisocial_ai::generate(handle, system, user, request.value("maxTokens", 128), 42, true, request.value("instructionCacheBytes", 0), request.value("samplingProfile", std::string("prism_non_thinking_reference_v1")));
                    response = {{"text", result}, {"diagnostics", json::parse(antisocial_ai::diagnostics(handle))}};
                } else throw std::runtime_error("Unknown fixture operation");
            } catch (const std::exception & error) { response = {{"error", error.what()}}; }
            std::cout << response.dump() << std::endl;
        }
        antisocial_ai::close(handle); return 0;
    } catch (const std::exception & error) {
        if (handle) antisocial_ai::close(handle);
        std::cerr << error.what() << '\n'; return 1;
    }
}
