/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import static android.app.ActivityManager.isStartResultSuccessful;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.android.internal.aohp.IAohpVirtualDisplay;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.AohpVirtualDisplayPolicy;
import com.android.server.wm.SafeActivityOptions;

/**
 * Binder service: privileged virtual display input and launcher starts for AOHP agent.
 */
public final class AohpVirtualDisplayService extends IAohpVirtualDisplay.Stub {
    public static final String SERVICE_NAME = "aohp_virtual_display";
    private static final String TAG = "AohpVD";

    private static final float DEFAULT_SIZE = 1.0f;
    private static final float DEFAULT_PRESSURE = 1.0f;
    private static final int DEFAULT_META_STATE = 0;
    private static final int DEFAULT_EDGE_FLAGS = 0;

    // Small buffer count is sufficient: we only need to keep the Surface producing frames
    // so the display stays in STATE_ON. Acquired images are released immediately.
    private static final int IMAGE_READER_MAX_IMAGES = 3;

    /**
     * ImageReader-backed Surface for a virtual display must allow the GPU to render into it
     * and SurfaceFlinger/HWComposer to scan it out. Without GPU_COLOR_OUTPUT the composition
     * target is rejected (visible as EGL_BAD_MATCH / HWUI "Failed to set EGL_SWAP_BEHAVIOR")
     * and the display ends up with no frames, which in turn drops injected input as if the
     * display were off.
     */
    private static final long IMAGE_READER_USAGE =
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | HardwareBuffer.USAGE_COMPOSER_OVERLAY;

    private final Context mContext;
    private final InputManagerService mInputManager;
    private final ActivityTaskManagerService mAtm;

    /** Callback tokens for VD created via {@link #createVirtualDisplay} (needed for release). */
    private final SparseArray<IVirtualDisplayCallback> mAohpCreatedCallbacks = new SparseArray<>();

    /**
     * ImageReaders that back the Surface of each AOHP-owned virtual display. Without a
     * producer-attached Surface the VD stays in {@link Display#STATE_OFF}, which makes the
     * WindowManager attach a sleep token, the hosted activity stops and its window becomes
     * NOT_VISIBLE, so injected touches fall through as "no touchable window".
     */
    private final SparseArray<ImageReader> mAohpImageReaders = new SparseArray<>();

    /** Lazily started background thread used to drain frames from our ImageReaders. */
    private HandlerThread mReaderThread;
    private Handler mReaderHandler;

    public AohpVirtualDisplayService(Context context, InputManagerService inputManager,
            ActivityTaskManagerService atm) {
        mContext = context;
        mInputManager = inputManager;
        mAtm = atm;
    }

    private synchronized Handler getReaderHandlerLocked() {
        if (mReaderThread == null) {
            mReaderThread = new HandlerThread("AohpVdImageReader");
            mReaderThread.start();
            mReaderHandler = new Handler(mReaderThread.getLooper());
        }
        return mReaderHandler;
    }

    private void enforceAohpPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    private void verifyCallerRegistered(int displayId, int ownerUid) {
        if (!AohpVirtualDisplayPolicy.isDisplayRegisteredForOwner(displayId, ownerUid)) {
            throw new SecurityException("displayId/uid not registered for AOHP session");
        }
    }

    @Override
    public void registerSession(int displayId, int ownerUid, String ownerPackage) {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        if (uid != ownerUid) {
            throw new SecurityException("ownerUid must match caller");
        }
        AohpVirtualDisplayPolicy.registerSession(displayId, ownerUid, ownerPackage);
    }

    @Override
    public void unregisterSession() {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        if (uid != AohpVirtualDisplayPolicy.getOwnerUid()) {
            throw new SecurityException("not session owner");
        }
        AohpVirtualDisplayPolicy.unregisterSession();
    }

    @Override
    public void setFocusPackage(String packageName) {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        if (uid != AohpVirtualDisplayPolicy.getOwnerUid()) {
            throw new SecurityException("not session owner");
        }
        AohpVirtualDisplayPolicy.setFocusPackage(packageName);
    }

    @Override
    public boolean startLauncherOnDisplay(int displayId, String packageName) throws RemoteException {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        verifyCallerRegistered(displayId, uid);
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }

