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
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.internal.aohp.IAohpVirtualDisplay;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.AohpVirtualDisplayPolicy;
import com.android.server.wm.SafeActivityOptions;

/**
 * Binder service: privileged virtual display input and launcher starts for AOHP agent.
 */
public final class AohpVirtualDisplayService extends IAohpVirtualDisplay.Stub {
    public static final String SERVICE_NAME = "aohp_virtual_display";

    private static final float DEFAULT_SIZE = 1.0f;
    private static final float DEFAULT_PRESSURE = 1.0f;
    private static final int DEFAULT_META_STATE = 0;
    private static final int DEFAULT_EDGE_FLAGS = 0;

    private final Context mContext;
    private final InputManagerService mInputManager;

    public AohpVirtualDisplayService(Context context, InputManagerService inputManager) {
        mContext = context;
        mInputManager = inputManager;
    }

    private void enforceAohpPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    private void verifyCallerRegistered(int displayId, int ownerUid) {
        if (ownerUid != AohpVirtualDisplayPolicy.getOwnerUid()
                || displayId != AohpVirtualDisplayPolicy.getRegisteredDisplayId()) {
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
            if (!mInputManager.injectInputEvent(e, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)) {
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

    private boolean injectMotionDownUp(int displayId, int x, int y, long now) {
        if (!injectMotionEvent(displayId, MotionEvent.ACTION_DOWN, now, now, x, y)) {
            return false;
        }
        return injectMotionEvent(displayId, MotionEvent.ACTION_UP, now, now + 10, x, y);
    }

    private boolean injectMotionEvent(
            int displayId, int action, long downTime, long eventTime, float x, float y) {
        final long ident = Binder.clearCallingIdentity();
        try {
            // Use synthetic device id 0 so routing follows MotionEvent.getDisplayId().
            // A physical touchscreen id is tied to the default display and can prevent
            // touches from reaching secondary / virtual displays (e.g. Cuttlefish).
            final int syntheticTouchDeviceId = 0;
            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, x, y,
                    DEFAULT_PRESSURE, DEFAULT_SIZE, DEFAULT_META_STATE,
                    1.0f, 1.0f, syntheticTouchDeviceId, DEFAULT_EDGE_FLAGS,
                    InputDevice.SOURCE_TOUCHSCREEN, displayId);
            return mInputManager.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
