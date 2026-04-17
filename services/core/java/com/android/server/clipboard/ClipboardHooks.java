package com.android.server.clipboard;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.settings.app.AswAllowClipboardRead;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

class ClipboardHooks {
    static final long PENDING_PASTE_TIMEOUT_MILLIS = 1000L;

    private final Context mContext;
    private final PackageManagerInternal mPmi;

    @GuardedBy("ClipboardService.mLock")
    private final SparseArray<PendingPaste> mPendingPasteActions = new SparseArray<>();

    ClipboardHooks(@NonNull Context context) {
        mContext = context;
        mPmi = LocalServices.getService(PackageManagerInternal.class);
    }

    @GuardedBy("ClipboardService.mLock")
    void onUserAuthorizedClipAccessLocked(int intendingUid, int intendingDeviceId,
            long elapsedRealtime, ClipboardService.Clipboard clipboard) {
        if (clipboard != null) {
            PendingPaste pendingPaste = new PendingPaste(intendingDeviceId,
                    elapsedRealtime + PENDING_PASTE_TIMEOUT_MILLIS, clipboard.generation);
            mPendingPasteActions.put(intendingUid, pendingPaste);
        }
    }

    @GuardedBy("ClipboardService.mLock")
    void pruneExpiredPasteActionsLocked(long elapsedRealtime) {
        for (int i = mPendingPasteActions.size() - 1; i >= 0; i--) {
            if (mPendingPasteActions.valueAt(i).expiry < elapsedRealtime) {
                mPendingPasteActions.removeAt(i);
            }
        }
    }

    @GuardedBy("ClipboardService.mLock")
    void onSystemSelectionToolbarClientUidDiedLocked(int uid) {
        mPendingPasteActions.remove(uid);
    }

    /**
     * Call only after {@link ClipboardService#clipboardAccessAllowed} returns true.
     */
    @GuardedBy("ClipboardService.mLock")
    boolean isClipboardReadAllowedLocked(String packageName, int intendingUid, int intendingUserId,
            long elapsedRealtime, ClipboardService.Clipboard clipboard) {
        if (clipboard == null) {
            return false;
        }

        if (intendingUid == clipboard.primaryClipUid) {
            return true;
        }

        // Some apps may read the clipboard multiple times as part of a single paste flow. Do not
        // consume the pending paste action after the first read; let it remain valid until it
        // expires.
        PendingPaste pendingPaste = mPendingPasteActions.get(intendingUid);
        if (pendingPaste != null && pendingPaste.deviceId() == clipboard.deviceId
                && pendingPaste.expiry() >= elapsedRealtime
                && pendingPaste.generation() == clipboard.generation) {
            return true;
        }

        return isClipboardReadAllowedForPackage(packageName, intendingUid, intendingUserId);
    }

    /**
     * Call only after {@link ClipboardService#clipboardAccessAllowed} returns true.
     */
    boolean isClipboardReadAllowedForPackage(String packageName, int intendingUid,
            int intendingUserId) {
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

    record PendingPaste(int deviceId, long expiry, int generation) {
    }
}