        ActivityTaskManagerInternal atm = LocalServices.getService(ActivityTaskManagerInternal.class);
        if (atm == null) {
            return false;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            Intent launchIntent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                return false;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            // Skip SafeActivityOptions launch-display uid check: app uid cannot launch on
            // FLAG_PRIVATE virtual displays; session is already gated by MANAGE_AOHP_VIRTUAL_DISPLAY.
            SafeActivityOptions safeOpts = new SafeActivityOptions(opts, -1, -1);

            int userId = ActivityManager.getCurrentUser();
            String callingPkg = AohpVirtualDisplayPolicy.getOwnerPackage();
            if (callingPkg == null || callingPkg.isEmpty()) {
                callingPkg = mContext.getPackageManager().getNameForUid(uid);
            }
            int result = atm.startActivityInPackage(
                    uid,
                    pid,
                    uid,
                    callingPkg,
                    null,
                    launchIntent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    safeOpts,
                    userId,
                    null,
                    "aohp-vd-start",
                    false,
                    null,
                    false);
            return isStartResultSuccessful(result);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean injectTap(int displayId, int x, int y) {
        enforceAohpPermission();
        verifyCallerRegistered(displayId, Binder.getCallingUid());
        final long now = SystemClock.uptimeMillis();
        return injectMotionDownUp(displayId, x, y, now);
    }

    @Override
    public boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
        enforceAohpPermission();
        verifyCallerRegistered(displayId, Binder.getCallingUid());
        final long down = SystemClock.uptimeMillis();
        final long duration = Math.max(1, durationMs);
        if (!injectMotionEvent(displayId, MotionEvent.ACTION_DOWN, down, down, x1, y1)) {
            return false;
        }
        final int steps = Math.min(20, Math.max(2, durationMs / 25));
        for (int i = 1; i < steps; i++) {
            long t = down + (duration * i) / steps;
            float a = (float) i / steps;
            float x = x1 + (x2 - x1) * a;
            float y = y1 + (y2 - y1) * a;
            if (!injectMotionEvent(displayId, MotionEvent.ACTION_MOVE, down, t, x, y)) {
                return false;
            }
        }
        long upTime = down + duration;
        return injectMotionEvent(displayId, MotionEvent.ACTION_UP, down, upTime, x2, y2);
    }

