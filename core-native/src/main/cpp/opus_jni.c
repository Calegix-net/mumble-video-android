// JNI wrapper around libopus for com.calegix.mumblevideo.client.audio.Opus.
//
// Handles are the raw encoder/decoder pointers as jlong. Kotlin owns their lifetime and must call the
// matching destroy exactly once. Every function is called from a single audio thread per handle.

#include <jni.h>
#include <opus.h>
#include <stdint.h>

#define FN(name) Java_com_calegix_mumblevideo_client_audio_Opus_##name

JNIEXPORT jlong JNICALL FN(nativeEncoderCreate)(JNIEnv *env, jclass cls, jint sampleRate, jint channels,
                                                jint application) {
    (void) env;
    (void) cls;
    int error = 0;
    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels, application, &error);
    return error == OPUS_OK ? (jlong) (intptr_t) encoder : 0;
}

JNIEXPORT jint JNICALL FN(nativeEncoderCtl)(JNIEnv *env, jclass cls, jlong handle, jint request, jint value) {
    (void) env;
    (void) cls;
    OpusEncoder *encoder = (OpusEncoder *) (intptr_t) handle;
    // Only the integer-setting CTLs are exposed; they all take one opus_int32.
    return opus_encoder_ctl(encoder, request, (opus_int32) value);
}

JNIEXPORT jint JNICALL FN(nativeEncode)(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jint frameSize,
                                        jbyteArray out) {
    (void) cls;
    OpusEncoder *encoder = (OpusEncoder *) (intptr_t) handle;
    jsize outCapacity = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    opus_int32 written = opus_encode(encoder, in, frameSize, (unsigned char *) dst, outCapacity);
    (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return written;
}

JNIEXPORT void JNICALL FN(nativeEncoderDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL FN(nativeDecoderCreate)(JNIEnv *env, jclass cls, jint sampleRate, jint channels) {
    (void) env;
    (void) cls;
    int error = 0;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    return error == OPUS_OK ? (jlong) (intptr_t) decoder : 0;
}

// data == null runs packet loss concealment for frameSize samples.
JNIEXPORT jint JNICALL FN(nativeDecode)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint length,
                                        jshortArray pcm, jint frameSize, jboolean fec) {
    (void) cls;
    OpusDecoder *decoder = (OpusDecoder *) (intptr_t) handle;
    jbyte *src = data ? (*env)->GetPrimitiveArrayCritical(env, data, NULL) : NULL;
    jshort *out = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    int samples = opus_decode(decoder, (const unsigned char *) src, src ? length : 0, out, frameSize, fec ? 1 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, out, 0);
    if (src) (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
    return samples;
}

JNIEXPORT jint JNICALL FN(nativePacketSamples)(JNIEnv *env, jclass cls, jbyteArray data, jint length,
                                               jint sampleRate) {
    (void) cls;
    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    int samples = opus_packet_get_nb_samples((const unsigned char *) src, length, sampleRate);
    (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
    return samples;
}

JNIEXPORT void JNICALL FN(nativeDecoderDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL FN(nativeVersion)(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
