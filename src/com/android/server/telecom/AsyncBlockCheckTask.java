/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;

import com.android.internal.telephony.BlockChecker;

/**
 * An {@link AsyncTask} that checks if a call needs to be blocked.
 * <p> An {@link AsyncTask} is used to perform the block check to avoid blocking the main thread.
 * The block check itself is performed in the {@link AsyncTask#doInBackground(Object[])}. However
 * a {@link Handler} passed by the caller is used to perform additional possibly intensive
 * operations such as call screening.
 */
class AsyncBlockCheckTask extends AsyncTask<String, Void, Boolean> {
    private final Context mContext;
    private final Call mIncomingCall;
    private final CallScreening mCallScreening;
    private final boolean mShouldSendToVoicemail;
    private final Handler mHandler = new Handler();
    private final Object mCallScreeningListenerLock = new Object();

    private Session mLogSubsession;
    private CallScreening.Listener mCallScreeningListener;
    private Session mLogSubsessionForTimeout;

    AsyncBlockCheckTask(Context context, Call incomingCall, CallScreening callScreening,
                        CallScreening.Listener callScreeningListener,
                        boolean shouldSendToVoicemail) {
        mContext = context;
        mIncomingCall = incomingCall;
        mCallScreening = callScreening;
        mCallScreeningListener = callScreeningListener;
        mShouldSendToVoicemail = shouldSendToVoicemail;
    }

    @Override
    protected void onPreExecute() {
        mLogSubsession = Log.createSubsession();
        mLogSubsessionForTimeout = Log.createSubsession();
        Runnable blockCheckTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (mCallScreeningListenerLock) {
                    if (mCallScreeningListener != null) {
                        try {
                            Log.continueSession(mLogSubsessionForTimeout, "aBCT.oPEr");
                            timeoutBlockCheck();
                            mCallScreeningListener = null;
                        } finally {
                            Log.endSession();
                            mLogSubsessionForTimeout = null;
                        }
                    }

                }
            }
        };
        mHandler.postDelayed(blockCheckTimeoutRunnable,
                Timeouts.getBlockCheckTimeoutMillis(mContext.getContentResolver()));
    }

    private void timeoutBlockCheck() {
        Log.event(mIncomingCall, Log.Events.BLOCK_CHECK_TIMED_OUT);
        mCallScreeningListener.onCallScreeningCompleted(
                mIncomingCall,
                true /*shouldAllowCall*/,
                false /*shouldReject*/,
                false /*shouldAddToCallLog*/,
                false /*shouldShowNotification*/);
    }

    @Override
    protected Boolean doInBackground(String... params) {
        try {
            Log.continueSession(mLogSubsession, "aBCT.dIBg");
            Log.event(mIncomingCall, Log.Events.BLOCK_CHECK_INITIATED);
            return BlockChecker.isBlocked(mContext, params[0]);
        } finally {
            Log.endSession();
        }
    }

    @Override
    protected void onPostExecute(Boolean isBlocked) {
        synchronized (mCallScreeningListenerLock) {
            mHandler.removeCallbacks(null);
            if (mCallScreeningListener != null) {
                processIsBlockedLocked(isBlocked);
                mCallScreeningListener = null;
                Log.cancelSubsession(mLogSubsessionForTimeout);
                mLogSubsessionForTimeout = null;
            }
        }
    }

    private void processIsBlockedLocked(boolean isBlocked) {
        Log.event(mIncomingCall, Log.Events.BLOCK_CHECK_FINISHED);
        if (isBlocked) {
            mCallScreeningListener.onCallScreeningCompleted(
                    mIncomingCall,
                    false /*shouldAllowCall*/,
                    true /*shouldReject*/,
                    false /*shouldAddToCallLog*/,
                    false /*shouldShowNotification*/);
        } else if (mShouldSendToVoicemail) {
            mCallScreeningListener.onCallScreeningCompleted(
                    mIncomingCall,
                    false /*shouldAllowCall*/,
                    true /*shouldReject*/,
                    true /*shouldAddToCallLog*/,
                    true /*shouldShowNotification*/);
        } else {
            mCallScreening.screenCall();
        }
    }
}