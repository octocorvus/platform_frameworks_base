package com.android.server.clipboard;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.settings.app.AswAllowClipboardRead;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

class ClipboardHooks {
    private final Context mContext;
    private final PackageManagerInternal mPmi;

    ClipboardHooks(@NonNull Context context) {
        mContext = context;
        mPmi = LocalServices.getService(PackageManagerInternal.class);
    }

    @GuardedBy("ClipboardService.mLock")
    boolean canPackageReadClipboardLocked(String packageName, int intendingUid, int intendingUserId,
            ClipboardService.Clipboard clipboard) {
        if (clipboard == null) {
            return false;
        }

        if (intendingUid == clipboard.primaryClipUid) {
            return true;
        }

        return canPackageReadClipboard(packageName, intendingUid, intendingUserId);
    }

    boolean canPackageReadClipboard(String packageName, int intendingUid, int intendingUserId) {
        if (!checkPackage(packageName, intendingUid, intendingUserId)) {
            return false;
        }
        final ApplicationInfo applicationInfo = mPmi.getApplicationInfo(packageName,
                /* flags = */ 0, intendingUid, intendingUserId);
        if (applicationInfo == null) {
            return false;
        }
        final GosPackageState gosPackageState = mPmi.getGosPackageState(packageName,
                intendingUserId);
        return AswAllowClipboardRead.I.get(mContext, intendingUserId, applicationInfo,
                gosPackageState);
    }

    private boolean checkPackage(String packageName, int intendingUid, int intendingUserId) {
        final int expectedUid = mPmi.getPackageUid(packageName, /* flags = */ 0, intendingUserId);
        return expectedUid >= 0 && expectedUid == intendingUid;
    }
}
