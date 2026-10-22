// Compare the actual packed SIMD kernel with QVAC's independent scalar decoder.
#include "llama.h"
#include "ggml-cpu/quants.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

int main() {
    llama_backend_init();
    std::mt19937 random(0x4c494245);
    std::uniform_int_distribution<int> byte(0, 255), signed_byte(-128, 127);
    std::uniform_real_distribution<float> scale(-3.0f, 3.0f);
    float maximum_error = 0;
    for (int sample = 0; sample < 5000; ++sample) {
        const int blocks = 1 + static_cast<int>(random() % 64);
        std::vector<block_pq2_0> x(blocks);
        std::vector<block_q8_0> y(blocks * 4);
        for (auto & row : x) {
            row.d = ggml_fp32_to_fp16(scale(random));
            for (auto & q : row.qs) q = static_cast<uint8_t>(byte(random));
        }
        for (auto & row : y) {
            row.d = ggml_fp32_to_fp16(scale(random));
            for (auto & q : row.qs) q = static_cast<int8_t>(signed_byte(random));
        }
        float reference = 0, actual = 0;
        ggml_vec_dot_pq2_0_q8_0_generic(blocks * QK_PQ2_0, &reference, 0, x.data(), 0, y.data(), 0, 1);
        ggml_vec_dot_pq2_0_q8_0(blocks * QK_PQ2_0, &actual, 0, x.data(), 0, y.data(), 0, 1);
        maximum_error = std::max(maximum_error, std::abs(reference - actual));
        if (!std::isfinite(actual) || reference != actual) {
            std::cerr << "Packed-kernel parity failed at sample " << sample << ": " << reference << " vs " << actual << '\n';
            return 1;
        }
    }
    std::cout << "{\"cases\":5000,\"maximum_absolute_error\":" << maximum_error << ",\"passed\":true}\n";
    return 0;
}
