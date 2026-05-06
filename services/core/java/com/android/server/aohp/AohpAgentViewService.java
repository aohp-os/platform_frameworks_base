/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;
import android.window.ScreenCaptureInternal;

import com.android.internal.aohp.IAohpAgentView;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binder service: privileged display screenshot for AOHP agent (full display or cropped region).
 */
public final class AohpAgentViewService extends IAohpAgentView.Stub {
    private static final String TAG = "AohpAgentViewService";
    public static final String SERVICE_NAME = "aohp_agent_view";
    private static final int CAPTURE_TIMEOUT_SEC = 5;

    private final Context mContext;

    public AohpAgentViewService(Context context) {
        mContext = context;
    }

    private void enforceAohpPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    @Override
    public byte[] captureDisplay(int displayId, int quality) throws RemoteException {
        enforceAohpPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return captureDisplayInternal(displayId, null, quality);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public byte[] captureRegion(int displayId, int left, int top, int right, int bottom,
            int quality) throws RemoteException {
        enforceAohpPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            Rect crop = new Rect(left, top, right, bottom);
            if (crop.width() <= 0 || crop.height() <= 0) {
                Slog.w(TAG, "Invalid crop rect");
                return null;
            }
            return captureDisplayInternal(displayId, crop, quality);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private byte[] captureDisplayInternal(int displayId, Rect sourceCrop, int quality) {
        WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
        if (wmi == null) {
            Slog.e(TAG, "WindowManagerInternal not available");
            return null;
        }
        ScreenCaptureInternal.CaptureArgs captureArgs = null;
        if (sourceCrop != null && !sourceCrop.isEmpty()) {
            captureArgs = new ScreenCaptureInternal.CaptureArgs.Builder<>()
                    .setSourceCrop(sourceCrop)
                    .build();
        }
        AtomicReference<ScreenCaptureInternal.ScreenshotHardwareBuffer> bufferRef =
                new AtomicReference<>();
        AtomicInteger statusRef = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        ScreenCaptureInternal.ScreenCaptureListener listener =
                new ScreenCaptureInternal.ScreenCaptureListener((buffer, status) -> {
                    statusRef.set(status);
                    bufferRef.set(buffer);
                    latch.countDown();
                });
        wmi.captureDisplay(displayId, captureArgs, listener);
        try {
            if (!latch.await(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Slog.w(TAG, "captureDisplay timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (statusRef.get() != 0) {
            Slog.w(TAG, "captureDisplay failed status=" + statusRef.get());
            return null;
        }
        ScreenCaptureInternal.ScreenshotHardwareBuffer shb = bufferRef.get();
        if (shb == null) {
            return null;
        }
        Bitmap hwBitmap = shb.asBitmap();
        if (hwBitmap == null) {
            return null;
        }
        Bitmap swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
        hwBitmap.recycle();
        try {
            return bitmapToBytes(swBitmap, quality);
        } finally {
            swBitmap.recycle();
        }
    }

    private static byte[] bitmapToBytes(Bitmap bitmap, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (quality > 0) {
            int q = Math.min(100, Math.max(1, quality));
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        }
        return baos.toByteArray();
    }
}
