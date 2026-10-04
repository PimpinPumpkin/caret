// SPDX-License-Identifier: GPL-3.0-only
// lm_test MODEL "context" word... : prints each word's score after the context, and the likeliest next words
#include <chrono>
#include <cstdio>
#include "lm.h"

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: lm_test model.gguf \"context\" [word ...]\n"); return 2; }
    CaretLm *lm = caret_lm_load(argv[1], 2);
    if (!lm) { std::fprintf(stderr, "cannot load %s\n", argv[1]); return 1; }
    std::vector<std::string> words(argv + 3, argv + argc);
    auto start = std::chrono::steady_clock::now();
    std::vector<float> scores = caret_lm_score(lm, argv[2], words);
    auto first = std::chrono::steady_clock::now();
    caret_lm_score(lm, argv[2], words);
    auto second = std::chrono::steady_clock::now();
    for (size_t i = 0; i < scores.size(); i++) std::printf("%-12s %.2f\n", words[i].c_str(), scores[i]);
    std::printf("next:");
    for (auto &w : caret_lm_predict(lm, argv[2], 6)) std::printf(" %s (%.1f)", w.first.c_str(), w.second);
    auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    std::printf("\nfirst %.1f ms, again %.1f ms\n", ms(start, first), ms(first, second));
    caret_lm_free(lm);
    return 0;
}
