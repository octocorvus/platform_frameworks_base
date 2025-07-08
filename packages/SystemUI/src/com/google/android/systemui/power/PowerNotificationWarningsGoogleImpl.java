package com.google.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.UserHandle;
import android.util.Log;

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

public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {
    private static final String TAG = "PNWGoogleImpl";

    private final Context mContext;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final Handler mMainHandler;

    private final Receiver mReceiver;

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
            mReceiver.init();
        });
    }

    private final class Receiver extends BroadcastReceiver {

        public void init() {
            final var intentFilter = new IntentFilter();
            mBroadcastDispatcher.registerReceiver(
                    this, intentFilter, new HandlerExecutor(mMainHandler),
                    UserHandle.ALL, Context.RECEIVER_EXPORTED);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String action = intent.getAction();
            if (action == null) return;
            Log.d(TAG, "onReceive: " + action);
        }
    }
}
