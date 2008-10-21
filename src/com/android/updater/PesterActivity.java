/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.updater;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Checkin;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import java.io.File;

/**
 * A simple Activity that prompts the user for whether they want to update.
 */
public class PesterActivity extends Activity {
    /** Class identifier for logging. */
    private static final String TAG = "PesterActivity";

    /** Alarm manager for setting prompt timeouts. */
    private AlarmManager mAlarmManager;

    /** Intent used to reboot and install an OTA package. */
    private PendingIntent mInstallIntent;

    /** Currently active prompt, if displayed. */
    private static PesterActivity mActivityStarted;

    /** @return whether a call is in progress (or the phone is ringing) */
    private boolean inPhoneCall() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_PHONE_STATE_CHANGED);
        Intent intent = registerReceiver(null, filter);
        if (intent == null) return false;

        String state = intent.getStringExtra(Phone.STATE_KEY);
        if (state == null) return false;

        try {
            Phone.State phoneState = Phone.State.valueOf(state);
            if (phoneState != Phone.State.IDLE) return true;
        } catch (IllegalArgumentException e) {
            // returned state isn't a valid enum value.
            Log.e(TAG, "parsing phone state:", e);
        }
        return false;
    }

    /**
     * @param intent to get first prompt time and interval schedule from
     * @param currentTime (in elapsedRealtime) to look for a prompt after
     * @return elapsedRealtime of the next prompt, or 0 if there are no more
     */
    private static long getNextPromptTime(Intent intent, long currentTime) {
        int[] minutes = new int[] { 30 };  // Default: every 30 minutes forever
        boolean forever = true;

        String intervals = intent.getStringExtra("promptMinutes");
        if (intervals != null) {
            if (intervals.endsWith(",...")) {
                forever = true;
                intervals = intervals.substring(0, intervals.length() - 4);
            } else {
                forever = false;
            }

            try {
                String[] split = intervals.split(",");
                int[] parsed = new int[split.length];
                for (int i = 0; i < split.length; i++) {
                    parsed[i] = Integer.parseInt(split[i]);
                }
                minutes = parsed;  // Only after they've all been converted.
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid prompt intervals: " + intervals, e);
                forever = false;
                // Use the defaults in this case.
            }
        }

        // Run through the prompts until we find the next one after now.
        long promptTime = intent.getLongExtra("firstPrompt", currentTime);
        for (int i = 0; promptTime <= currentTime && i < minutes.length; i++) {
            promptTime += minutes[i] * 60 * 1000;
        }

        // If the prompts go forever, just keep repeating the last interval.
        while (forever && promptTime <= currentTime) {
            if (minutes.length == 0 || minutes[minutes.length - 1] <= 0) {
                Log.e(TAG, "Invalid infinite interval: " + intervals);
                break;
            }
            promptTime += minutes[minutes.length - 1] * 60 * 1000;
        }

        return (promptTime <= currentTime) ? 0 : promptTime;
    }

    /**
     * @param intent to get prompt delay settings from
     * @return milliseconds to wait, 0 to wait forever
     */
    private static long getPromptTimeout(Intent intent) {
        long value = 0;  // Default: wait forever (no timeout)
        String seconds = intent.getStringExtra("timeoutSeconds");
        try {
            if (seconds != null) value = Long.parseLong(seconds) * 1000;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid timeout delay: " + seconds, e);
        }
        return value;
    }

    /** Install the update immediately and dismiss the dialog. */
    private void installUpdate() {
        if (mInstallIntent != null) {
            try {
                mInstallIntent.send();
            } catch (PendingIntent.CanceledException e) {
                throw new RuntimeException(e);  // Should not happen!
            }
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        setContentView(R.layout.pester);
        getWindow().setFeatureDrawableResource(
            Window.FEATURE_LEFT_ICON, android.R.drawable.ic_dialog_alert);

        // Install now -> broadcast the intent to install!
        findViewById(R.id.now).setOnClickListener(
            new View.OnClickListener() {
                public void onClick(View v) {
                    Log.i(TAG, "OTA update accepted by user!");
                    Checkin.logEvent(getContentResolver(),
                            Checkin.Events.Tag.FOTA_PROMPT_ACCEPT, null);
                    installUpdate();
                }
            });

        // Install later -> just go away, the alarm will trigger again.
        findViewById(R.id.later).setOnClickListener(
            new View.OnClickListener() {
                public void onClick(View v) {
                    Log.i(TAG, "OTA update dismissed by user");
                    finish();
                }
            });
    }

    @Override
    public void onStart() {
        super.onStart();
        mActivityStarted = this;

        onNewIntent(getIntent());
        if (isFinishing()) return;  // onNewIntent() can do this

        if (inPhoneCall()) {
            Log.i(TAG, "OTA update prompt postponed by phone call");
            Checkin.logEvent(getContentResolver(),
                    Checkin.Events.Tag.FOTA_PROMPT_SKIPPED, null);
            finish();
            return;
        }

        Checkin.logEvent(getContentResolver(),
                Checkin.Events.Tag.FOTA_PROMPT, null);
    }

    @Override
    public void onResume() {
        super.onResume();

        // The server can override the default text in the resource.
        CharSequence message = getIntent().getStringExtra("promptMessage");
        if (message == null) message = getText(R.string.message);
        ((TextView) findViewById(R.id.message)).setText(message);

        if (mInstallIntent != null) {
            long delay = getPromptTimeout(getIntent());
            if (delay > 0) {
                Log.i(TAG, "OTA prompt timeout in " + delay / 1000 + " sec");
                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay, mInstallIntent);
            }
        }
    }

    @Override
    public void onPause() {
        if (mInstallIntent != null) {
            mAlarmManager.cancel(mInstallIntent);
            if (isFinishing()) {
                Log.i(TAG, "OTA update prompt dismissed");
                Checkin.logEvent(getContentResolver(),
                        Checkin.Events.Tag.FOTA_PROMPT_REJECT, null);
            }
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        if (mActivityStarted == this) mActivityStarted = null;
        super.onStop();
    }

    @Override
    public void onNewIntent(Intent intent) {
        // This activity is launched with SINGLE_TOP_LAUNCH, so this happens
        // if the file being downloaded changes, or if the next alarm
        // goes off while the prompt is still displayed.  We also call
        // this function directly from onStart() so all Intents come here.
        // Regardless, this is only called with the activity paused.

        super.onNewIntent(intent);
        setIntent(intent);

        // This Extra must always be set by the caller.
        File update = new File(intent.getStringExtra("updateFile"));
        if (!update.exists()) {
            // The update has gone away, maybe replaced by something newer?
            Log.i(TAG, "OTA update no longer exists: " + update);
            finish();
            return;
        }

        Log.i(TAG, "OTA update available: " + update);
        Uri uri = Uri.fromFile(update);
        Intent install = new Intent("android.server.checkin.FOTA_INSTALL", uri);
        mInstallIntent = PendingIntent.getBroadcast(this, 0, install, 0);

        long now = SystemClock.elapsedRealtime();
        long nextPrompt = getNextPromptTime(intent, now);
        if (nextPrompt == 0) {
            Log.i(TAG, "Installing overdue OTA update without prompting");
            installUpdate();
            return;
        }

        // Use FLAG_CANCEL_CURRENT because the Intent differs only in extras.
        Log.i(TAG, "Next OTA prompt in " + (nextPrompt - now) / 1000 + " sec");
        mAlarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, nextPrompt,
                PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_CANCEL_CURRENT));
    }
}
