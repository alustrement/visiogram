// JNI layer over WebRTC's audio processing module (AEC3 echo canceller, high-pass filter, noise suppression),
// for app.visiogram.calls.EchoCanceller. Built by tools/build-echo-canceller.sh into libvisio_apm.so.
//
// ntgcalls' own WebRTC has no audio processing at all: the TV speakers would otherwise go straight back
// into the microphone. The render side gets what SpeakerSink plays, the capture side what MicSource records,
// both mono 16-bit in 10 ms blocks, from their own threads (the module is safe for that).

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <memory>

#include "api/audio/audio_processing.h"
#include "api/audio/echo_canceller3_config.h"
#include "api/audio/echo_control.h"
#include "api/scoped_refptr.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"

namespace {

constexpr const char* kTag = "EchoCanceller";

// Each AEC3 matched filter covers about 96 ms of echo delay; the default 5 stop near 480 ms, which a TV's
// audio path (HDMI, re-encoding for a sound system) can exceed.
constexpr size_t kMsPerMatchedFilter = 96;

class Aec3Factory : public webrtc::EchoControlFactory {
public:
    explicit Aec3Factory(webrtc::EchoCanceller3Config config): config_(config) {}

    std::unique_ptr<webrtc::EchoControl> Create(int sample_rate_hz, int num_render_channels, int num_capture_channels) override {
        return std::make_unique<webrtc::EchoCanceller3>(
            config_, std::nullopt, sample_rate_hz, num_render_channels, num_capture_channels);
    }

private:
    const webrtc::EchoCanceller3Config config_;
};

struct Processor {
    webrtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig stream;
};

Processor* from(jlong handle) {
    return reinterpret_cast<Processor*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_visiogram_calls_EchoCanceller_nativeCreate(JNIEnv*, jclass, jint sample_rate, jint max_delay_ms) {
    webrtc::EchoCanceller3Config aec3;
    aec3.delay.num_filters = std::max<size_t>(5, (static_cast<size_t>(max_delay_ms) + kMsPerMatchedFilter - 1) / kMsPerMatchedFilter);

    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false;
    config.high_pass_filter.enabled = true;
    config.noise_suppression.enabled = true;
    config.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;

    auto apm = webrtc::AudioProcessingBuilder()
        .SetConfig(config)
        .SetEchoControlFactory(std::make_unique<Aec3Factory>(aec3))
        .Create();
    if (!apm) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "AudioProcessing creation failed");
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "AEC3 ready, %d Hz, %zu delay filters", sample_rate, aec3.delay.num_filters);
    return reinterpret_cast<jlong>(new Processor{apm, webrtc::StreamConfig(sample_rate, 1)});
}

JNIEXPORT void JNICALL
Java_app_visiogram_calls_EchoCanceller_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete from(handle);
}

JNIEXPORT void JNICALL
Java_app_visiogram_calls_EchoCanceller_nativeRender(JNIEnv* env, jclass, jlong handle, jshortArray samples) {
    auto* processor = from(handle);
    auto* data = static_cast<int16_t*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    // The reference is only analysed; JNI_ABORT leaves the caller's samples untouched.
    processor->apm->ProcessReverseStream(data, processor->stream, processor->stream, data);
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_app_visiogram_calls_EchoCanceller_nativeCapture(JNIEnv* env, jclass, jlong handle, jshortArray samples, jint delay_ms) {
    auto* processor = from(handle);
    processor->apm->set_stream_delay_ms(delay_ms);
    auto* data = static_cast<int16_t*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    processor->apm->ProcessStream(data, processor->stream, processor->stream, data);
    env->ReleasePrimitiveArrayCritical(samples, data, 0);
}

// [echo return loss enhancement dB, echo return loss dB, estimated delay ms]; NaN when not known yet.
JNIEXPORT jdoubleArray JNICALL
Java_app_visiogram_calls_EchoCanceller_nativeStats(JNIEnv* env, jclass, jlong handle) {
    const auto stats = from(handle)->apm->GetStatistics();
    const jdouble values[] = {
        stats.echo_return_loss_enhancement.value_or(NAN),
        stats.echo_return_loss.value_or(NAN),
        stats.delay_ms ? static_cast<jdouble>(*stats.delay_ms) : NAN,
    };
    auto result = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

}  // extern "C"
