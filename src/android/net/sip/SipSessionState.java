/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.net.sip;

/**
 * Defines {@link SipSession} states.
 */
public enum SipSessionState {
    READY_FOR_CALL,
    REGISTERING,
    DEREGISTERING,
	INCOMING_CALL,
	INCOMING_CALL_ANSWERING,
	OUTGOING_CALL,
	OUTGOING_CALL_RING_BACK,
	OUTGOING_CALL_CANCELING,

    // the states below must be after the session being established
    IN_CALL,
    IN_CALL_CHANGING,
    IN_CALL_CHANGING_CANCELING,
    IN_CALL_ANSWERING,
}
