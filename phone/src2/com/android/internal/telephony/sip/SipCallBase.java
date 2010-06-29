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

package com.android.internal.telephony.sip;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;

import android.net.sip.SipManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sip.SipException;

abstract class SipCallBase extends Call {
    private static final int MAX_CONNECTIONS_PER_CALL = 5;

    protected List<Connection> connections = new ArrayList<Connection>();
    /*package*/ //SipCallTracker owner;

    private static State stateFromDCState (DriverCall.State dcState) {
        switch (dcState) {
            case ACTIVE:        return State.ACTIVE;
            case HOLDING:       return State.HOLDING;
            case DIALING:       return State.DIALING;
            case ALERTING:      return State.ALERTING;
            case INCOMING:      return State.INCOMING;
            case WAITING:       return State.WAITING;
            default:            throw new RuntimeException ("illegal call state:" + dcState);
        }
    }


    //SipCall(SipPhone phone) {
        //this.owner = owner;
    //}

    public void dispose() {
    }

    /************************** Overridden from Call *************************/

    public List<Connection> getConnections() {
        // FIXME should return Collections.unmodifiableList();
        return connections;
    }

    public boolean isMultiparty() {
        return connections.size() > 1;
    }

    public String toString() {
        return state.toString();
    }

    //***** Called from SipConnection

    /*package*/ void attach(Connection conn, DriverCall dc) {
        connections.add(conn);

        state = stateFromDCState (dc.state);
    }

    /*package*/ void attachFake(Connection conn, State state) {
        connections.add(conn);

        this.state = state;
    }

    /**
     * Called by SipConnection when it has disconnected
     */
    void connectionDisconnected(Connection conn) {
        if (state != State.DISCONNECTED) {
            /* If only disconnected connections remain, we are disconnected*/

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = connections.size()  ; i < s; i ++) {
                if (connections.get(i).getState()
                    != State.DISCONNECTED
                ) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }

            if (hasOnlyDisconnectedConnections) {
                state = State.DISCONNECTED;
            }
        }
    }


    /*package*/ void detach(Connection conn) {
        connections.remove(conn);

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }

    /*package*/ boolean update (Connection conn, DriverCall dc) {
        State newState;
        boolean changed = false;

        newState = stateFromDCState(dc.state);

        if (newState != state) {
            state = newState;
            changed = true;
        }

        return changed;
    }

    /**
     * @return true if there's no space in this call for additional
     * connections to be added via "conference"
     */
    /*package*/ boolean isFull() {
        return connections.size() == MAX_CONNECTIONS_PER_CALL;
    }

    //***** Called from SipCallTracker


    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the radio
     * but no response has yet been received so update() has not yet been called
     */
    void onHangupLocal() {
        for (int i = 0, s = connections.size()
                ; i < s; i++
        ) {
            SipConnectionBase cn = (SipConnectionBase)connections.get(i);

            cn.onHangupLocal();
        }
        state = State.DISCONNECTING;
    }

    void clearDisconnected() {
        for (Iterator<Connection> it = connections.iterator(); it.hasNext(); ) {
            Connection c = it.next();
            if (c.getState() == State.DISCONNECTED) it.remove();
        }

        if (connections.isEmpty()) state = State.IDLE;
    }
}
