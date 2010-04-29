/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/time.h>
#include <time.h>

#include <cutils/atomic.h>

#define LOG_TAG "AudioStream"
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <media/AudioRecord.h>
#include <media/AudioTrack.h>
#include <media/mediarecorder.h>

#include "jni.h"
#include "JNIHelp.h"

#include "RtpSocket.h"
#include "AudioCodec.h"

using namespace android;

static int gRandom = -1;

class AudioStream
{
public:
    AudioStream();
    ~AudioStream();

    bool set(RtpSocket *rtpSocket, const char *codecName,
        int sampleRate, int sampleCount, int codecType, int dtmfType);

    bool startSending();
    bool startReceiving();
    bool sendDtmf(int event);
    void stopSending();
    void stopReceiving();

private:
    RtpSocket *mSocket;
    AudioCodec *mCodec;
    AudioRecord mRecord;
    AudioTrack mTrack;

    uint16_t mLocalSequence;
    uint32_t mLocalTimestamp;
    uint32_t mLocalSsrc;
    uint32_t mRemoteTimestamp;
    uint32_t mRemoteSsrc;
    uint32_t mCodecMagic;
    uint32_t mDtmfMagic;

    int mSampleRate;
    int mSampleCount;
    int mInterval;
    int mTimer;

    volatile int32_t mNextDtmfEvent;
    int mDtmfEvent;
    int mDtmfDuration;

    bool encode();
    bool decode();

    class Sender : public Thread
    {
    public:
        Sender(AudioStream *stream) : Thread(false), mStream(stream) {}
    private:
        virtual bool threadLoop()
        {
            if (!mStream->encode()) {
                mStream->mRecord.stop();
                return false;
            }
            return true;
        }
        AudioStream *mStream;
    };
    sp<Sender> mSender;

    class Receiver : public Thread
    {
    public:
        Receiver(AudioStream *stream) : Thread(false), mStream(stream) {}
    private:
        virtual bool threadLoop()
        {
            if (!mStream->decode()) {
                mStream->mTrack.stop();
                return false;
            }
            return true;
        }
        AudioStream *mStream;
    };
    sp<Receiver> mReceiver;
};

AudioStream::AudioStream()
{
    mSender = new Sender(this);
    mReceiver = new Receiver(this);
    mCodec = NULL;
}

AudioStream::~AudioStream()
{
    stopSending();
    stopReceiving();
    mSender.clear();
    mReceiver.clear();
    delete mCodec;
}

bool AudioStream::set(RtpSocket *rtpSocket, const char *codecName,
    int sampleRate, int sampleCount, int codecType, int dtmfType)
{
    mSocket = rtpSocket;

    // One frame per second is just not reasonable.
    if (sampleRate <= 0 || sampleCount <= 0 || sampleRate <= sampleCount) {
        return false;
    }

    // Find AudioCodec and configure it.
    if (strcmp("PCMU", codecName) == 0) {
        mCodec = new UlawCodec;
    } else if (strcmp("PCMA", codecName) == 0) {
        mCodec = new AlawCodec;
    } else {
        mCodec = NULL;
    }
    if (mCodec == NULL || !mCodec->set(sampleCount)) {
        return false;
    }

    // Set AudioRecord with double buffer. Otherwise try system default.
    if (mRecord.set(AUDIO_SOURCE_MIC, sampleRate, AudioSystem::PCM_16_BIT,
        AudioSystem::CHANNEL_IN_MONO, sampleCount * 2) != NO_ERROR &&
        mRecord.set(AUDIO_SOURCE_MIC, sampleRate, AudioSystem::PCM_16_BIT,
        AudioSystem::CHANNEL_IN_MONO) != NO_ERROR) {
        return false;
    }

    // Set AudioTrack with double buffer. Otherwise try system default.
    if (mTrack.set(AudioSystem::VOICE_CALL, sampleRate, AudioSystem::PCM_16_BIT,
        AudioSystem::CHANNEL_OUT_MONO, sampleCount * 2) != NO_ERROR &&
        mTrack.set(AudioSystem::VOICE_CALL, sampleRate, AudioSystem::PCM_16_BIT,
        AudioSystem::CHANNEL_OUT_MONO) != NO_ERROR) {
        return false;
    }

    // Only initialize these random bits once for the maximum compatibility.
    read(gRandom, &mLocalSequence, sizeof(mLocalSequence));
    read(gRandom, &mLocalTimestamp, sizeof(mLocalTimestamp));
    read(gRandom, &mLocalSsrc, sizeof(mLocalSsrc));

    mCodecMagic = (0x8000 | codecType) << 16;
    mDtmfMagic = (dtmfType == -1 ? -1 : (0x8000 | dtmfType) << 16);

    mSampleRate = sampleRate;
    mSampleCount = sampleCount;

    // mInterval is a threshold for jitter control in microseconds. To avoid
    // introducing more latencies, here we use 0.8 times of the real interval.
    mInterval = 1000 * sampleCount / sampleRate * 800;

    return true;
}

