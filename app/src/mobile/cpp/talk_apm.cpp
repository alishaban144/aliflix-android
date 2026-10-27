// SPDX-License-Identifier: BSD-3-Clause
// Aliflix's small JNI boundary; DSP is the unmodified WebRTC APM extraction.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <new>
#include "api/audio/audio_processing.h"

namespace {
struct Processor {
  rtc::scoped_refptr<webrtc::AudioProcessing> apm;
  std::array<float, 240> render{};
  std::array<int16_t, 160> capture{};
  std::array<float, 160> capture_float{};
  std::deque<std::array<float, 240>> pending_render;
  bool discontinuity = false;
  Processor() {
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false; // AEC3, including desktop-quality double talk.
    config.noise_suppression.enabled = false;
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;
    apm = webrtc::AudioProcessingBuilder().SetConfig(config).Create();
  }
  int initialize() {
    pending_render.clear();discontinuity=false;
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
  // AudioTrack writes fill a future playout reservoir. They are not a render
  // clock: keep reference frames until the capture clock advances below.
  if (p->pending_render.size() >= 256) {p->pending_render.clear();p->discontinuity=true;}
  p->pending_render.push_back(p->render);
  return true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_aliflix_app_talk_TalkApmNative_discardRender(JNIEnv*, jobject, jlong h) {
  if (h) get(h)->pending_render.clear();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aliflix_app_talk_TalkApmNative_capture(JNIEnv* env, jobject, jlong h, jshortArray pcm, jint delay) {
  if (!h || env->GetArrayLength(pcm) != 160) return false;
  auto* p = get(h);
  env->GetShortArrayRegion(pcm, 0, 160, p->capture.data());
  if (env->ExceptionCheck()) return false;
  if (p->discontinuity && p->initialize() != 0) return false;
  std::array<float, 240> reference{};
  if (!p->pending_render.empty()) {reference=p->pending_render.front();p->pending_render.pop_front();}
  const float* reverse_src[] = {reference.data()};
  float* reverse_dst[] = {reference.data()};
  const webrtc::StreamConfig reverse(24000, 1);
  if (p->apm->ProcessReverseStream(reverse_src, reverse, reverse, reverse_dst) != 0) return false;
  // Exclude reference audio still waiting in our queue. AEC3's default internal
  // estimator refines the measured device delay, including vendor DSP latency.
  int queued_ms=static_cast<int>(p->pending_render.size())*10;
  p->apm->set_stream_delay_ms(std::clamp(static_cast<int>(delay)-queued_ms,0,500));
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
