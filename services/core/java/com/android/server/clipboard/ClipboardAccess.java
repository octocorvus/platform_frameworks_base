package com.android.server.clipboard;

import static android.content.Context.DEVICE_ID_INVALID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.UidObserver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.settings.app.AswAllowClipboardRead;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.clipboard.ClipboardManagerInternal.PasteGrant;
import com.android.server.clipboard.ClipboardService.Clipboard;

class ClipboardAccess {
    private static final String TAG = "ClipboardAccess";

    // Arbitrary timeout for clipboard access after a trusted paste trigger
    private static final long PASTE_GRANT_TIMEOUT_MILLIS = 1000L;

    private final Context mContext;
    private final Injector mInjector;
    private final PackageManagerInternal mPmi;
    private final Handler mWorkerHandler;

    // Lock must be the one used by ClipboardService
    private final Object mLock;

    @GuardedBy("mLock")
    private final SparseArray<PasteGrantRecord> mPasteGrantsByUid = new SparseArray<>();

    ClipboardAccess(@NonNull Context context, @NonNull Handler workerHandler, @NonNull Object lock,
            @NonNull Injector injector) {
        mContext = context;
        mLock = lock;
        mWorkerHandler = workerHandler;
        mInjector = injector;
        mPmi = LocalServices.getService(PackageManagerInternal.class);
        registerUidObserver();
    }

    @Nullable
    PasteGrant createPasteGrant(int intendingUid, int deviceId) {
        final long elapsedRealtime = SystemClock.elapsedRealtime();

        final int intendingUserId = UserHandle.getUserId(intendingUid);
        final int intendingDeviceId = mInjector.getIntendingDeviceId(deviceId, intendingUid);
        if (intendingDeviceId == DEVICE_ID_INVALID) {
            Slog.i(TAG, "createPasteGrant: invalid deviceId for uid:" + intendingUid + " deviceId:"
                    + deviceId);
            return null;
        }

        final PasteGrantRecord record;
        synchronized (mLock) {
            final Clipboard clipboard = mInjector.getClipboardLocked(intendingUserId,
                    intendingDeviceId);
            if (clipboard == null) {
                return null;
            }

            record = new PasteGrantRecord(intendingUid, intendingDeviceId, clipboard.generation,
                    elapsedRealtime + PASTE_GRANT_TIMEOUT_MILLIS);
            mPasteGrantsByUid.put(intendingUid, record);
        }

        mWorkerHandler.postDelayed(
                PooledLambda.obtainRunnable(ClipboardAccess::pruneExpiredPasteGrants, this),
                PASTE_GRANT_TIMEOUT_MILLIS + 1);

        return new PasteGrantImpl(record);
    }

    void revokePasteGrant(int intendingUid) {
        synchronized (mLock) {
            mPasteGrantsByUid.remove(intendingUid);
        }
    }

    void revokePasteGrant(@NonNull PasteGrantRecord record) {
        synchronized (mLock) {
            final PasteGrantRecord currentRecord = mPasteGrantsByUid.get(record.uid());
            if (currentRecord == record) {
                mPasteGrantsByUid.remove(record.uid());
            }
        }
    }

    private void pruneExpiredPasteGrants() {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            for (int i = mPasteGrantsByUid.size() - 1; i >= 0; i--) {
                if (mPasteGrantsByUid.valueAt(i).expiry < elapsedRealtime) {
                    mPasteGrantsByUid.removeAt(i);
                }
            }
        }
    }

    @GuardedBy("mLock")
    boolean clipboardReadAllowedLocked(String packageName, int intendingUid,
            int intendingUserId, int intendingDeviceId) {
        return clipboardReadAllowedByPasteGrantLocked(
                        intendingUid, intendingUserId, intendingDeviceId)
                || clipboardReadAllowedForPackage(packageName, intendingUid, intendingUserId);
    }

    @GuardedBy("mLock")
    boolean clipboardReadAllowedByPasteGrantLocked(int intendingUid, int intendingUserId,
            int intendingDeviceId) {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        Clipboard clipboard = mInjector.getClipboardLocked(intendingUserId, intendingDeviceId);
        if (clipboard == null) {
            return false;
        }
        final PasteGrantRecord record = mPasteGrantsByUid.get(intendingUid);
        return record != null
                && record.deviceId() == clipboard.deviceId
                && record.generation() == clipboard.generation
                && record.expiry() >= elapsedRealtime;
    }

    boolean clipboardReadAllowedForPackage(String packageName, int intendingUid,
            int intendingUserId) {
        final ApplicationInfo applicationInfo = mPmi.getApplicationInfo(packageName, /* flags = */0,
                intendingUid, intendingUserId);
        if (applicationInfo == null) {
            return false;
        }
        final GosPackageState gosPackageState = mPmi.getGosPackageState(packageName,
                intendingUserId);
        return AswAllowClipboardRead.I.get(mContext, intendingUserId, applicationInfo,
                gosPackageState);
    }

    private void registerUidObserver() {
        try {
            ActivityManager.getService().registerUidObserver(new UidObserver() {
                @Override
                public void onUidGone(int uid, boolean disabled) {
                    revokePasteGrant(uid);
                }
            }, ActivityManager.UID_OBSERVER_GONE, ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }
    }

    interface Injector {
        @Nullable
        Clipboard getClipboardLocked(int userId, int deviceId);

        int getIntendingDeviceId(int requestedDeviceId, int uid);
    }

    record PasteGrantRecord(int uid, int deviceId, int generation, long expiry) {
    }

    private final class PasteGrantImpl implements ClipboardManagerInternal.PasteGrant {
        private final PasteGrantRecord mRecord;

        private PasteGrantImpl(@NonNull PasteGrantRecord record) {
            mRecord = record;
        }

        @Override
        public void revoke() {
            revokePasteGrant(mRecord);
        }
    }
}
