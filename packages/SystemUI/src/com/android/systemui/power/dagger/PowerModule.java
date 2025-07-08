/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.power.dagger;

import android.content.Context;
import android.ext.power.BatteryChargeLimit;
import android.os.Handler;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.CoreStartable;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.EnhancedEstimatesImpl;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.power.PowerUI;
import com.android.systemui.power.data.repository.PowerRepositoryModule;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import com.google.android.systemui.power.PowerNotificationWarningsGoogleImpl;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

/** Dagger Module for code in the power package. */
@Module(
        includes = {
                PowerRepositoryModule.class,
        }
)
public interface PowerModule {
    /** Starts PowerUI.  */
    @Binds
    @IntoMap
    @ClassKey(PowerUI.class)
    CoreStartable bindPowerUIStartable(PowerUI impl);

    /** Listen to config changes for PowerUI.  */
    @Binds
    @IntoSet
    ConfigurationController.ConfigurationListener bindPowerUIConfigChanges(PowerUI impl);

    /** */
    @Binds
    EnhancedEstimates bindEnhancedEstimates(EnhancedEstimatesImpl enhancedEstimates);

    /** */
    @Binds
    PowerUI.WarningsUI provideWarningsUi(PowerNotificationWarnings controllerImpl);

    @Provides
    @SysUISingleton
    static PowerNotificationWarnings providePowerNotificationWarnings(
            Context context,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            BroadcastDispatcher broadcastDispatcher,
            @Main Handler mainHandler) {
        PowerNotificationWarnings pNW;
        // maybe can refactor this check into more generic class instead of "BatteryChargeLimit",
        // but doesn't matter
        if (BatteryChargeLimit.isGoogleDevice()) {
            pNW = new PowerNotificationWarningsGoogleImpl(
                    context,
                    activityStarter,
                    broadcastSender,
                    batteryControllerLazy,
                    dialogTransitionAnimator,
                    uiEventLogger,
                    userTracker,
                    systemUIDialogFactory,
                    broadcastDispatcher,
                    mainHandler
            );
        } else {
            pNW = new PowerNotificationWarnings(
                    context,
                    activityStarter,
                    broadcastSender,
                    batteryControllerLazy,
                    dialogTransitionAnimator,
                    uiEventLogger,
                    userTracker,
                    systemUIDialogFactory
            );
        }
        return pNW;
    }
}
