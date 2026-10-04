// SPDX-License-Identifier: GPL-3.0-only
#include "lm.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <unordered_map>

#include "llama.h"

namespace {
const int MAX_CONTEXT_TOKENS = 96;  // a keyboard needs the sentence at hand, not the page
const int MAX_WORD_TOKENS = 12;     // a candidate word in more pieces than this is not a word
const int CANDIDATE_SEQUENCES = 7;  // candidates tried behind the context in one run
const int CONTEXT_SIZE = MAX_CONTEXT_TOKENS + CANDIDATE_SEQUENCES * MAX_WORD_TOKENS;
const char *SPACE_MARK = "\xE2\x96\x81"; // U+2581, what SentencePiece writes for a space
}

struct CaretLm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int vocabSize = 0;
    std::vector<llama_token> cached;  // the context tokens held in sequence 0 of the memory
    std::vector<float> next;          // log probabilities of the token after them
    std::unordered_map<std::string, float> scored;  // words already scored after this context
};

static void log_softmax(const float *logits, int n, std::vector<float> &out) {
    float top = logits[0];
    for (int i = 1; i < n; i++) top = std::max(top, logits[i]);
    double sum = 0;
    for (int i = 0; i < n; i++) sum += std::exp(logits[i] - top);
    const float shift = top + static_cast<float>(std::log(sum));
    out.resize(n);
    for (int i = 0; i < n; i++) out[i] = logits[i] - shift;
}

static std::vector<llama_token> tokenize(const CaretLm *lm, const std::string &text, bool special) {
    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(lm->vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), special, false);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(lm->vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), special, false);
    }
    tokens.resize(std::max(n, 0));
    return tokens;
}

// Runs `tokens` through the model as sequence `seq` starting at position `from`, asking for the
// prediction after every one of them when `all` is set and only after the last one otherwise.
static bool decode(CaretLm *lm, const llama_token *tokens, int count, int from, llama_seq_id seq, bool all) {
    llama_batch batch = llama_batch_init(count, 0, 1);
    for (int i = 0; i < count; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = from + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = seq;
        batch.logits[i] = all || i == count - 1;
    }
    batch.n_tokens = count;
    const bool ok = llama_decode(lm->ctx, batch) == 0;
    llama_batch_free(batch);
    return ok;
}

static bool evaluate(CaretLm *lm, const std::string &text) {
    // a space at the end would become a token of its own, and the next word brings its own space
    std::string context = text;
    while (!context.empty() && std::isspace(static_cast<unsigned char>(context.back()))) context.pop_back();
    std::vector<llama_token> tokens = tokenize(lm, context, true);  // starts with BOS
    if (tokens.empty()) return false;
    if (static_cast<int>(tokens.size()) > MAX_CONTEXT_TOKENS) {
        // keep BOS and the end: what was said last matters most
        tokens.erase(tokens.begin() + 1, tokens.end() - (MAX_CONTEXT_TOKENS - 1));
    }
    size_t common = 0;
    while (common < tokens.size() && common < lm->cached.size() && tokens[common] == lm->cached[common]) common++;
    if (common == tokens.size() && common == lm->cached.size() && !lm->next.empty()) return true;
    if (common == tokens.size()) common--;  // the prediction after the last token has to be made again
    llama_memory_t memory = llama_get_memory(lm->ctx);
    llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1);
    lm->cached.resize(common);
    lm->next.clear();
    lm->scored.clear();
    if (!decode(lm, tokens.data() + common, static_cast<int>(tokens.size() - common), static_cast<int>(common), 0, false)) {
        llama_memory_clear(memory, true);
        lm->cached.clear();
        return false;
    }
    log_softmax(llama_get_logits_ith(lm->ctx, -1), lm->vocabSize, lm->next);
    lm->cached = tokens;
    return true;
}

CaretLm *caret_lm_load(const char *path, int threads) {
    static bool started = false;
    if (!started) {
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
        started = true;
    }
    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, modelParams);
    if (!model) return nullptr;
    llama_context_params params = llama_context_default_params();
    params.n_ctx = CONTEXT_SIZE;
    params.n_batch = CONTEXT_SIZE;
    params.n_seq_max = CANDIDATE_SEQUENCES + 1;  // 0 holds the context, the others each try a candidate behind it
    params.kv_unified = true;  // the candidates share the context instead of each getting a copy
    params.n_threads = threads;
    params.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, params);
    if (!ctx) {
        llama_model_free(model);
        return nullptr;
    }
    auto *lm = new CaretLm();
    lm->model = model;
    lm->ctx = ctx;
    lm->vocab = llama_model_get_vocab(model);
    lm->vocabSize = llama_vocab_n_tokens(lm->vocab);
    return lm;
}

