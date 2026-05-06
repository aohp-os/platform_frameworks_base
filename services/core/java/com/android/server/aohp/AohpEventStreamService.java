/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Slog;
import android.window.ScreenCaptureInternal;

import com.android.internal.aohp.IAohpEventStream;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerInternal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System-side event stream buffer for AOHP automation.
 *
 * <p>Hooks in NotificationManagerService/SystemUI enqueue lightweight event builders here. The
 * service owns all I/O-ish work on a private handler so framework hot paths do not block.</p>
 */
public final class AohpEventStreamService extends IAohpEventStream.Stub {
    public static final String SERVICE_NAME = "aohp_event_stream";

    private static final String TAG = "AohpEventStream";
    private static final int DEFAULT_MAX_EVENTS = 200;
    private static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;
    private static final int DEFAULT_QUALITY = 75;
    private static final int MAX_INLINE_SCREENSHOT_BYTES = 2 * 1024 * 1024;
    private static final int SCREENSHOT_TIMEOUT_SEC = 5;

    private static volatile AohpEventStreamService sInstance;

    private final Context mContext;
    private final ActivityTaskManagerService mAtm;
    private final HandlerThread mThread;
    private final Handler mHandler;
    private final Object mLock = new Object();
    private final ArrayDeque<EventRecord> mEvents = new ArrayDeque<>();
    private final Map<String, Session> mSessions = new HashMap<>();

    private long mNextSeq = 1;
    private long mDropped;

