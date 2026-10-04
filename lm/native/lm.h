// SPDX-License-Identifier: GPL-3.0-only
// Caret's typing language model: a small Llama run by llama.cpp. Given the text so far it says how
// likely each of a few candidate words is to come next, and which words are likeliest.
#pragma once
#include <string>
#include <utility>
#include <vector>

struct CaretLm;

// Loads a GGUF model. Returns null when the file cannot be loaded.
CaretLm *caret_lm_load(const char *path, int threads);
void caret_lm_free(CaretLm *lm);

// The log probability of each word as the next word after `context`. Empty when the context could
// not be evaluated. The context is remembered, so asking again after it grew only reads the new part.
std::vector<float> caret_lm_score(CaretLm *lm, const std::string &context, const std::vector<std::string> &words);

// The likeliest next words after `context` that are a single token, best first, with their log probability.
std::vector<std::pair<std::string, float>> caret_lm_predict(CaretLm *lm, const std::string &context, int count);
