#pragma once
#include <android/log.h>

#define TFX_LOG_TAG "TarangFXNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TFX_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TFX_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TFX_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TFX_LOG_TAG, __VA_ARGS__)
