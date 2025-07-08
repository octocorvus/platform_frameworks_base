package com.google.android.systemui.power;

import static android.content.Context.MODE_PRIVATE;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.Html;
import android.util.Log;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;

import androidx.core.app.NotificationCompat;

import java.time.Clock;
import java.time.Duration;

/**
 * Based on SystemUIGoogle
 */
public final class BatteryReplacementNotification {
    private static final String TAG = "BatteryReplacementNotification";
    private static final int BLUEJAY_CYCLE_COUNT_EARLY_RECOMMENDATION_THRESHOLD = 375;
    private static final int MAX_EARLY_RECOMMENDATION_TRIGGER_NOTIFICATIONS = 1;
    private static final int MAX_RECOMMENDATION_NOTIFICATIONS = 3;
    private static final long RECOMMENDATION_INTERVAL_MS = Duration.ofDays(14).toMillis();

    /**
     * An undocumented constant value for {@link BatteryManager#EXTRA_HEALTH}.
     * It's defined in
     * frameworks/native/services/batteryservice/include/batteryservice/BatteryServiceConstants.h
     * but not exposed in frameworks {@link android.os.BatteryManager}
     */
    private static final int BATTERY_HEALTH_FAIR = 8;

    private static final String NOTIFICATION_TAG = "battery_replacement";
    public static final String PREF_EARLY_RECOMMENDATION_TRIGGER_TIMES_KEY =
            "early_recommendation_trigger_times";
    public static final String PREF_RECOMMENDATION_TRIGGER_TIMES_KEY =
            "recommendation_trigger_times";
    public static final String PREF_LAST_RECOMMENDATION_TIMESTAMP_MS_KEY =
            "last_recommendation_timestamp_ms";
    public static final String BATTERY_REPLACEMENT_SHARED_PREFS_NAME =
            "battery_replacement_shared_prefs";
    private final Clock mClock;
    private final Context mContext;
    private SharedPreferences mSharedPreferences;
    private long mLastRecommendationTimestampMs = -1;
    private int mRecommendationTriggerTimes = -1;
    private int mEarlyRecommendationTriggerTimes = -1;

    public BatteryReplacementNotification(Context context, Clock clock) {
        this.mContext = context;
        this.mClock = clock;
    }

    private SharedPreferences getSharedPreferences() {
        if (this.mSharedPreferences == null) {
            this.mSharedPreferences = this.mContext.getApplicationContext()
                    .getSharedPreferences(BATTERY_REPLACEMENT_SHARED_PREFS_NAME, MODE_PRIVATE);
        }
        return this.mSharedPreferences;
    }

    private PendingIntent createBatterySettingsPendingIntentAsUser() {
        return PendingIntent.getActivityAsUser(
                mContext, 0, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
    }

    private PendingIntent createHelpArticlePendingIntentAsUser(int urlResId) {
        return PendingIntent.getActivityAsUser(mContext, 0,
                new Intent(Intent.ACTION_VIEW, Uri.parse(mContext.getString(urlResId))),
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
    }

    private void overrideNotificationAppName(NotificationCompat.Builder builder) {
        Bundle bundle = new Bundle(1);
        // Replace "System UI" app name with "Android System"
        bundle.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                mContext.getString(com.android.internal.R.string.android_system_label));
        builder.addExtras(bundle);
    }
    
    public void onBatteryInfoChanged(int batteryHealth, int cycleCount) {
        // Log.w(TAG, "batteryHealth " + batteryHealth + ", cycleCount " + cycleCount);
        if (batteryHealth == BatteryManager.BATTERY_HEALTH_DEAD) {
            if (mRecommendationTriggerTimes == -1 || mLastRecommendationTimestampMs == -1) {
                SharedPreferences sharedPreferences = getSharedPreferences();
                mRecommendationTriggerTimes = sharedPreferences.getInt(
                        PREF_RECOMMENDATION_TRIGGER_TIMES_KEY, 0);
                mLastRecommendationTimestampMs = sharedPreferences.getLong(
                        PREF_LAST_RECOMMENDATION_TIMESTAMP_MS_KEY, 0L);
            }
            long millis = mClock.millis();
            if (mRecommendationTriggerTimes < MAX_RECOMMENDATION_NOTIFICATIONS &&
                    millis - mLastRecommendationTimestampMs > RECOMMENDATION_INTERVAL_MS) {
                Log.w(TAG, "recommendation, count: " + mRecommendationTriggerTimes
                        + ", last: " + mLastRecommendationTimestampMs);
                sendNotification(mContext.getString(R.string.battery_replacement_notify_title),
                        Html.fromHtml(
                                mContext.getString(R.string.battery_replacement_notify_des), 0),
                        false);

                mRecommendationTriggerTimes = mRecommendationTriggerTimes + 1;
                mLastRecommendationTimestampMs = millis;
                getSharedPreferences()
                        .edit()
                        .putInt(PREF_RECOMMENDATION_TRIGGER_TIMES_KEY,
                                mRecommendationTriggerTimes)
                        .putLong(PREF_LAST_RECOMMENDATION_TIMESTAMP_MS_KEY,
                                mLastRecommendationTimestampMs)
                        .apply();
            }
        } else if (batteryHealth == BATTERY_HEALTH_FAIR &&
                cycleCount >= BLUEJAY_CYCLE_COUNT_EARLY_RECOMMENDATION_THRESHOLD) {
            if (mEarlyRecommendationTriggerTimes == -1) {
                mEarlyRecommendationTriggerTimes = getSharedPreferences()
                        .getInt(PREF_EARLY_RECOMMENDATION_TRIGGER_TIMES_KEY, 0);
            }
            if (mEarlyRecommendationTriggerTimes < MAX_EARLY_RECOMMENDATION_TRIGGER_NOTIFICATIONS) {
                Log.w(TAG, "early recommendation, count: " + mEarlyRecommendationTriggerTimes);
                sendNotification(
                        mContext.getString(R.string.battery_replacement_notify_title),
                        Html.fromHtml(
                                mContext.getString(R.string.battery_replacement_early_notify_des),
                                0),
                        true);

                mEarlyRecommendationTriggerTimes = mEarlyRecommendationTriggerTimes + 1;
                getSharedPreferences()
                        .edit()
                        .putInt(
                                PREF_EARLY_RECOMMENDATION_TRIGGER_TIMES_KEY,
                                mEarlyRecommendationTriggerTimes
                        ).apply();
            }
        }
    }

    private void sendNotification(String title, CharSequence contentText,
            boolean addBatteryManagementAction) {
        NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);
        var builder = new NotificationCompat.Builder(mContext, NotificationChannels.BATTERY);
        builder.setSmallIcon(R.drawable.ic_battery_alert_fill);
        builder.setColor(Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorError));
        builder.setContentTitle(title);
        builder.setContentText(contentText);
        builder.setContentIntent(createBatterySettingsPendingIntentAsUser());
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText));
        builder.setSilent(true);
        builder.addAction(
                0,
                mContext.getString(R.string.battery_health_notify_learn_more),
                createHelpArticlePendingIntentAsUser(R.string.battery_replacement_notify_help_url));
        if (addBatteryManagementAction) {
            builder.addAction(
                    0,
                    mContext.getString(R.string.battery_management_action),
                    createBatterySettingsPendingIntentAsUser());
        }
        overrideNotificationAppName(builder);
        notificationManager.notifyAsUser(NOTIFICATION_TAG,
                R.string.battery_replacement_notify_title, builder.build(), UserHandle.CURRENT);
    }
}
