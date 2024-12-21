package com.android.server.biometrics.sensors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.hardware.fingerprint.FingerprintManager;
import android.security.KeyStoreAuthorization;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class BiometricAuthTokenStoreTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private KeyStoreAuthorization mKeyStoreAuthorization;

    private BiometricAuthTokenStore mBiometricAuthTokenStore;
    private int mUserId;
    private byte[] mAuthToken;

    @Before
    public void setUp() {
        mUserId = 0;
        mAuthToken =  "Mock Auth Token".getBytes();
        mBiometricAuthTokenStore = new BiometricAuthTokenStore(mKeyStoreAuthorization);
    }

    @Test
    public void storePendingAuthToken_OnSuccess_StoresToken() {
        assertNull(mBiometricAuthTokenStore.getPendingAuthToken(mUserId));
        mBiometricAuthTokenStore.storePendingAuthToken(mUserId, mAuthToken);
        assertEquals(mAuthToken, mBiometricAuthTokenStore.getPendingAuthToken(mUserId));
    }

    @Test
    public void addPendingAuthTokenToKeyStore_WithPendingAuthToken_AddsTokenToKeyStore() {
        mBiometricAuthTokenStore.storePendingAuthToken(mUserId, mAuthToken);

        // Return success.
        when(mKeyStoreAuthorization.addAuthToken(mAuthToken)).thenReturn(0);

        assertEquals(mBiometricAuthTokenStore.addPendingAuthTokenToKeyStore(mUserId),
                FingerprintManager.SUCCESS);

        // Verify token removed from pending store.
        assertNull(mBiometricAuthTokenStore.getPendingAuthToken(mUserId));
    }

    @Test
    public void addPendingAuthTokenToKeyStore_WithInvalidPendingAuthToken_ReturnsFalse() {
        mBiometricAuthTokenStore.storePendingAuthToken(mUserId, mAuthToken);

        // Return not success.
        when(mKeyStoreAuthorization.addAuthToken(mAuthToken)).thenReturn(1);

        assertEquals(mBiometricAuthTokenStore.addPendingAuthTokenToKeyStore(mUserId),
                FingerprintManager.ERROR_UNABLE_TO_ADD_AUTH_TOKEN_TO_KEYSTORE);

        // Verify token removed from pending store.
        assertNull(mBiometricAuthTokenStore.getPendingAuthToken(mUserId));
    }

    @Test
    public void addPendingAuthTokenToKeyStore_WithoutPendingAuthToken_ReturnsFalse() {
        // Verify no token in store.
        assertNull(mBiometricAuthTokenStore.getPendingAuthToken(mUserId));

        assertEquals(mBiometricAuthTokenStore.addPendingAuthTokenToKeyStore(mUserId),
                FingerprintManager.ERROR_NO_PENDING_AUTH_TOKEN);
    }

    @Test
    public void clearPendingAuthTokens_Success_ClearsTokens() {
        mBiometricAuthTokenStore.storePendingAuthToken(mUserId, mAuthToken);

        mBiometricAuthTokenStore.clearPendingAuthTokens();

        assertNull(mBiometricAuthTokenStore.getPendingAuthToken(mUserId));
    }
}
