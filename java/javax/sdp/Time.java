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

package javax.sdp;

import java.util.*;

public interface Time {
    long getStartTime();
    long getStopTime();
    void setStartTime(long startTime);
    void setStopTime(long stopTime);
    Date getStart() throws SdpParseException;
    Date getStop() throws SdpParseException;
    void setStop(Date stop) throws SdpException;
    void setStart(Date start) throws SdpException;
    boolean getTypedTime();
    void setTypedTime(boolean typedTime);
    boolean isZero();
    void setZero();
    Object clone();
    String encode();
}
