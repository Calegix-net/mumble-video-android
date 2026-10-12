// JNI binding of mumble-core's MediaSession for com.calegix.mumblevideo.client.media.MumbleCoreMedia.
//
// Every call that can produce MediaEvents returns them serialised into one byte[] (layout below), or
// null when there are none, so a datagram costs exactly one JNI crossing and no Java objects are
// built here. MediaEventsCodec.kt reads the layout back; keep the two in step.
//
// Layout, little-endian:
//   u32 count, then per audio packet:
//       u32 sender, u64 frameNumber, u32 context, u8 flags (1 lastFrame, 2 hasPosition),
//       f32 x, f32 y, f32 z, f32 volumeFactor, u32 length, bytes opus
//   u32 count, then per video unit:
//       u32 sender, u32 streamID, u64 frameNumber, u32 unitID, u8 flags (1 keyframe, 2 frameEnd),
//       u64 captureTimestampUsec, u32 x, u32 y, u32 width, u32 height, u32 length, bytes payload
//   u32 count, then per pong: u64 timestamp
//   then three blob lists (video NACKs for us, datagrams to send, control to send), each:
//       u32 count, then per blob: u32 length, bytes
//
// A MediaSession is not thread safe; the Kotlin side serialises calls per handle.

#include <jni.h>
#include <android/log.h>

#include "CoreLog.h"
#include "mumble-core/MumbleCore.h"

#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace {

struct Session {
	MumbleCore::MediaSession session;
	MumbleCore::MediaEvents events;
};

Session *fromHandle(jlong handle) {
	return reinterpret_cast< Session * >(static_cast< intptr_t >(handle));
}

class Writer {
public:
	void u8(std::uint8_t v) { m_out.push_back(v); }
	void u32(std::uint32_t v) {
		for (int i = 0; i < 4; ++i) {
			m_out.push_back(static_cast< std::uint8_t >(v >> (8 * i)));
		}
	}
	void u64(std::uint64_t v) {
		for (int i = 0; i < 8; ++i) {
			m_out.push_back(static_cast< std::uint8_t >(v >> (8 * i)));
		}
	}
	void f32(float v) {
		std::uint32_t bits;
		std::memcpy(&bits, &v, sizeof bits);
		u32(bits);
	}
	void bytes(const MumbleCore::Bytes &b) {
		u32(static_cast< std::uint32_t >(b.size()));
		m_out.insert(m_out.end(), b.begin(), b.end());
	}
	void blobs(const std::vector< MumbleCore::Bytes > &list) {
		u32(static_cast< std::uint32_t >(list.size()));
		for (const auto &b : list) {
			bytes(b);
		}
	}
	const std::vector< std::uint8_t > &data() const { return m_out; }

private:
	std::vector< std::uint8_t > m_out;
};

bool isEmpty(const MumbleCore::MediaEvents &e) {
	return e.audio.empty() && e.video.empty() && e.pongs.empty() && e.videoNacksForUs.empty()
		   && e.datagramsToSend.empty() && e.controlToSend.empty();
}

jbyteArray toJava(JNIEnv *env, const std::vector< std::uint8_t > &bytes) {
	jbyteArray array = env->NewByteArray(static_cast< jsize >(bytes.size()));
	if (array && !bytes.empty()) {
		env->SetByteArrayRegion(array, 0, static_cast< jsize >(bytes.size()),
								reinterpret_cast< const jbyte * >(bytes.data()));
	}
	return array;
}

jbyteArray serialise(JNIEnv *env, MumbleCore::MediaEvents &e, bool evenIfEmpty) {
	if (!evenIfEmpty && isEmpty(e)) {
		return nullptr;
	}
	Writer w;
	w.u32(static_cast< std::uint32_t >(e.audio.size()));
	for (const auto &a : e.audio) {
		w.u32(a.senderSession);
		w.u64(a.frameNumber);
		w.u32(a.context);
		w.u8(static_cast< std::uint8_t >((a.isLastFrame ? 1 : 0) | (a.hasPosition ? 2 : 0)));
		w.f32(a.position[0]);
		w.f32(a.position[1]);
		w.f32(a.position[2]);
		w.f32(a.volumeFactor);
		w.bytes(a.opus);
	}
	w.u32(static_cast< std::uint32_t >(e.video.size()));
	for (const auto &v : e.video) {
		w.u32(v.senderSession);
		w.u32(v.streamID);
		w.u64(v.frameNumber);
		w.u32(v.unitID);
		w.u8(static_cast< std::uint8_t >((v.isKeyframe ? 1 : 0) | (v.isFrameEnd ? 2 : 0)));
		w.u64(v.captureTimestampUsec);
		w.u32(v.x);
		w.u32(v.y);
		w.u32(v.width);
		w.u32(v.height);
		w.bytes(v.payload);
	}
	w.u32(static_cast< std::uint32_t >(e.pongs.size()));
	for (auto p : e.pongs) {
		w.u64(p);
	}
	w.blobs(e.videoNacksForUs);
	w.blobs(e.datagramsToSend);
	w.blobs(e.controlToSend);
	e.clear();
	return toJava(env, w.data());
}

