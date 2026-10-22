#include "text_runtime.h"
#include <iostream>
#include <stdexcept>
#include <thread>
#include <chrono>
#include <atomic>
#include "nlohmann/json.hpp"

static int self_test(const std::string & path, const std::string & profile = "f16_reference", const std::string & backend = "cpu") {
    using nlohmann::ordered_json;
    auto handle = antisocial_ai::open(path, backend, 2048, 4, profile);
    ordered_json report = {{"runtime", ordered_json::parse(antisocial_ai::capabilities())}, {"cases", ordered_json::array()}};
    struct Case { const char * name; const char * source; const char * expected; };
    const Case cases[] = {
        {"corrected_number", "Source A originally reported 19 closures. Its correction says 23 closures, replacing 19. Return only the corrected number.", "23"},
        {"negation", "Source: The council did not approve the bridge proposal. Was it approved? Answer with only YES or NO.", "NO"},
        {"attribution", "Asha denied the allegation. Bruno supported the allegation. Who denied it? Return only the person's name.", "Asha"},
        {"unicode", "Source: The café opened in Montréal. Return only the city name, preserving its spelling.", "Montréal"}
    };
    bool passed = true;
    for (const auto & item : cases) {
        auto output = antisocial_ai::generate(handle, "Answer directly from the supplied source. Do not add information.", item.source, 48, 42);
        bool ok = output.find(item.expected) != std::string::npos && output.find("<think>") == std::string::npos;
        passed = passed && ok;
        report["cases"].push_back({{"name", item.name}, {"output", output}, {"expected", item.expected}, {"passed", ok}, {"diagnostics", ordered_json::parse(antisocial_ai::diagnostics(handle))}});
    }
    bool rejected = false;
    try { antisocial_ai::generate(handle, "", std::string(40000, 'q'), 48, 42); } catch (const std::exception &) { rejected = true; }
    report["bounded_context_rejected"] = rejected;
    passed = passed && rejected;
    std::atomic<bool> was_cancelled{false};
    std::thread worker([&] {
        try { antisocial_ai::generate(handle, "Repeat the supplied facts accurately.", std::string(2000, 'a'), 512, 42); }
        catch (const std::exception & error) { was_cancelled = std::string(error.what()).find("cancelled") != std::string::npos; }
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    auto cancellation_started = std::chrono::steady_clock::now();
    antisocial_ai::cancel(handle);
    worker.join();
    report["cancelled"] = was_cancelled.load();
    report["cancel_ms"] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - cancellation_started).count();
    passed = passed && was_cancelled.load();
    antisocial_ai::reset(handle);
    auto after_reset = antisocial_ai::generate(handle, "Follow the instruction directly.", "Return only the word READY.", 16, 42);
    report["reset_output"] = after_reset;
    passed = passed && after_reset.find("READY") != std::string::npos;
    antisocial_ai::close(handle);
    antisocial_ai::close(handle);
    bool closed = false;
    try { antisocial_ai::diagnostics(handle); } catch (const std::exception &) { closed = true; }
    report["closed_handle_rejected"] = closed;
    report["passed"] = passed && closed;
    std::cout << report.dump(2) << '\n';
    return passed && closed ? 0 : 2;
}

static int json_self_test(const std::string & path) {
    using nlohmann::ordered_json;
    auto handle = antisocial_ai::open(path, "cpu", 2048, 2);
    ordered_json report = {{"runtime", ordered_json::parse(antisocial_ai::capabilities())}};
    bool passed = true;
    const auto structured = antisocial_ai::generate(handle,
        "Use the supplied facts only. Return a JSON object without prose or markdown.",
        "Source: The correction replaces 19 closures with 23 closures. Return an object with a corrected_number integer and a quote string copied from this source.",
        128, 42, true);
    const auto parsed = ordered_json::parse(structured);
    const auto structured_diagnostics = ordered_json::parse(antisocial_ai::diagnostics(handle));
    const bool object = parsed.is_object() && structured_diagnostics["output_constraint"] == "json_object";
    report["structured"] = {{"output", structured}, {"object", object}, {"diagnostics", structured_diagnostics}};
    passed = passed && object;

    // The grammar permits complete objects only; the output budget can still
    // stop generation in the middle of a valid prefix. It must fail explicitly.
    bool incomplete_rejected = false;
    std::string bounded_output;
    try {
        bounded_output = antisocial_ai::generate(handle,
            "Return the requested JSON object exactly.",
            "Return {\"message\":\"A deliberately long sentence that cannot fit in a single output token.\"}.",
            1, 42, true);
    } catch (const std::exception & error) {
        incomplete_rejected = std::string(error.what()).find("did not finish a JSON object") != std::string::npos;
    }
    report["incomplete_object_rejected"] = incomplete_rejected;
    if (!bounded_output.empty()) report["one_token_output"] = bounded_output;
    passed = passed && incomplete_rejected;

    std::atomic<bool> was_cancelled{false};
    std::thread worker([&] {
        try {
            antisocial_ai::generate(handle, "Return a JSON object containing the supplied text.", std::string(2000, 'a'), 512, 42, true);
        } catch (const std::exception & error) {
            was_cancelled = std::string(error.what()).find("cancelled") != std::string::npos;
        }
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    const auto started = std::chrono::steady_clock::now();
    antisocial_ai::cancel(handle);
    worker.join();
    report["json_cancelled"] = was_cancelled.load();
    report["cancel_ms"] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    passed = passed && was_cancelled.load();
    antisocial_ai::reset(handle);

    const auto freeform = antisocial_ai::generate(handle, "Follow the instruction directly.", "Return only the word READY.", 16, 42);
    const auto freeform_diagnostics = ordered_json::parse(antisocial_ai::diagnostics(handle));
    const bool freeform_ok = freeform.find("READY") != std::string::npos && freeform_diagnostics["output_constraint"] == "freeform";
    report["freeform_after_reset"] = {{"output", freeform}, {"passed", freeform_ok}, {"diagnostics", freeform_diagnostics}};
    passed = passed && freeform_ok;
    const auto resumed = antisocial_ai::generate(handle, "Return a JSON object without prose.", "Return an object with ready set to true.", 32, 42, true);
    const bool resumed_ok = ordered_json::parse(resumed).is_object();
    report["json_after_freeform"] = {{"output", resumed}, {"object", resumed_ok}};
    passed = passed && resumed_ok;
    antisocial_ai::close(handle);
    report["passed"] = passed;
    std::cout << report.dump(2) << '\n';
    return passed ? 0 : 2;
}

int main(int argc, char ** argv) {
    try {
        if (argc < 2) { std::cout << antisocial_ai::capabilities() << '\n'; return 0; }
        if (std::string(argv[1]) == "--self-test") {
            if (argc < 3 || argc > 5) throw std::runtime_error("--self-test requires model [cache profile] [backend]");
            return self_test(argv[2], argc > 3 ? argv[3] : "f16_reference", argc > 4 ? argv[4] : "cpu");
        }
        if (std::string(argv[1]) == "--json-self-test") {
            if (argc != 3) throw std::runtime_error("--json-self-test requires the verified model path");
            return json_self_test(argv[2]);
        }
        const std::string backend = argc > 2 ? argv[2] : "cpu";
        int context = argc > 4 ? std::stoi(argv[4]) : 2048;
        auto handle = antisocial_ai::open(argv[1], backend, context, 4, argc > 5 ? argv[5] : "f16_reference");
        std::string prompt = argc > 3 ? argv[3] : "In one sentence, explain what an accountless social reader does.";
        std::cout << antisocial_ai::generate(handle, "You are a concise, faithful assistant. Answer directly using only the supplied facts.", prompt, 96, 42) << '\n';
        std::cout << antisocial_ai::diagnostics(handle) << '\n';
        antisocial_ai::close(handle);
        return 0;
    } catch (const std::exception & e) { std::cerr << e.what() << '\n'; return 1; }
}