bool AudioStream::startSending()
{
    if (mRecord.stopped()) {
        mTimer = 0;
        mNextDtmfEvent = -1;
        mDtmfEvent = -1;

        if (mRecord.start() != NO_ERROR ||
            mSender->run("Sender", ANDROID_PRIORITY_AUDIO) != NO_ERROR) {
            mRecord.stop();
            return false;
        }
    }
    return true;
}

bool AudioStream::startReceiving()
{
    if (mTrack.stopped()) {
        mRemoteTimestamp = 0;
        mRemoteSsrc = 0;

        mTrack.start();
        if (mReceiver->run("Receiver", ANDROID_PRIORITY_AUDIO) != NO_ERROR) {
            mTrack.stop();
            return false;
        }
    }
    return true;
}

bool AudioStream::sendDtmf(int event)
{
    if (mRecord.stopped() || ~mDtmfMagic == 0) {
        return false;
    }
    if (android_atomic_cmpxchg(-1, event, &mNextDtmfEvent)) {
        usleep(mInterval * 2);
        return !android_atomic_cmpxchg(-1, event, &mNextDtmfEvent);
    }
    return true;
}

void AudioStream::stopSending()
{
    if (!mRecord.stopped()) {
        mSender->requestExitAndWait();
        mRecord.stop();
    }
}

void AudioStream::stopReceiving()
{
    if (!mTrack.stopped()) {
        mReceiver->requestExitAndWait();
        mTrack.stop();
    }
}

// -----------------------------------------------------------------------------

