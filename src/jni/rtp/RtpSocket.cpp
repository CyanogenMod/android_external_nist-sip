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
#include <errno.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#define LOG_TAG "RtpSocket"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"

#include "RtpSocket.h"

static jfieldID gNative;

struct RtpSocket
{
    int mFd;
    int mFamily;
    sockaddr_storage mRemote;

    RtpSocket(int fd, sockaddr_storage *local)
    {
        mFd = fd;
        mFamily = local->ss_family;
        mRemote.ss_family = ~mFamily;
    }

    ~RtpSocket() { close(mFd); }
};

//------------------------------------------------------------------------------

RtpSocket *getRtpSocket(JNIEnv *env, jobject jRtpSocket, bool associated)
{
    if (jRtpSocket == NULL) {
        jniThrowNullPointerException(env, "rtpSocket");
        return NULL;
    }
    RtpSocket *rtpSocket = (RtpSocket *)env->GetIntField(jRtpSocket, gNative);
    if (rtpSocket == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "native");
        LOGE("native is NULL");
        return NULL;
    }
    if ((rtpSocket->mRemote.ss_family == rtpSocket->mFamily) != associated) {
        jniThrowException(env, "java/lang/IllegalStateException",
            strerror(associated ? ENOTCONN : EISCONN));
        return NULL;
    }
    return rtpSocket;
}

int send(RtpSocket *rtpSocket, void *buffer, int length)
{
    return sendto(rtpSocket->mFd, buffer, length, MSG_NOSIGNAL,
        (sockaddr *)&rtpSocket->mRemote, sizeof(rtpSocket->mRemote));
}

int receive(RtpSocket *rtpSocket, void *buffer, int length, timeval *deadline)
{
    int flag = MSG_TRUNC | MSG_DONTWAIT;
    if (deadline != NULL) {
        timeval timeout;
        if (gettimeofday(&timeout, NULL) != 0) {
            return -1;
        }

        int remain = (deadline->tv_sec - timeout.tv_sec) * 1000000 +
            deadline->tv_usec - timeout.tv_usec;
        if (remain <= 0) {
            return 0;
        }

        if (remain < 1000000) {
            timeout.tv_sec = 0;
            timeout.tv_usec = remain;
        } else {
            timeout.tv_sec = remain / 1000000;
            timeout.tv_usec = remain - timeout.tv_sec * 1000000;
        }
        if (setsockopt(rtpSocket->mFd, SOL_SOCKET, SO_RCVTIMEO, &timeout,
            sizeof(timeout)) != 0) {
            return -1;
        }
        flag ^= MSG_DONTWAIT;
    }

    length = recv(rtpSocket->mFd, buffer, length, flag);
    if (length == -1 && (errno == EAGAIN || errno == EINTR)) {
        return 0;
    }
    return length;
}

//------------------------------------------------------------------------------

static void throwSocketException(JNIEnv *env, int error)
{
    jniThrowException(env, "java/net/SocketException", strerror(error));
}

static int parse(JNIEnv *env, jstring jAddress, jint port, sockaddr_storage *ss)
{
    if (jAddress == NULL) {
        jniThrowNullPointerException(env, "address");
        return -1;
    }
    if (port < 0 || port > 65535) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "port");
        return -1;
    }
    const char *address = env->GetStringUTFChars(jAddress, NULL);
    if (address == NULL) {
        // Exception already thrown.
        return -1;
    }
    memset(ss, 0, sizeof(*ss));

    sockaddr_in *sin = (sockaddr_in *)ss;
    if (inet_pton(AF_INET, address, &(sin->sin_addr)) > 0) {
        sin->sin_family = AF_INET;
        sin->sin_port = htons(port);
        env->ReleaseStringUTFChars(jAddress, address);
        return 0;
    }

    sockaddr_in6 *sin6 = (sockaddr_in6 *)ss;
    if (inet_pton(AF_INET6, address, &(sin6->sin6_addr)) > 0) {
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(port);
        env->ReleaseStringUTFChars(jAddress, address);
        return 0;
    }

    env->ReleaseStringUTFChars(jAddress, address);
    jniThrowException(env, "java/lang/IllegalArgumentException", "address");
    return -1;
}

static jint create(JNIEnv *env, jobject thiz, jstring jAddress)
{
    sockaddr_storage ss;
    if (parse(env, jAddress, 0, &ss) < 0) {
        // Exception already thrown.
        return -1;
    }

    int fd = socket(ss.ss_family, SOCK_DGRAM, 0);
    int len = sizeof(ss);
    if (fd == -1 || bind(fd, (sockaddr *)&ss, sizeof(ss)) != 0 ||
        getsockname(fd, (sockaddr *)&ss, &len) != 0) {
        throwSocketException(env, errno);
        close(fd);
        return -1;
    }

    uint16_t *p = (ss.ss_family == AF_INET ?
        &((sockaddr_in *)&ss)->sin_port : &((sockaddr_in6 *)&ss)->sin6_port);
    uint16_t port = ntohs(*p);
    if ((port & 1) == 0) {
        env->SetIntField(thiz, gNative, (int)new RtpSocket(fd, &ss));
        return port;
    }
    close(fd);

    fd = socket(ss.ss_family, SOCK_DGRAM, 0);
    if (fd != -1) {
        uint16_t delta = port << 1;
        ++port;

        for (int i = 0; i < 1000; ++i) {
            do {
                port += delta;
            } while (port < 1024);
            *p = htons(port);

            if (bind(fd, (sockaddr *)&ss, sizeof(ss)) == 0) {
                env->SetIntField(thiz, gNative, (int)new RtpSocket(fd, &ss));
                return port;
            }
        }
    }

    throwSocketException(env, errno);
    close(fd);
    return -1;
}

static void associate(JNIEnv *env, jobject thiz, jstring jAddress, jint port)
{
    RtpSocket *rtpSocket = getRtpSocket(env, thiz, false);
    if (rtpSocket == NULL) {
        // Exception already thrown.
        return;
    }
    sockaddr_storage ss;
    if (parse(env, jAddress, port, &ss) < 0) {
        // Exception already thrown.
        return;
    }
    if (rtpSocket->mFamily != ss.ss_family) {
        throwSocketException(env, EAFNOSUPPORT);
        return;
    }
    rtpSocket->mRemote = ss;
}

static void release(JNIEnv *env, jobject thiz)
{
    delete (RtpSocket *)env->GetIntField(thiz, gNative);
}

//------------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"create", "(Ljava/lang/String;)I", (void *)create},
    {"associate", "(Ljava/lang/String;I)V", (void *)associate},
    {"release", "()V", (void *)release},
};

int registerRtpSocket(JNIEnv *env)
{
    jclass clazz;
    if ((clazz = env->FindClass("com/android/sip/rtp/RtpSocket")) == NULL ||
        (gNative = env->GetFieldID(clazz, "mNative", "I")) == NULL ||
        env->RegisterNatives(clazz, gMethods, NELEM(gMethods)) < 0) {
        LOGE("JNI registration failed");
        return -1;
    }
    return 0;
}