    public AohpEventStreamService(Context context, ActivityTaskManagerService atm) {
        mContext = context;
        mAtm = atm;
        mThread = new HandlerThread("AohpEventStream");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        sInstance = this;
    }

    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    @Override
    public String register(String clientId, String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            Options opts = Options.parse(optionsJson);
            String id = UUID.randomUUID().toString();
            synchronized (mLock) {
                Session s = new Session();
                s.sessionId = id;
                s.clientId = emptyToDefault(clientId, "unknown");
                s.nextSeq = mNextSeq;
                s.maxEvents = opts.maxEvents;
                s.ttlMs = opts.ttlMs;
                s.captureScreenshots = opts.captureScreenshots;
                s.screenshotQuality = opts.screenshotQuality;
                mSessions.put(id, s);
                JSONObject out = ok();
                out.put("sessionId", id);
                out.put("nextSeq", s.nextSeq);
                out.put("clientId", s.clientId);
                out.put("activeSessions", mSessions.size());
                return out.toString();
            }
        } catch (Exception e) {
            return error("register_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String drain(String sessionId, String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            DrainOptions opts = DrainOptions.parse(optionsJson);
            synchronized (mLock) {
                Session session = mSessions.get(sessionId);
                if (session == null) {
                    return error("unknown_session", sessionId);
                }
                pruneLocked(SystemClock.elapsedRealtime());
                JSONArray arr = new JSONArray();
                long next = session.nextSeq;
                int count = 0;
                for (EventRecord event : mEvents) {
                    if (event.seq < session.nextSeq) {
                        continue;
                    }
                    arr.put(event.toJson(opts.includeScreenshots, opts.inlineScreenshots));
                    next = Math.max(next, event.seq + 1);
                    count++;
                    if (opts.maxEvents > 0 && count >= opts.maxEvents) {
                        break;
                    }
                }
                session.nextSeq = next;
                JSONObject out = ok();
                out.put("sessionId", session.sessionId);
                out.put("events", arr);
                out.put("count", count);
                out.put("dropped", mDropped);
                out.put("nextSeq", session.nextSeq);
                out.put("summary", summarize(arr));
                return out.toString();
            }
        } catch (Exception e) {
            return error("drain_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean unregister(String sessionId) {
        enforcePermission();
        synchronized (mLock) {
            boolean removed = mSessions.remove(sessionId) != null;
            if (mSessions.isEmpty()) {
                mEvents.clear();
            }
            return removed;
        }
    }

    @Override
    public String status() {
        enforcePermission();
        synchronized (mLock) {
            try {
                JSONObject out = ok();
                out.put("activeSessions", mSessions.size());
                out.put("bufferedEvents", mEvents.size());
                out.put("nextSeq", mNextSeq);
                out.put("dropped", mDropped);
                JSONArray sessions = new JSONArray();
                for (Session s : mSessions.values()) {
                    JSONObject o = new JSONObject();
                    o.put("sessionId", s.sessionId);
                    o.put("clientId", s.clientId);
                    o.put("nextSeq", s.nextSeq);
                    o.put("captureScreenshots", s.captureScreenshots);
                    sessions.put(o);
                }
                out.put("sessions", sessions);
                return out.toString();
            } catch (JSONException e) {
                return error("json", e.getMessage());
            }
        }
    }

    @Override
    public void recordHeadsUp(String type, String key, String packageName) {
        recordHeadsUpEvent(type, key, packageName);
    }

    public static void recordToast(String phase, int uid, int pid, String pkg, CharSequence text,
            int duration, int displayId) {
        AohpEventStreamService s = sInstance;
        if (s == null || !s.hasActiveSessions()) {
            return;
        }
        final String safeText = text != null ? text.toString() : null;
        s.mHandler.post(() -> s.addEvent("toast", displayId, pkg, uid, null, safeText,
                json -> {
                    json.put("phase", phase);
                    json.put("pid", pid);
                    json.put("duration", duration);
                }));
    }

    public static void recordNotificationPosted(StatusBarNotification sbn) {
        AohpEventStreamService s = sInstance;
        if (s == null || !s.hasActiveSessions() || sbn == null) {
            return;
        }
        s.mHandler.post(() -> s.addNotificationEvent("notification_posted", sbn, -1));
    }

    public static void recordNotificationRemoved(StatusBarNotification sbn, int reason) {
        AohpEventStreamService s = sInstance;
        if (s == null || !s.hasActiveSessions() || sbn == null) {
            return;
        }
        s.mHandler.post(() -> s.addNotificationEvent("notification_removed", sbn, reason));
    }

    public static void recordHeadsUpEvent(String type, String key, String pkg) {
        AohpEventStreamService s = sInstance;
        if (s == null || !s.hasActiveSessions()) {
            return;
        }
        s.mHandler.post(() -> s.addEvent(type, 0, pkg, -1, null, key,
                json -> json.put("notificationKey", key)));
    }

    private boolean hasActiveSessions() {
        synchronized (mLock) {
            return !mSessions.isEmpty();
        }
    }

    private void addNotificationEvent(String type, StatusBarNotification sbn, int reason) {
        Notification n = sbn.getNotification();
        Bundle extras = n != null ? n.extras : null;
        String title = charSeq(extras, Notification.EXTRA_TITLE);
        String text = firstNonEmpty(
                charSeq(extras, Notification.EXTRA_BIG_TEXT),
                charSeq(extras, Notification.EXTRA_TEXT),
                charSeq(extras, Notification.EXTRA_SUB_TEXT));
        String summary = firstNonEmpty(joinNonEmpty(title, text), sbn.getPackageName());
        addEvent(type, 0, sbn.getPackageName(), sbn.getUid(), sbn.getKey(), summary,
                json -> {
                    JSONObject no = new JSONObject();
                    no.put("key", sbn.getKey());
                    no.put("id", sbn.getId());
                    no.put("tag", sbn.getTag());
                    no.put("title", title);
                    no.put("text", text);
                    no.put("channelId", n != null ? n.getChannelId() : null);
                    no.put("category", n != null ? n.category : null);
                    if (reason >= 0) {
                        no.put("removeReason", reason);
                    }
                    json.put("notification", no);
                });
    }

    private void addEvent(String type, int displayId, String pkg, int uid, String key, String text,
            JsonMutator mutator) {
        long now = SystemClock.elapsedRealtime();
        try {
            JSONObject data = new JSONObject();
            data.put("type", type);
            data.put("timeRealtimeMs", now);
            data.put("timeWallMs", System.currentTimeMillis());
            data.put("packageName", pkg);
            data.put("uid", uid);
            data.put("displayId", displayId);
            data.put("displayRole", displayId == 0 ? "default" : "aohp_virtual");
            data.put("text", text);
            if (key != null) {
                data.put("key", key);
            }
            attachActivitySnapshot(data, displayId, pkg);
            if (mutator != null) {
                mutator.apply(data);
            }
            EventRecord record = new EventRecord();
            boolean capture;
            int quality;
            synchronized (mLock) {
                record.seq = mNextSeq++;
                record.realtimeMs = now;
                record.data = data;
                record.screenshotDisplayId = displayId;
                capture = shouldCaptureScreenshotsLocked();
                quality = effectiveScreenshotQualityLocked();
            }
            if (capture) {
                record.screenshotBytes = captureDisplay(displayId, quality);
            }
            synchronized (mLock) {
                mEvents.addLast(record);
                pruneLocked(now);
            }
        } catch (Exception e) {
            Slog.w(TAG, "addEvent " + type, e);
        }
    }

    private void attachActivitySnapshot(JSONObject data, int displayId, String pkg) {
        if (mAtm == null) {
            return;
        }
        try {
            String snapshot = mAtm.buildAohpDisplayRuntimeSnapshotJson(null);
            data.put("displaySnapshot", new JSONObject(snapshot));
            String activity = findActivityForDisplay(new JSONObject(snapshot), displayId, pkg);
            if (activity != null) {
                data.put("activity", activity);
            }
        } catch (Exception e) {
            try {
                data.put("activityError", e.getMessage());
            } catch (JSONException ignored) {
            }
        }
    }

    private static String findActivityForDisplay(JSONObject snapshot, int displayId, String pkg)
            throws JSONException {
        JSONArray displays = snapshot.optJSONArray("displays");
        if (displays == null) {
            return null;
        }
        String fallback = null;
        for (int i = 0; i < displays.length(); i++) {
            JSONObject d = displays.getJSONObject(i);
            JSONObject top = d.optJSONObject("topRunningActivity");
            JSONObject focused = d.optJSONObject("focusedActivity");
            String topName = componentString(top);
            String focusedName = componentString(focused);
            if (d.optInt("displayId", -1) == displayId) {
                fallback = firstNonEmpty(focusedName, topName, fallback);
            }
            if (pkg != null && (matchesPackage(top, pkg) || matchesPackage(focused, pkg))) {
                return firstNonEmpty(focusedName, topName);
            }
        }
        return fallback;
    }

    private static boolean matchesPackage(JSONObject component, String pkg) {
        return component != null && pkg != null && pkg.equals(component.optString("packageName"));
    }

    private static String componentString(JSONObject component) {
        if (component == null) {
            return null;
        }
        String pkg = component.optString("packageName", "");
        String cls = component.optString("className", "");
        if (pkg.isEmpty() && cls.isEmpty()) {
            return null;
        }
        return pkg + "/" + cls;
    }

    private boolean shouldCaptureScreenshotsLocked() {
        for (Session s : mSessions.values()) {
            if (s.captureScreenshots) {
                return true;
            }
        }
        return false;
    }

    private int effectiveScreenshotQualityLocked() {
        int quality = DEFAULT_QUALITY;
        for (Session s : mSessions.values()) {
            if (s.captureScreenshots) {
                quality = Math.max(quality, s.screenshotQuality);
            }
        }
        return quality;
    }

    private byte[] captureDisplay(int displayId, int quality) {
        WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
        if (wmi == null) {
            return null;
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
        ScreenCaptureInternal.CaptureArgs captureArgs = null;
        wmi.captureDisplay(displayId, captureArgs, listener);
        try {
            if (!latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS) || statusRef.get() != 0) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        ScreenCaptureInternal.ScreenshotHardwareBuffer shb = bufferRef.get();
        if (shb == null) {
            return null;
        }
        Bitmap hw = shb.asBitmap();
        if (hw == null) {
            return null;
        }
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            sw.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, quality)), baos);
            return baos.toByteArray();
        } finally {
            sw.recycle();
        }
    }

    private void pruneLocked(long now) {
        long ttl = effectiveTtlLocked();
        int max = effectiveMaxEventsLocked();
        Iterator<EventRecord> it = mEvents.iterator();
        while (it.hasNext()) {
            EventRecord e = it.next();
            if (now - e.realtimeMs > ttl) {
                it.remove();
                mDropped++;
            } else {
                break;
            }
        }
        while (mEvents.size() > max) {
            mEvents.removeFirst();
            mDropped++;
        }
    }

    private int effectiveMaxEventsLocked() {
        int max = DEFAULT_MAX_EVENTS;
        for (Session s : mSessions.values()) {
            max = Math.max(max, s.maxEvents);
        }
        return max;
    }

    private long effectiveTtlLocked() {
        long ttl = DEFAULT_TTL_MS;
        for (Session s : mSessions.values()) {
            ttl = Math.max(ttl, s.ttlMs);
        }
        return ttl;
    }

    private static JSONObject ok() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        return o;
    }

    private static String error(String code, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", true);
            o.put("code", code);
            o.put("message", message != null ? message : "");
            return o.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":true}";
        }
    }

    private static String summarize(JSONArray arr) throws JSONException {
        if (arr.length() == 0) {
            return "No new AOHP events.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            sb.append('#').append(e.optLong("seq"))
                    .append(' ').append(e.optString("type"))
                    .append(" display=").append(e.optInt("displayId", -1))
                    .append(' ').append(e.optString("packageName", ""));
            String text = e.optString("text", "");
            if (!text.isEmpty()) {
                sb.append(": ").append(text);
            }
            if (i + 1 < arr.length()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String charSeq(Bundle extras, String key) {
        if (extras == null) {
            return null;
        }
        CharSequence cs = extras.getCharSequence(key);
        return cs != null ? cs.toString() : null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String joinNonEmpty(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return a + " - " + b;
    }

    private static String emptyToDefault(String value, String def) {
        return value == null || value.isEmpty() ? def : value;
    }

    private interface JsonMutator {
        void apply(JSONObject json) throws JSONException;
    }

    private static final class Session {
        String sessionId;
        String clientId;
        long nextSeq;
        int maxEvents = DEFAULT_MAX_EVENTS;
        long ttlMs = DEFAULT_TTL_MS;
        boolean captureScreenshots = true;
        int screenshotQuality = DEFAULT_QUALITY;
    }

    private static final class EventRecord {
        long seq;
        long realtimeMs;
        int screenshotDisplayId;
        JSONObject data;
        byte[] screenshotBytes;

        JSONObject toJson(boolean includeScreenshots, boolean inlineScreenshots) throws JSONException {
            JSONObject out = new JSONObject(data.toString());
            out.put("seq", seq);
            if (includeScreenshots) {
                JSONObject shots = out.optJSONObject("screenshots");
                if (shots == null) {
                    shots = new JSONObject();
                }
                shots.put("displayId", screenshotDisplayId);
                if (screenshotBytes != null) {
                    shots.put("bytes", screenshotBytes.length);
                    shots.put("mimeType", "image/jpeg");
                    if (inlineScreenshots && screenshotBytes.length <= MAX_INLINE_SCREENSHOT_BYTES) {
                        shots.put("inlineBase64", Base64.encodeToString(
                                screenshotBytes, Base64.NO_WRAP));
                    }
                } else {
                    shots.put("error", "not_captured");
                }
                out.put("screenshots", shots);
            }
            return out;
        }
    }

    private static class Options {
        int maxEvents = DEFAULT_MAX_EVENTS;
        long ttlMs = DEFAULT_TTL_MS;
        boolean captureScreenshots = true;
        int screenshotQuality = DEFAULT_QUALITY;

        static Options parse(String json) {
            Options o = new Options();
            JSONObject p = parseObj(json);
            o.maxEvents = clamp(p.optInt("maxEvents", DEFAULT_MAX_EVENTS), 1, 2000);
            o.ttlMs = clamp(p.optLong("ttlMs", DEFAULT_TTL_MS), 10_000L, 60 * 60 * 1000L);
            o.captureScreenshots = p.optBoolean("captureScreenshots", true);
            o.screenshotQuality = clamp(p.optInt("screenshotQuality", DEFAULT_QUALITY), 1, 100);
            return o;
        }
    }

    private static final class DrainOptions {
        boolean includeScreenshots;
        boolean inlineScreenshots;
        int maxEvents;

        static DrainOptions parse(String json) {
            DrainOptions o = new DrainOptions();
            JSONObject p = parseObj(json);
            o.includeScreenshots = p.optBoolean("includeScreenshots", false);
            o.inlineScreenshots = p.optBoolean("inlineScreenshots", false);
            o.maxEvents = clamp(p.optInt("maxEvents", 0), 0, 2000);
            return o;
        }
    }

    private static JSONObject parseObj(String json) {
        if (json == null || json.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Slog.w(TAG, String.format(Locale.US, "bad options json: %s", json));
            return new JSONObject();
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