bool AudioStream::encode()
{
    int16_t samples[mSampleCount];

    // Read samples from AudioRecord. Since AudioRecord itself has fault
    // recovery mechanism, we just return false if the length is wrong.
    int length = mRecord.read(samples, sizeof(samples));
    if (length - sizeof(samples) != 0) {
        LOGD("read");
        return false;
    }

    mLocalSequence++;
    mLocalTimestamp += mSampleCount;

    // If we have a DTMF event to send, send it now.
    int32_t event = android_atomic_swap(-1, &mNextDtmfEvent);
    if (event != -1) {
        mDtmfEvent = event << 25;
        mDtmfDuration = 0;
    }
    if (mDtmfEvent != -1) {
        mDtmfDuration += mSampleCount;
        uint32_t packet[4] = {
            htonl(mDtmfMagic | mLocalSequence),
            htonl(mLocalTimestamp - mDtmfDuration),
            mLocalSsrc,
            htonl(mDtmfEvent | mDtmfDuration),
        };
        // Make the DTMF event roughly 200 millisecond long.
        if (mDtmfDuration * 5 >= mSampleRate) {
            packet[3] |= 1 << 24;
            mDtmfEvent = -1;
        }
        send(mSocket, packet, sizeof(packet));
        return true;
    }

    // Otherwise encode the samples and prepare the packet.
    __attribute__((aligned(4)) uint8_t packet[12 + sizeof(samples)];

    uint32_t *header = (uint32_t *)packet;
    header[0] = htonl(mCodecMagic | mLocalSequence);
    header[1] = htonl(mLocalTimestamp);
    header[2] = mLocalSsrc;

    length = mCodec->encode(&packet[12], samples);
    if (length <= 0) {
        LOGD("encode");
        return false;
    }
    length += 12;

    // Here we implement a simple jitter control for the outgoing packets.
    // Ideally we should send out packets at a constant rate, but in practice
    // every component in the pipeline might delay or speed up a little. To
    // avoid making things worse, we only delay the packet which comes early.
    // Note that interval is less than the real one, so the delay will be
    // converged.
    timeval now;
    if (gettimeofday(&now, NULL) != 0) {
        LOGD("gettimeofday");
        return false;
    }
    int interval = now.tv_sec * 1000000 + now.tv_usec - mTimer;
    if (interval > 0 && interval < mInterval) {
        usleep(mInterval - interval);
        interval = mInterval;
    }
    mTimer += interval;

    send(mSocket, packet, length);
    return true;
}

bool AudioStream::decode()
{
    timeval deadline;

    if (gettimeofday(&deadline, NULL) != 0) {
        LOGD("gettimeofday");
        return false;
    }

    // mInterval is always less than 1000000.
    deadline.tv_usec += mInterval;
    if (deadline.tv_usec > 1000000) {
        deadline.tv_usec -= 1000000;
        deadline.tv_sec++;
    }

    int16_t samples[mSampleCount];
    __attribute__((aligned(4)) uint8_t packet[2048 + sizeof(samples)];

    while (1) {
        int length = receive(mSocket, packet, sizeof(packet), &deadline);
        if (length <= 0) {
            return true;
        }
        if (length < 12) {
            continue;
        }

        // Here we check all the fields in the standard RTP header. Some
        // restrictions might be too tight and could be removed in the future.
        int offset = 12 + (packet[0] & 0x0F) * 4;
        if ((packet[0] & 0x10) != 0) {
            offset += 4 + (packet[offset + 2] << 8 | packet[offset + 3]) * 4;
        }
        if ((packet[0] & 0x20) != 0 && length - sizeof(packet) <= 0) {
            length -= packet[length - 1];
        }
        length -= offset;
        if (length < 0) {
            continue;
        }

        uint32_t *header = (uint32_t *)packet;
        header[0] = ntohl(header[0]);
        header[1] = ntohl(header[1]);

        if ((header[0] & 0xC07F0000) != mCodecMagic) {
            LOGD("wrong magic (%X != %X)", mCodecMagic, header[0] & 0xC07F0000);
            continue;
        }

        if (mRemoteTimestamp != 0) {
            if ((int)(header[1] - mRemoteTimestamp) < 0) {
                LOGD("decrescent timestamp (difference = %d)",
                    header[1] - mRemoteTimestamp);
                continue;
            }
            if (header[2] != mRemoteSsrc) {
                LOGD("wrong ssrc (%X != %X)", mRemoteSsrc, header[2]);
                continue;
            }
        }

        length = mCodec->decode(samples, &packet[offset], length) * 2;
        if (length <= 0) {
            LOGD("decode");
            continue;
        }

        mRemoteTimestamp = header[1];
        mRemoteSsrc = header[2];

        // Here we implement a simple jitter control for the incoming packets.
        // Ideally there should be only one packet every time we try to read
        // from the socket. If any packets are late, we must drop some of them
        // or the delay will get longer and longer. In this implementation we
        // simply drain the socket after we get a valid packet. We are also
        // looking for better alternatives such as removing silence, but let us
        // start simple. :)
        for (int i = 0; i < 100; ++i) {
            if (receive(mSocket, NULL, 0, NULL) <= 0) {
                if (i > 0) {
                    LOGD("drop %d packets", i);
                }
                break;
            }
        }

        // Write samples to AudioTrack. Again, since AudioTrack itself has fault
        // recovery mechanism, we just return false if the length is wrong.
        return mTrack.write(samples, length) == length;
    }
}

// -----------------------------------------------------------------------------

static jfieldID gNative;

static void throwIllegalStateException(JNIEnv *env, const char *message)
{
    jniThrowException(env, "java/lang/IllegalStateException", message);
}

// All these JNI methods are synchronized in java class, so we implement them
// without using any mutex locks. Simple is best!

static void prepare(JNIEnv *env, jobject thiz, jobject jRtpSocket,
    jstring jCodecName, jint sampleRate, jint sampleCount, jint codecType,
    jint dtmfType)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream != NULL) {
        throwIllegalStateException(env, "Already prepared");
        return;
    }

    RtpSocket *rtpSocket = getRtpSocket(env, jRtpSocket, true);
    if (rtpSocket == NULL) {
        // Exception already thrown.
        return;
    }

    if (jCodecName == NULL) {
        jniThrowNullPointerException(env, "codecName");
        return;
    }

    const char *codecName = env->GetStringUTFChars(jCodecName, NULL);
    stream = new AudioStream;
    if (!stream->set(rtpSocket, codecName, sampleRate, sampleCount, codecType,
        dtmfType)) {
        delete stream;
        stream = NULL;
    }
    env->ReleaseStringUTFChars(jCodecName, codecName);

    if (stream == NULL) {
         throwIllegalStateException(env, "Failed to create native AudioStream");
    }
    env->SetIntField(thiz, gNative, (int)stream);
}

