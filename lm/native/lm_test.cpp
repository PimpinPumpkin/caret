// SPDX-License-Identifier: GPL-3.0-only
// lm_test MODEL "context" word... : prints each word's score after the context, and the likeliest next words
// lm_test MODEL - : the same for many, lines of "context<TAB>word word ..." on standard input
#include <chrono>
#include <cstdio>
#include <string>
#include "lm.h"

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: lm_test model.gguf \"context\" [word ...]\n"); return 2; }
    CaretLm *lm = caret_lm_load(argv[1], 2);
    if (!lm) { std::fprintf(stderr, "cannot load %s\n", argv[1]); return 1; }
    if (std::string(argv[2]) == "-") {
        // many at once: lines of "context<TAB>word word ..." in, a line of scores out for each
        char line[8192];
        while (std::fgets(line, sizeof line, stdin)) {
            std::string text(line);
            while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
            const size_t tab = text.find('\t');
            if (tab == std::string::npos) { std::printf("\n"); continue; }
            std::vector<std::string> candidates;
            size_t at = tab + 1;
            while (at < text.size()) {
                size_t end = text.find(' ', at);
                if (end == std::string::npos) end = text.size();
                if (end > at) candidates.push_back(text.substr(at, end - at));
                at = end + 1;
            }
            const std::vector<float> out = caret_lm_score(lm, text.substr(0, tab), candidates);
            for (size_t i = 0; i < out.size(); i++) std::printf(i ? " %.3f" : "%.3f", out[i]);
            std::printf("\n");
            std::fflush(stdout);
        }
        caret_lm_free(lm);
        return 0;
    }
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
