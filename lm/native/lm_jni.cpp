// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <string>
#include <vector>

#include "lm.h"

static std::string str(JNIEnv *env, jstring value) {
    if (!value) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_caret_latin_lm_TypingModel_nativeLoad(JNIEnv *env, jclass, jstring path, jint threads) {
    return reinterpret_cast<jlong>(caret_lm_load(str(env, path).c_str(), threads));
}

JNIEXPORT void JNICALL
Java_app_caret_latin_lm_TypingModel_nativeFree(JNIEnv *, jclass, jlong handle) {
    caret_lm_free(reinterpret_cast<CaretLm *>(handle));
}

JNIEXPORT jfloatArray JNICALL
Java_app_caret_latin_lm_TypingModel_nativeScore(JNIEnv *env, jclass, jlong handle, jstring context, jobjectArray words) {
    std::vector<std::string> list;
    const jsize count = env->GetArrayLength(words);
    for (jsize i = 0; i < count; i++) {
        auto word = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        list.push_back(str(env, word));
        env->DeleteLocalRef(word);
    }
    const std::vector<float> scores = caret_lm_score(reinterpret_cast<CaretLm *>(handle), str(env, context), list);
    if (scores.empty()) return nullptr;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(scores.size()));
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(scores.size()), scores.data());
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_app_caret_latin_lm_TypingModel_nativePredict(JNIEnv *env, jclass, jlong handle, jstring context, jint count) {
    const auto words = caret_lm_predict(reinterpret_cast<CaretLm *>(handle), str(env, context), count);
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(words.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < words.size(); i++) {
        jstring word = env->NewStringUTF(words[i].first.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), word);
        env->DeleteLocalRef(word);
    }
    return out;
}

}