void caret_lm_free(CaretLm *lm) {
    if (!lm) return;
    llama_free(lm->ctx);
    llama_model_free(lm->model);
    delete lm;
}

std::vector<float> caret_lm_score(CaretLm *lm, const std::string &context, const std::vector<std::string> &words) {
    std::vector<float> scores;
    if (!lm || !evaluate(lm, context)) return scores;
    llama_memory_t memory = llama_get_memory(lm->ctx);
    const int at = static_cast<int>(lm->cached.size());
    scores.assign(words.size(), -100.0f);

    // words of one token are read off the prediction already made; the longer ones each continue the
    // context in a sequence of their own, all of them in one run of the model
    struct Pending { size_t word; std::vector<llama_token> tokens; };
    std::vector<Pending> pending;
    for (size_t w = 0; w < words.size(); w++) {
        const auto known = lm->scored.find(words[w]);
        if (known != lm->scored.end()) {
            scores[w] = known->second;
            continue;
        }
        // the tokenizer puts the space in front of the word itself
        std::vector<llama_token> tokens = tokenize(lm, words[w], false);
        if (tokens.empty() || static_cast<int>(tokens.size()) > MAX_WORD_TOKENS) continue;
        if (tokens.size() == 1) {
            scores[w] = lm->scored[words[w]] = lm->next[tokens[0]];
        } else {
            pending.push_back({w, std::move(tokens)});
        }
    }

    std::vector<float> step;
    for (size_t first = 0; first < pending.size(); first += CANDIDATE_SEQUENCES) {
        const size_t last = std::min(pending.size(), first + CANDIDATE_SEQUENCES);
        int count = 0;
        for (size_t k = first; k < last; k++) count += static_cast<int>(pending[k].tokens.size()) - 1;
        llama_batch batch = llama_batch_init(count, 0, 1);
        int n = 0;
        for (size_t k = first; k < last; k++) {
            const llama_seq_id seq = static_cast<llama_seq_id>(k - first + 1);
            llama_memory_seq_cp(memory, 0, seq, -1, -1);
            for (size_t i = 0; i + 1 < pending[k].tokens.size(); i++, n++) {
                batch.token[n] = pending[k].tokens[i];
                batch.pos[n] = at + static_cast<int>(i);
                batch.n_seq_id[n] = 1;
                batch.seq_id[n][0] = seq;
                batch.logits[n] = true;
            }
        }
        batch.n_tokens = n;
        const bool ok = llama_decode(lm->ctx, batch) == 0;
        llama_batch_free(batch);
        n = 0;
        for (size_t k = first; k < last; k++) {
            const std::vector<llama_token> &tokens = pending[k].tokens;
            if (ok) {
                float score = lm->next[tokens[0]];
                for (size_t i = 0; i + 1 < tokens.size(); i++, n++) {
                    log_softmax(llama_get_logits_ith(lm->ctx, n), lm->vocabSize, step);
                    score += step[tokens[i + 1]];
                }
                scores[pending[k].word] = lm->scored[words[pending[k].word]] = score;
            }
            llama_memory_seq_rm(memory, static_cast<llama_seq_id>(k - first + 1), -1, -1);
        }
    }
    return scores;
}

std::vector<std::pair<std::string, float>> caret_lm_predict(CaretLm *lm, const std::string &context, int count) {
    std::vector<std::pair<std::string, float>> words;
    if (!lm || count <= 0 || !evaluate(lm, context)) return words;
    std::vector<int> order(lm->vocabSize);
    for (int i = 0; i < lm->vocabSize; i++) order[i] = i;
    const int look = std::min(lm->vocabSize, count * 8);
    std::partial_sort(order.begin(), order.begin() + look, order.end(),
                      [&](int a, int b) { return lm->next[a] > lm->next[b]; });
    const size_t mark = std::strlen(SPACE_MARK);
    for (int i = 0; i < look && static_cast<int>(words.size()) < count; i++) {
        const char *piece = llama_vocab_get_text(lm->vocab, order[i]);
        if (!piece || std::strncmp(piece, SPACE_MARK, mark) != 0) continue;  // a whole word starts with a space
        std::string word(piece + mark);
        if (word.empty()) continue;
        bool letters = true;
        for (unsigned char c : word) letters = letters && (std::isalpha(c) || c == '\'' || c >= 0x80);
        if (letters) words.emplace_back(word, lm->next[order[i]]);
    }
    return words;
}
