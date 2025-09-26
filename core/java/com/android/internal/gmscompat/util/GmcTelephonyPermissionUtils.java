package com.android.internal.gmscompat.util;

import android.app.compat.gms.GmsCompat;
import android.content.Context;
import android.content.pm.PackageManager;
import android.ext.settings.ExtSettings;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.Log;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GmcTelephonyPermissionUtils {

    private static final String TAG = GmcTelephonyPermissionUtils.class.getSimpleName();

    private static final String PACKAGE_GMS_CORE = "com.google.android.gms";
    private static final String PACKAGE_GOOGLE_MESSAGES = "com.google.android.apps.messaging";

    private static final Map<String, List<byte[]>> CERT_DIGESTS = Map.ofEntries(
            Map.entry(PACKAGE_GMS_CORE, List.of(
                    HexFormat.of().parseHex("7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"),
                    HexFormat.of().parseHex("5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"))),
            Map.entry(PACKAGE_GOOGLE_MESSAGES, List.of(
                    HexFormat.of().parseHex("f7e73d4edc2b7f55eecd3f898d3cca7da9cbbf51694eba14f816e2eccf015035"))));

    public enum DeviceIdentifier {
        IMEI,
        PHONE_NUMBER,
        SUBSCRIBER_ID,
        SUBSCRIPTION_INFO,
    }

    private static final Map<String, Set<DeviceIdentifier>> DEFAULT_ACCESS_CONFIG =
            Map.ofEntries(
                    Map.entry(PACKAGE_GMS_CORE, Set.of(
                            DeviceIdentifier.IMEI,
                            DeviceIdentifier.PHONE_NUMBER,
                            DeviceIdentifier.SUBSCRIBER_ID)),
                    Map.entry(PACKAGE_GOOGLE_MESSAGES, Set.of(
                            DeviceIdentifier.SUBSCRIPTION_INFO)));
    private static final AtomicReference<Map<String, Set<DeviceIdentifier>>> sAccessConfigCache =
            new AtomicReference<>(DEFAULT_ACCESS_CONFIG);

    private static final String DEBUG_ACCESS_CONFIG_NAMESPACE =
            "gmc_device_identifiers_debug_access_config";
    private static final Executor DEBUG_ACCESS_CONFIG_EXECUTOR = Runnable::run;
    private static final AtomicBoolean isDebugAccessConfigInitialized = new AtomicBoolean();

    private GmcTelephonyPermissionUtils() {}

    public static boolean checkGoogleAppsSpecialReadAccess(Context context, int callingUid,
            DeviceIdentifier identifier) {
        if (!ExtSettings.ALLOW_GOOGLE_APPS_SPECIAL_ACCESS_TO_DEVICE_IDENTIFIERS.get(context)) {
            logd("setting disabled, denying " + callingUid, new Throwable());
            return false;
        }

        final var um = Objects.requireNonNull(context.getSystemService(UserManager.class));
        final int mainUserId = Objects.requireNonNull(um.getMainUser()).getIdentifier();
        final int packageUserId = UserHandle.getUserId(callingUid);
        if (mainUserId == UserHandle.USER_NULL || packageUserId != mainUserId) {
            logd("no access in user " + packageUserId, new Throwable());
            return false;
        }

        final PackageManager pm = context.getPackageManager();

        final String[] packages = pm.getPackagesForUid(callingUid);
        if (packages == null) {
            logd("package list for uid " + callingUid + " is null", new Throwable());
            return false;
        }

        String googlePackage = null;
        for (String pkg : packages) {
            if (getAccessConfig().containsKey(pkg)) {
                googlePackage = pkg;
                break;
            }
        }
        if (googlePackage == null) {
            logd("no known package for uid " + callingUid, new Throwable());
            return false;
        }

        final Set<DeviceIdentifier> allowedIdentifiers = getAccessConfig().get(googlePackage);
        if (allowedIdentifiers == null || !allowedIdentifiers.contains(identifier)) {
            logd("package " + googlePackage + " with uid " + callingUid
                    + " does not have access to identifier " + identifier, new Throwable());
            return  false;
        }

        final List<byte[]> validCertDigests = CERT_DIGESTS.get(googlePackage);
        if (validCertDigests == null) {
            logd("no cert digests for " + googlePackage, new Throwable());
            return false;
        }
        boolean isValidSigner = false;
        for (byte[] digest : validCertDigests) {
            if (pm.hasSigningCertificate(callingUid, digest, PackageManager.CERT_INPUT_SHA256)) {
                logd("matching hex digest " + HexFormat.of().formatHex(digest)
                        + " for package " + googlePackage + " with uid " + callingUid);
                isValidSigner = true;
                break;
            }
        }
        if (!isValidSigner) {
            throw new SecurityException(
                    "google package " + googlePackage + " has invalid signature");
        }

        logd("allowing package " + googlePackage + " with uid " + callingUid
                + " to access " + identifier, new Throwable());
        return true;
    }

    private static Map<String, Set<DeviceIdentifier>> getAccessConfig() {
        if (GmsCompat.isDevBuild()) {
            initDebugAccessConfigCache();
        }
        return sAccessConfigCache.get();
    }

    private static void initDebugAccessConfigCache() {
        if (!isDebugAccessConfigInitialized.compareAndSet(false, true)) {
            return;
        }

        sAccessConfigCache.set(loadAccessConfigFromDeviceConfig());

        DeviceConfig.addOnPropertiesChangedListener(DEBUG_ACCESS_CONFIG_NAMESPACE,
                DEBUG_ACCESS_CONFIG_EXECUTOR,
                properties -> sAccessConfigCache.set(loadAccessConfigFromDeviceConfig()));
    }

    private static Map<String, Set<DeviceIdentifier>> loadAccessConfigFromDeviceConfig() {
        Map<String, Set<DeviceIdentifier>> map = new HashMap<>(DEFAULT_ACCESS_CONFIG);
        for (String pkg : DEFAULT_ACCESS_CONFIG.keySet()) {
            String raw = DeviceConfig.getProperty(DEBUG_ACCESS_CONFIG_NAMESPACE, pkg);
            if (raw != null) {
                map.put(pkg, parseIdentifiers(raw));
            }
        }
        logd("loadAccessConfigFromDeviceConfig: parsed config: " + map);
        return Collections.unmodifiableMap(map);
    }

    private static Set<DeviceIdentifier> parseIdentifiers(String csv) {
        var set = EnumSet.noneOf(DeviceIdentifier.class);
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                set.add(DeviceIdentifier.valueOf(t));
            } catch (IllegalArgumentException e) {
                logd("parseIdentifiers: skipping invalid token: " + t);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private static void logd(String msg) {
        if (GmsCompat.isDevBuild()) {
            Log.d(TAG, msg);
        }
    }

    private static void logd(String msg, Throwable tr) {
        if (GmsCompat.isDevBuild()) {
            Log.d(TAG, msg, tr);
        }
    }
}