static jboolean isPrepared(JNIEnv *env, jobject thiz)
{
    return env->GetIntField(thiz, gNative) != 0;
}

static void startSending(JNIEnv *env, jobject thiz)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream == NULL) {
        throwIllegalStateException(env, "Not prepared");
    } else if (!stream->startSending()) {
        throwIllegalStateException(env, "Failed to start native AudioRecord");
    }
}

static void startReceiving(JNIEnv *env, jobject thiz)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream == NULL) {
        throwIllegalStateException(env, "Not prepared");
    } else if (!stream->startReceiving()) {
        throwIllegalStateException(env, "Failed to start native AudioTrack");
    }
}

static void sendDtmf(JNIEnv *env, jobject thiz, jint event)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream == NULL) {
        throwIllegalStateException(env, "Not prepared");
    } else if (event < 0 || event > 15) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "event");
    } else if (!stream->sendDtmf(event)) {
        throwIllegalStateException(env, "Failed to send DTMF");
    }
}

static void stopSending(JNIEnv *env, jobject thiz)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream != NULL) {
        stream->stopSending();
    }
}

static void stopReceiving(JNIEnv *env, jobject thiz)
{
    AudioStream *stream = (AudioStream *)env->GetIntField(thiz, gNative);
    if (stream != NULL) {
        stream->stopReceiving();
    }
}

static void release(JNIEnv *env, jobject thiz)
{
    delete (AudioStream *)env->GetIntField(thiz, gNative);
    env->SetIntField(thiz, gNative, NULL);
}

//------------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"prepare", "(Lcom/android/sip/rtp/RtpSocket;Ljava/lang/String;IIII)V",
        (void *)prepare},
    {"isPrepared", "()Z", (void *)isPrepared},
    {"startSending", "()V", (void *)startSending},
    {"startReceiving", "()V", (void *)startReceiving},
    {"sendDtmf", "(I)V", (void *)sendDtmf},
    {"stopSending", "()V", (void *)stopSending},
    {"stopReceiving", "()V", (void *)stopReceiving},
    {"release", "()V", (void *)release},
};

int registerAudioStream(JNIEnv *env)
{
    gRandom = open("/dev/urandom", O_RDONLY);
    if (gRandom == -1) {
        LOGE("urandom: %s", strerror(errno));
        return -1;
    }

    jclass clazz;
    if ((clazz = env->FindClass("com/android/sip/rtp/AudioStream")) == NULL ||
        (gNative = env->GetFieldID(clazz, "mNative", "I")) == NULL ||
        env->RegisterNatives(clazz, gMethods, NELEM(gMethods)) < 0) {
        LOGE("JNI registration failed");
        return -1;
    }
    return 0;
}