    @Override
    public boolean injectText(int displayId, String text) {
        enforceAohpPermission();
        verifyCallerRegistered(displayId, Binder.getCallingUid());
        if (text == null || text.isEmpty()) {
            return true;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            KeyEvent[] events = kcm.getEvents(text.toCharArray());
            if (events != null) {
                return injectKeyEventSequence(displayId, events);
            }
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                KeyEvent[] ev = kcm.getEvents(new char[]{ch});
                if (ev == null) {
                    ev = fallbackLatinKeyEvents(ch);
                }
                if (ev == null || !injectKeyEventSequence(displayId, ev)) {
                    return false;
                }
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Inject KeyCharacterMap events in order (each entry keeps its original action). */
    private boolean injectKeyEventSequence(int displayId, KeyEvent[] events) {
        for (KeyEvent ev : events) {
            KeyEvent e = new KeyEvent(ev.getDownTime(), ev.getEventTime(), ev.getAction(),
                    ev.getKeyCode(), ev.getRepeatCount(), ev.getMetaState(),
                    ev.getDeviceId(), ev.getScanCode(), ev.getFlags(), ev.getSource());
            e.setDisplayId(displayId);
            final boolean ok = mInputManager.injectInputEvent(
                    e, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            Slog.i(TAG, "injectKey: displayId=" + displayId
                    + " keyCode=" + e.getKeyCode()
                    + " action=" + KeyEvent.actionToString(e.getAction())
                    + " eventDisplayId=" + e.getDisplayId() + " result=" + ok);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** ASCII letters/digits when KeyCharacterMap.getEvents returns null. */
    private static KeyEvent[] fallbackLatinKeyEvents(char ch) {
        long now = SystemClock.uptimeMillis();
        int meta = 0;
        int keyCode;
        if (ch >= 'a' && ch <= 'z') {
            keyCode = KeyEvent.KEYCODE_A + (ch - 'a');
        } else if (ch >= 'A' && ch <= 'Z') {
            keyCode = KeyEvent.KEYCODE_A + (ch - 'A');
            meta = KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON;
        } else if (ch >= '0' && ch <= '9') {
            keyCode = KeyEvent.KEYCODE_0 + (ch - '0');
        } else {
            return null;
        }
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return new KeyEvent[]{down, up};
    }

    @Override
    public boolean injectKeyEvent(int displayId, int keyCode) {
        enforceAohpPermission();
        verifyCallerRegistered(displayId, Binder.getCallingUid());
        final long ident = Binder.clearCallingIdentity();
        try {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            down.setDisplayId(displayId);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            up.setDisplayId(displayId);
            return mInputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
                    && mInputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String getDisplayRuntimeSnapshotJson(int[] extraDisplayIds) throws RemoteException {
        enforceAohpPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mAtm.buildAohpDisplayRuntimeSnapshotJson(extraDisplayIds);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void applyMultiDisplayDeveloperSettings() {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        final int owner = AohpVirtualDisplayPolicy.getOwnerUid();
        if (owner != -1 && uid != owner) {
            throw new SecurityException("not session owner");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(), "force_resizable_activities", 1);
            Settings.Global.putInt(mContext.getContentResolver(), "force_activities_resizable", 1);
            Settings.Global.putInt(mContext.getContentResolver(), "enable_freeform_support", 0);
            Settings.Global.putInt(mContext.getContentResolver(),
                    "force_desktop_mode_on_external_displays", 0);
            Settings.Global.putInt(mContext.getContentResolver(), "desktop_mode", 0);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int createVirtualDisplay(String name, int width, int height, int densityDpi, int flags)
            throws RemoteException {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            return Display.INVALID_DISPLAY;
        }
        String pkg = getPrimaryPackageForUid(uid);
        int effectiveFlags = flags;
        if (effectiveFlags == 0) {
            effectiveFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
        }
        // OWN_FOCUS gives the AOHP virtual display its own input focus, so KeyEvents /
        // text injection routed with displayId=this-vd find a focused window on this display
        // instead of falling back to the globally focused window (usually on the default
        // display). OWN_FOCUS requires TRUSTED, which we already include.
        effectiveFlags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;
        // Back the VD with an ImageReader-owned Surface. If we pass null, VirtualDisplayAdapter
        // forces the display into STATE_OFF, which in turn makes WindowManager add a sleep
        // token (DisplayContent#onDisplayInfoUpdated), pauses activities, marks their windows
        // NOT_VISIBLE, and finally causes InputDispatcher to drop injected touches as
        // "no touchable window". A silent producer keeps the display active.
        ImageReader reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES,
                IMAGE_READER_USAGE);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                try {
                    Image img = r.acquireLatestImage();
                    if (img != null) {
                        img.close();
                    }
                } catch (IllegalStateException ignored) {
                    // Reader already closed or max images reached; safe to drop.
                }
            }
        }, getReaderHandler());

        Surface surface = reader.getSurface();
        VirtualDisplayConfig config =
                new VirtualDisplayConfig.Builder(
                        name != null && !name.isEmpty() ? name : "aohp-vd",
                        width, height, densityDpi)
                        .setFlags(effectiveFlags)
                        .setSurface(surface)
                        .build();
        IVirtualDisplayCallback callback = new IVirtualDisplayCallback.Stub() {
            @Override
            public void onPaused() {
            }

            @Override
            public void onResumed() {
            }

            @Override
            public void onStopped() {
            }
        };
        final long ident = Binder.clearCallingIdentity();
        int displayId = Display.INVALID_DISPLAY;
        try {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            if (dmi == null) {
                return Display.INVALID_DISPLAY;
            }
            // Surface is attached via VirtualDisplayConfig#setSurface above; DMI signature is
            // (config, callback, virtualDevice, dwpc, packageName, ownerUid).
            displayId = dmi.createVirtualDisplay(config, callback, null, null, pkg, uid);
            if (displayId != Display.INVALID_DISPLAY) {
                synchronized (this) {
                    mAohpCreatedCallbacks.put(displayId, callback);
                    mAohpImageReaders.put(displayId, reader);
                }
                AohpVirtualDisplayPolicy.registerSession(displayId, uid, pkg);
                // Log the freshly-created display's power state so we can tell from logcat
                // whether the attached Surface actually brought the display online.
                DisplayManager dm = mContext.getSystemService(DisplayManager.class);
                Display created = dm != null ? dm.getDisplay(displayId) : null;
                Slog.i(TAG, "createVirtualDisplay: displayId=" + displayId
                        + " size=" + width + "x" + height + " dpi=" + densityDpi
                        + " flags=0x" + Integer.toHexString(effectiveFlags)
                        + " surfaceValid=" + (surface != null && surface.isValid())
                        + " state="
                        + (created != null ? Display.stateToString(created.getState()) : "null"));
            } else {
                Slog.w(TAG, "createVirtualDisplay: DMI returned INVALID_DISPLAY for pkg=" + pkg);
            }
            return displayId;
        } finally {
            Binder.restoreCallingIdentity(ident);
            if (displayId == Display.INVALID_DISPLAY) {
                try {
                    reader.close();
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to close ImageReader after VD creation failure", e);
                }
            }
        }
    }

    /** Public accessor used by the anonymous listener to lazily create the reader thread. */
    private Handler getReaderHandler() {
        synchronized (this) {
            return getReaderHandlerLocked();
        }
    }

    @Override
    public boolean destroyVirtualDisplay(int displayId) throws RemoteException {
        enforceAohpPermission();
        final int uid = Binder.getCallingUid();
        if (!AohpVirtualDisplayPolicy.isDisplayRegisteredForOwner(displayId, uid)) {
            throw new SecurityException("not owner of display");
        }
        IVirtualDisplayCallback callback;
        ImageReader reader;
        synchronized (this) {
            callback = mAohpCreatedCallbacks.get(displayId);
            reader = mAohpImageReaders.get(displayId);
        }
        if (callback == null) {
            return false;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            if (dmi != null) {
                dmi.releaseVirtualDisplay(callback);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        synchronized (this) {
            mAohpCreatedCallbacks.remove(displayId);
            mAohpImageReaders.remove(displayId);
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception e) {
                Slog.w(TAG, "Failed to close ImageReader for displayId=" + displayId, e);
            }
        }
        AohpVirtualDisplayPolicy.unregisterDisplay(displayId);
        return true;
    }

    private String getPrimaryPackageForUid(int uid) {
        String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
        if (pkgs != null && pkgs.length > 0) {
            return pkgs[0];
        }
        return "android";
    }

    private boolean injectMotionDownUp(int displayId, int x, int y, long now) {
        if (!injectMotionEvent(displayId, MotionEvent.ACTION_DOWN, now, now, x, y)) {
            return false;
        }
        return injectMotionEvent(displayId, MotionEvent.ACTION_UP, now, now + 10, x, y);
    }

    private boolean injectMotionEvent(
            int displayId, int action, long downTime, long eventTime, float x, float y) {
        final long ident = Binder.clearCallingIdentity();
        MotionEvent ev = null;
        try {
            // Use synthetic device id 0 so routing follows MotionEvent.getDisplayId().
            // A physical touchscreen id is tied to the default display and can prevent
            // touches from reaching secondary / virtual displays (e.g. Cuttlefish).
            final int syntheticTouchDeviceId = 0;
            ev = MotionEvent.obtain(downTime, eventTime, action, x, y,
                    DEFAULT_PRESSURE, DEFAULT_SIZE, DEFAULT_META_STATE,
                    1.0f, 1.0f, syntheticTouchDeviceId, DEFAULT_EDGE_FLAGS,
                    InputDevice.SOURCE_TOUCHSCREEN, displayId);
            // Defensive: re-set displayId on the MotionEvent in case MotionEvent.obtain's
            // overload didn't carry it through (observed on some builds where the ctor
            // silently drops displayId when deviceId is 0).
            if (ev.getDisplayId() != displayId) {
                Slog.w(TAG, "injectMotionEvent: obtained event has displayId="
                        + ev.getDisplayId() + " expected " + displayId + "; forcing setDisplayId");
                ev.setDisplayId(displayId);
            }
            final boolean ok = mInputManager.injectInputEvent(
                    ev, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            Slog.i(TAG, "injectMotionEvent: displayId=" + displayId
                    + " action=" + MotionEvent.actionToString(action)
                    + " xy=(" + x + "," + y + ") eventDisplayId=" + ev.getDisplayId()
                    + " result=" + ok);
            return ok;
        } finally {
            if (ev != null) {
                ev.recycle();
            }
            Binder.restoreCallingIdentity(ident);
        }
    }
}
