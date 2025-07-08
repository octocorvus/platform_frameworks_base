package com.google.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;

import dagger.Lazy;

import java.time.Clock;

public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {
    private static final String TAG = "PowerNotifWrnGoogleImpl";

    private final Context mContext;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final Handler mMainHandler;

    private final Receiver mReceiver;

    @Nullable
    private BatteryReplacementNotification mBatteryReplacementNotification = null;

    public PowerNotificationWarningsGoogleImpl(Context context,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            BroadcastDispatcher broadcastDispatcher,
            Handler mainHandler) {
        super(context, activityStarter, broadcastSender, batteryControllerLazy,
                dialogTransitionAnimator, uiEventLogger, userTracker, systemUIDialogFactory);
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mMainHandler = mainHandler;
        mReceiver = new Receiver();
        mainHandler.post(() -> {
            if ("bluejay".equals(Build.DEVICE)) {
                Log.d(TAG, "enabling mBatteryReplacementNotification");
                mBatteryReplacementNotification = new BatteryReplacementNotification(mContext,
                        Clock.systemUTC());
                // TODO: If we end up porting more things from PowerNotificationWarningsGoogleImpl
                //  that are not device-specific, then take this init outside of this bluejay path.
                //  Currently, this is the only thing we're taking from stock OS, so it's fine to
                //  only init if on bluejay
                mReceiver.init();
            }
        });
    }

    private final class Receiver extends BroadcastReceiver {
        public void init() {
            Log.d(TAG, "init mReceiver");
            final var intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);

            // registerReceiverWithHandler is deprecated
            mBroadcastDispatcher.registerReceiver(
                    this, intentFilter, new HandlerExecutor(mMainHandler),
                    UserHandle.ALL, Context.RECEIVER_EXPORTED);
            // stock OS does this presumably to set the state ASAP
            var stickyIntent = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (stickyIntent != null) {
                onReceive(mContext, stickyIntent);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String action = intent.getAction();
            if (action == null) return;
            Log.d(TAG, "onReceive: " + action);

            // dropped the additional AND condition from stock OS:
            //      && mSecureSettings.getInt(0, "barrel_forcibly_disabled") == 0
            if (mBatteryReplacementNotification != null) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    int batteryHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 1);
                    int cycleCount = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
                    mBatteryReplacementNotification.onBatteryInfoChanged(batteryHealth, cycleCount);
                }
            }
        }
    }
}
