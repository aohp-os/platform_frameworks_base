/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.internal.app;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.aohp.IAohpSecurityBridge;

/**
 * System consent dialog for AOHP sensitive inject / tap actions (no biometrics).
 *
 * <p>Started from {@code system_server} on the main looper when policy returns
 * {@code CONSENT_REQUIRED}. Completes consent via {@link IAohpSecurityBridge#completeConsent}.
 */
public final class AohpConsentActivity extends AlertActivity {
    private static final String TAG = "AohpConsentActivity";

    static final String EXTRA_CONSENT_ID = "com.android.internal.aohp.extra.CONSENT_ID";
    static final String EXTRA_REASON = "com.android.internal.aohp.extra.REASON";
    static final String EXTRA_FOREGROUND_PKG = "com.android.internal.aohp.extra.FOREGROUND_PKG";
    static final String EXTRA_RESOURCE_ID = "com.android.internal.aohp.extra.RESOURCE_ID";

    private String mConsentId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        mConsentId = intent.getStringExtra(EXTRA_CONSENT_ID);
        if (TextUtils.isEmpty(mConsentId)) {
            Log.wtf(TAG, "Missing consent id");
            finish();
            return;
        }

        String reason = emptyDash(intent.getStringExtra(EXTRA_REASON));
        String pkg = emptyDash(intent.getStringExtra(EXTRA_FOREGROUND_PKG));
        String rid = emptyDash(intent.getStringExtra(EXTRA_RESOURCE_ID));

        Resources res = getResources();
        int titleId = res.getIdentifier("aohp_consent_title", "string", "android");
        int msgId = res.getIdentifier("aohp_consent_message", "string", "android");
        if (titleId == 0 || msgId == 0) {
            Log.wtf(TAG, "Missing AOHP consent string resources");
            finish();
            return;
        }
        mAlertParams.mTitle = getText(titleId);
        mAlertParams.mMessage = getString(msgId, reason, pkg, rid);

        mAlertParams.mPositiveButtonText = getText(android.R.string.ok);
        mAlertParams.mPositiveButtonListener = (d, w) -> finishConsent(true);

        mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
        mAlertParams.mNegativeButtonListener = (d, w) -> finishConsent(false);

        setupAlert();
    }

    private static String emptyDash(String s) {
        return TextUtils.isEmpty(s) ? "-" : s;
    }

    private void finishConsent(boolean approved) {
        IBinder b = ServiceManager.getService("aohp_security_bridge");
        if (b != null) {
            try {
                IAohpSecurityBridge bridge = IAohpSecurityBridge.Stub.asInterface(b);
                bridge.completeConsent(mConsentId, approved);
            } catch (RemoteException e) {
                Log.e(TAG, "completeConsent failed", e);
            }
        } else {
            Log.e(TAG, "aohp_security_bridge service missing");
        }
        finish();
    }

    /** Factory for {@link com.android.server.aohp.AohpSecurityBridgeService}. */
    public static Intent createIntent(
            String consentId, String reason, String foregroundPkg, String resourceId) {
        return new Intent()
                .setClassName("android", AohpConsentActivity.class.getName())
                .putExtra(EXTRA_CONSENT_ID, consentId)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_FOREGROUND_PKG, foregroundPkg)
                .putExtra(EXTRA_RESOURCE_ID, resourceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