// Copies a Java byte[] range for the core's span-based API.
std::vector< std::uint8_t > fromJava(JNIEnv *env, jbyteArray array, jint length) {
	std::vector< std::uint8_t > out(static_cast< std::size_t >(length));
	if (length > 0) {
		env->GetByteArrayRegion(array, 0, length, reinterpret_cast< jbyte * >(out.data()));
	}
	return out;
}

void logToLogcat(Mumble::Core::LogLevel level, const char *message) {
	int priority = level == Mumble::Core::LogLevel::Info ? ANDROID_LOG_INFO
				   : level == Mumble::Core::LogLevel::Warning ? ANDROID_LOG_WARN
															  : ANDROID_LOG_FATAL;
	__android_log_write(priority, "MumbleCore", message);
}

} // namespace

#define FN(name) Java_com_calegix_mumblevideo_client_media_MumbleCoreMedia_##name

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
	Mumble::Core::setLogHandler(&logToLogcat);
	return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL FN(nativeVersion)(JNIEnv *env, jclass) {
	return env->NewStringUTF(MumbleCore::version());
}

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *, jclass) {
	return static_cast< jlong >(reinterpret_cast< intptr_t >(new Session()));
}

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *, jclass, jlong handle) {
	delete fromHandle(handle);
}

JNIEXPORT void JNICALL FN(nativeSetServerVersion)(JNIEnv *, jclass, jlong handle, jlong versionV2) {
	fromHandle(handle)->session.setServerVersion(static_cast< std::uint64_t >(versionV2));
}

JNIEXPORT void JNICALL FN(nativeSetUdpFraming)(JNIEnv *, jclass, jlong handle, jboolean channelByte) {
	fromHandle(handle)->session.setUdpFraming(channelByte ? MumbleCore::UdpFraming::ChannelByte
														   : MumbleCore::UdpFraming::Plain);
}

JNIEXPORT jbyteArray JNICALL FN(nativeHandleCryptSetup)(JNIEnv *env, jclass, jlong handle, jbyteArray payload,
														 jint length) {
	Session *s       = fromHandle(handle);
	const auto bytes = fromJava(env, payload, length);
	if (!s->session.handleCryptSetup(bytes, s->events)) {
		s->events.clear();
		return nullptr;
	}
	return serialise(env, s->events, true);
}

JNIEXPORT jboolean JNICALL FN(nativeIsKeyed)(JNIEnv *, jclass, jlong handle) {
	return fromHandle(handle)->session.isKeyed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL FN(nativeReceiveDatagram)(JNIEnv *env, jclass, jlong handle, jbyteArray datagram,
														jint length, jlong nowUsec) {
	Session *s       = fromHandle(handle);
	const auto bytes = fromJava(env, datagram, length);
	s->session.receiveDatagram(bytes, static_cast< std::uint64_t >(nowUsec), s->events);
	return serialise(env, s->events, false);
}

JNIEXPORT jbyteArray JNICALL FN(nativeReceiveTunnelled)(JNIEnv *env, jclass, jlong handle, jbyteArray payload,
														 jint length) {
	Session *s       = fromHandle(handle);
	const auto bytes = fromJava(env, payload, length);
	s->session.receiveTunnelled(bytes, s->events);
	return serialise(env, s->events, false);
}

JNIEXPORT jbyteArray JNICALL FN(nativeMakePing)(JNIEnv *env, jclass, jlong handle, jlong timestamp) {
	auto datagram = fromHandle(handle)->session.makePing(static_cast< std::uint64_t >(timestamp));
	return datagram ? toJava(env, *datagram) : nullptr;
}

JNIEXPORT jbyteArray JNICALL FN(nativeMakeAudio)(JNIEnv *env, jclass, jlong handle, jbyteArray opus, jint length,
												  jlong frameNumber, jint target, jboolean isLastFrame) {
	const auto frame = fromJava(env, opus, length);
	auto datagram    = fromHandle(handle)->session.makeAudio(frame, static_cast< std::uint64_t >(frameNumber),
															 static_cast< std::uint32_t >(target), isLastFrame);
	return datagram ? toJava(env, *datagram) : nullptr;
}

JNIEXPORT void JNICALL FN(nativeRemoveSender)(JNIEnv *, jclass, jlong handle, jint session) {
	fromHandle(handle)->session.removeSender(static_cast< std::uint32_t >(session));
}

JNIEXPORT jlongArray JNICALL FN(nativeStats)(JNIEnv *env, jclass, jlong handle) {
	const auto st      = fromHandle(handle)->session.stats();
	const jlong vals[] = { st.audioGood,    st.audioLate,   st.audioLost,         st.audioResync,
						   static_cast< jlong >(st.videoReplays), static_cast< jlong >(st.videoTooOld),
						   static_cast< jlong >(st.videoAuthFailures) };
	jlongArray array = env->NewLongArray(7);
	if (array) {
		env->SetLongArrayRegion(array, 0, 7, vals);
	}
	return array;
}

} // extern "C"
