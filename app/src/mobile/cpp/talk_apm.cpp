// SPDX-License-Identifier: BSD-3-Clause
// Aliflix's small JNI boundary; DSP is the unmodified WebRTC APM extraction.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <new>
#include "api/audio/audio_processing.h"
#include "api/audio/echo_control.h"
#include "api/audio/echo_canceller3_config.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"

namespace {
class EchoFactory final : public webrtc::EchoControlFactory {
 public:
  std::unique_ptr<webrtc::EchoControl> Create(int rate, int render, int capture) override {
    webrtc::EchoCanceller3Config config;
    // We supply Android's measured playout + capture delay every 10 ms.
    // The default AEC3 configuration ignores that external delay estimate.
    config.delay.use_external_delay_estimator = true;
    return std::make_unique<webrtc::EchoCanceller3>(config, std::nullopt, rate, render, capture);
  }
};
struct Processor {
  rtc::scoped_refptr<webrtc::AudioProcessing> apm;
  std::array<float, 240> render{};
  std::array<int16_t, 160> capture{};
  std::array<float, 160> capture_float{};
  Processor() {
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false; // AEC3, including desktop-quality double talk.
    config.noise_suppression.enabled = false;
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;
    apm = webrtc::AudioProcessingBuilder().SetConfig(config).SetEchoControlFactory(std::make_unique<EchoFactory>()).Create();
  }
  int initialize() {
    webrtc::ProcessingConfig config;
    config.input_stream() = webrtc::StreamConfig(16000, 1);
    config.output_stream() = webrtc::StreamConfig(16000, 1);
    config.reverse_input_stream() = webrtc::StreamConfig(24000, 1);
    config.reverse_output_stream() = webrtc::StreamConfig(24000, 1);
    return apm->Initialize(config);
  }
};
Processor* get(jlong handle) { return reinterpret_cast<Processor*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aliflix_app_talk_TalkApmNative_create(JNIEnv*, jobject) {
  auto* p = new (std::nothrow) Processor();
  if (!p || !p->apm || p->initialize() != 0) { delete p; return 0; }
  return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_aliflix_app_talk_TalkApmNative_destroy(JNIEnv*, jobject, jlong h) { delete get(h); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aliflix_app_talk_TalkApmNative_reset(JNIEnv*, jobject, jlong h) {
  return h && get(h)->initialize() == 0;
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
  for (size_t i = 0; i < p->capture.size(); ++i) p->capture_float[i] = p->capture[i] / 32768.f;
  const float* src[] = {p->capture_float.data()};
  float* dst[] = {p->capture_float.data()};
  int result = p->apm->ProcessStream(src, stream, stream, dst);
  for (size_t i = 0; i < p->capture.size(); ++i) p->capture[i] = static_cast<int16_t>(std::clamp(p->capture_float[i] * 32768.f, -32768.f, 32767.f));
  if (result == 0) env->SetShortArrayRegion(pcm, 0, 160, p->capture.data());
  return result == 0 && !env->ExceptionCheck();
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_aliflix_app_talk_TalkApmNative_statistics(JNIEnv* env, jobject, jlong h) {
  if (!h) return nullptr;
  auto stats = get(h)->apm->GetStatistics();
  double values[] = {stats.echo_return_loss.value_or(-1), stats.echo_return_loss_enhancement.value_or(-1),
                     static_cast<double>(stats.delay_ms.value_or(-1))};
  auto result = env->NewDoubleArray(3);
  if (result) env->SetDoubleArrayRegion(result, 0, 3, values);
  return result;
}
