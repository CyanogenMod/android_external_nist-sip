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

public interface RepeatTime {
    LinkedList getOffsets();
    int getRepeatInterval() throws SdpParseException;
    int getActiveDuration() throws SdpParseException;
    int[] getOffsetArray() throws SdpParseException;
    void setOffsetArray(int[] offsets) throws SdpException;
    boolean getTypedTime() throws SdpParseException;
    void setTypedTime(boolean typedTime);
    String encode();
    Object clone();
}
