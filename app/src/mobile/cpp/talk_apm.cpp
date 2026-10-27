// SPDX-License-Identifier: BSD-3-Clause
// Aliflix's small JNI boundary; DSP is the unmodified WebRTC APM extraction.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <new>
#include "api/audio/audio_processing.h"

namespace {
struct Processor {
  rtc::scoped_refptr<webrtc::AudioProcessing> apm;
  std::array<float, 240> render{};
  std::array<int16_t, 160> capture{};
  Processor() {
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false; // AEC3, including desktop-quality double talk.
    config.noise_suppression.enabled = false;
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;
    apm = webrtc::AudioProcessingBuilder().SetConfig(config).Create();
  }
};
Processor* get(jlong handle) { return reinterpret_cast<Processor*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aliflix_app_talk_TalkApmNative_create(JNIEnv*, jobject) {
  auto* p = new (std::nothrow) Processor();
  if (!p || !p->apm || p->apm->Initialize() != 0) { delete p; return 0; }
  return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_aliflix_app_talk_TalkApmNative_destroy(JNIEnv*, jobject, jlong h) { delete get(h); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aliflix_app_talk_TalkApmNative_reset(JNIEnv*, jobject, jlong h) {
  return h && get(h)->apm->Initialize() == 0;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aliflix_app_talk_TalkApmNative_render(JNIEnv* env, jobject, jlong h, jshortArray pcm) {
  if (!h || env->GetArrayLength(pcm) != 240) return false;
  std::array<jshort, 240> input{};
  env->GetShortArrayRegion(pcm, 0, 240, input.data());
  if (env->ExceptionCheck()) return false;
  auto* p = get(h);
  for (size_t i = 0; i < input.size(); ++i) p->render[i] = input[i] / 32768.f;
  const float* src[] = {p->render.data()};
  float* dst[] = {p->render.data()};
  const webrtc::StreamConfig stream(24000, 1);
  return p->apm->ProcessReverseStream(src, stream, stream, dst) == 0;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aliflix_app_talk_TalkApmNative_capture(JNIEnv* env, jobject, jlong h, jshortArray pcm, jint delay) {
  if (!h || env->GetArrayLength(pcm) != 160) return false;
  auto* p = get(h);
  env->GetShortArrayRegion(pcm, 0, 160, p->capture.data());
  if (env->ExceptionCheck()) return false;
  p->apm->set_stream_delay_ms(std::clamp(static_cast<int>(delay), 0, 500));
  const webrtc::StreamConfig stream(16000, 1);
  int result = p->apm->ProcessStream(p->capture.data(), stream, stream, p->capture.data());
  if (result == 0) env->SetShortArrayRegion(pcm, 0, 160, p->capture.data());
  return result == 0 && !env->ExceptionCheck();
}
