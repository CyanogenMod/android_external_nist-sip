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

#ifndef __RTP_SOCKET_H__
#define __RTP_SOCKET_H__

#include "jni.h"

struct RtpSocket;

// Returns NULL and throws an exception if an error occurs.
RtpSocket *getRtpSocket(JNIEnv *env, jobject jRtpSocket, bool associated);

// Returns the number of bytes sent or -1 if an error occurs. The error code
// can be found in errno.
int send(RtpSocket *rtpSocket, void *buffer, int length);

// Returns the REAL LENGTH of the packet received, 0 if deadline is reached,
// or -1 if an error occurs. The error code can be found in errno.
int receive(RtpSocket *rtpSocket, void *buffer, int length, timeval *deadline);

#endif
