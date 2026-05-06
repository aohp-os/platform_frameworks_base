/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;

import com.android.internal.aohp.IAohpAdManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * System-side coordinator for AOHP ad compatibility.
 *
 * <p>The service intentionally keeps third-party ad SDKs out of system_server. It owns policy,
 * opportunity state and event accounting; SystemUI / adapter processes can be layered on top via
 * the JSON management surface.</p>
 */
public final class AohpAdService extends IAohpAdManager.Stub {
    public static final String SERVICE_NAME = "aohp_ad";

    private static final String TAG = "AohpAdService";
    private static final int DEFAULT_MAX_EVENTS = 300;
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private final Context mContext;
    private final Object mLock = new Object();
    private final Map<String, SlotRecord> mSlots = new HashMap<>();
    private final Map<String, JSONObject> mOpportunities = new HashMap<>();
    private final ArrayDeque<JSONObject> mEvents = new ArrayDeque<>();

    private JSONObject mPolicy;
    private JSONObject mHostState;
    private long mNextSeq = 1;
    private long mDropped;

    public AohpAdService(Context context) {
        mContext = context;
        mPolicy = defaultPolicy();
        mHostState = new JSONObject();
        try {
            mHostState.put("ok", true);
            mHostState.put("available", false);
            mHostState.put("host", "not_registered");
        } catch (JSONException ignored) {
        }
    }

    @Override
    public String registerSlot(String slotJson) {
        final int uid = Binder.getCallingUid();
        try {
            JSONObject in = parse(slotJson);
            String slotId = in.optString("slotId", "");
            if (slotId.isEmpty()) {
                slotId = "slot-" + UUID.randomUUID();
            }
            SlotRecord r = new SlotRecord();
            r.slotId = slotId;
            r.ownerUid = uid;
            r.packageName = in.optString("packageName", "");
            r.format = in.optString("format", "banner");
            r.placement = in.optString("placement", slotId);
            r.displayId = in.optInt("displayId", Display.DEFAULT_DISPLAY);
            r.createdAt = now();
            synchronized (mLock) {
                mSlots.put(slotId, r);
                recordLocked("slot_registered", slotId, uid, r.packageName, in);
            }
            return ok().put("slotId", slotId).put("ownerUid", uid).toString();
        } catch (Exception e) {
            Slog.w(TAG, "registerSlot failed", e);
            return error("register_slot_failed", e.getMessage());
        }
    }

    @Override
    public String requestDecision(String slotId, String requestJson) {
        final int uid = Binder.getCallingUid();
        try {
            JSONObject req = parse(requestJson);
            int displayId = req.optInt("displayId", Display.DEFAULT_DISPLAY);
            String decision = decide(displayId, req.optString("format", ""), false);
            JSONObject out = ok();
            out.put("slotId", safe(slotId));
            out.put("displayId", displayId);
            out.put("decision", decision);
            out.put("humanVisible", isHumanDisplay(displayId));
            synchronized (mLock) {
                recordLocked("decision", safe(slotId), uid, "", out);
                if ("SUPPRESS_FOR_AGENT".equals(decision)) {
                    recordLocked("agent_suppressed", safe(slotId), uid, "", out);
                }
            }
            return out.toString();
        } catch (Exception e) {
            Slog.w(TAG, "requestDecision failed", e);
            return error("decision_failed", e.getMessage());
        }
    }

    @Override
    public String reportEvent(String slotId, String eventJson) {
        final int uid = Binder.getCallingUid();
        try {
            JSONObject event = parse(eventJson);
            String type = event.optString("type", "app_event");
            synchronized (mLock) {
                recordLocked(type, safe(slotId), uid, "", event);
            }
            return ok().put("slotId", safe(slotId)).put("type", type).toString();
        } catch (Exception e) {
            return error("report_event_failed", e.getMessage());
        }
    }

    @Override
    public boolean unregisterSlot(String slotId) {
        synchronized (mLock) {
            return mSlots.remove(safe(slotId)) != null;
        }
    }

    @Override
    public String submitOpportunity(String opportunityJson) {
        final int uid = Binder.getCallingUid();
        try {
            JSONObject in = parse(opportunityJson);
            String id = in.optString("opportunityId", "");
            if (id.isEmpty()) {
                id = "opp-" + UUID.randomUUID();
            }
            int displayId = in.optInt("displayId", Display.DEFAULT_DISPLAY);
            String decision = decide(displayId, in.optString("format", ""), true);
            JSONObject opp = new JSONObject(in.toString());
            opp.put("opportunityId", id);
            opp.put("ownerUid", uid);
            opp.put("decision", decision);
            opp.put("createdAt", System.currentTimeMillis());
            synchronized (mLock) {
                mOpportunities.put(id, opp);
                recordLocked("opportunity", id, uid, in.optString("packageName", ""), opp);
                if ("DEFERRED_TO_HUMAN".equals(decision)) {
                    recordLocked("deferred", id, uid, in.optString("packageName", ""), opp);
                } else if ("SUPPRESSED_FOR_AGENT".equals(decision)) {
                    recordLocked("agent_suppressed", id, uid, in.optString("packageName", ""), opp);
                }
            }
            return ok().put("opportunityId", id).put("decision", decision).toString();
        } catch (Exception e) {
            return error("submit_opportunity_failed", e.getMessage());
        }
    }

    @Override
    public String getHostState(String queryJson) {
        enforceManagePermission();
        synchronized (mLock) {
            return copyWithOk(mHostState).toString();
        }
    }

    @Override
    public String recordHostEvent(String eventJson) {
        enforceManagePermission();
        try {
            JSONObject event = parse(eventJson);
            synchronized (mLock) {
                if ("state".equals(event.optString("kind"))) {
                    mHostState = new JSONObject(event.toString());
                    mHostState.put("ok", true);
                    mHostState.put("updatedAt", System.currentTimeMillis());
                }
                recordLocked(event.optString("type", "host_event"),
                        event.optString("opportunityId", ""), Binder.getCallingUid(),
                        event.optString("packageName", ""), event);
            }
            return ok().put("recorded", true).toString();
        } catch (Exception e) {
            return error("host_event_failed", e.getMessage());
        }
    }

    @Override
    public String runAdapterTest(String testJson) {
        enforceManagePermission();
        try {
            JSONObject in = parse(testJson);
            String scenario = in.optString("scenario", "fake_banner");
            JSONObject out = ok();
            out.put("scenario", scenario);
            out.put("testMode", true);
            if ("no_fill".equals(scenario)) {
                out.put("result", "NO_FILL");
                recordHostAdapter("adapter_no_fill", out);
            } else if ("timeout".equals(scenario)) {
                out.put("result", "TIMEOUT");
                recordHostAdapter("adapter_timeout", out);
            } else if ("crash".equals(scenario)) {
                out.put("result", "CRASH_SIMULATED");
                recordHostAdapter("adapter_crash", out);
            } else if ("render_error".equals(scenario)) {
                out.put("result", "RENDER_ERROR");
                recordHostAdapter("render_error", out);
            } else {
                out.put("result", "LOADED");
                out.put("creativeMarker", "AOHP_FAKE_AD_CREATIVE_MARKER");
                recordHostAdapter("adapter_loaded", out);
            }
            return out.toString();
        } catch (Exception e) {
            return error("adapter_test_failed", e.getMessage());
        }
    }

    @Override
    public String getState(String queryJson) {
        enforceManagePermission();
        synchronized (mLock) {
            try {
                JSONObject out = ok();
                out.put("service", SERVICE_NAME);
                out.put("policy", new JSONObject(mPolicy.toString()));
                out.put("slotCount", mSlots.size());
                out.put("opportunityCount", mOpportunities.size());
                out.put("eventCount", mEvents.size());
                out.put("dropped", mDropped);
                out.put("hostState", copyWithOk(mHostState));
                return out.toString();
            } catch (JSONException e) {
                return error("state_failed", e.getMessage());
            }
        }
    }

    @Override
    public String setPolicy(String policyJson) {
        enforceManagePermission();
        try {
            JSONObject incoming = parse(policyJson);
            synchronized (mLock) {
                Iterator<String> keys = incoming.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    mPolicy.put(key, incoming.opt(key));
                }
                recordLocked("policy_set", "", Binder.getCallingUid(), "", mPolicy);
                return ok().put("policy", new JSONObject(mPolicy.toString())).toString();
            }
        } catch (Exception e) {
            return error("policy_failed", e.getMessage());
        }
    }

    @Override
    public void clearEvents() {
        enforceManagePermission();
        synchronized (mLock) {
            mEvents.clear();
            mDropped = 0;
        }
    }

    @Override
    public String drainEvents(String optionsJson) {
        enforceManagePermission();
        try {
            JSONObject opts = parse(optionsJson);
            int max = opts.optInt("maxEvents", DEFAULT_MAX_EVENTS);
            synchronized (mLock) {
                pruneLocked();
                JSONArray events = new JSONArray();
                int count = 0;
                for (JSONObject event : mEvents) {
                    events.put(new JSONObject(event.toString()));
                    count++;
                    if (max > 0 && count >= max) {
                        break;
                    }
                }
                JSONObject out = ok();
                out.put("events", events);
                out.put("count", events.length());
                out.put("dropped", mDropped);
                return out.toString();
            }
        } catch (Exception e) {
            return error("drain_failed", e.getMessage());
        }
    }

    @Override
    public String runSelfTest(String optionsJson) {
        enforceManagePermission();
        try {
            JSONObject out = ok();
            out.put("serviceAvailable", true);
            out.put("defaultDisplayDecision", decide(Display.DEFAULT_DISPLAY, "banner", false));
            out.put("agentDisplayDecision", decide(99, "banner", false));
            out.put("adapterFake", new JSONObject(runAdapterTest("{\"scenario\":\"fake_banner\"}")));
            return out.toString();
        } catch (Exception e) {
            return error("self_test_failed", e.getMessage());
        }
    }

    private void enforceManagePermission() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_AOHP_ADS, null);
    }

    private String decide(int displayId, String format, boolean opportunity) {
        if (!mPolicy.optBoolean("enabled", true)) {
            return "DENIED_BY_POLICY";
        }
        if (!mPolicy.optBoolean("consent", true)) {
            return "DENIED_BY_CONSENT";
        }
        if (!isHumanDisplay(displayId)) {
            if (opportunity && mPolicy.optBoolean("deferAgentOpportunities", true)) {
                return "DEFERRED_TO_HUMAN";
            }
            return "SUPPRESS_FOR_AGENT";
        }
        if (opportunity && mPolicy.optBoolean("systemHostOnMainDisplay", false)) {
            return "HOSTED_BY_SYSTEM";
        }
        return "RENDER_IN_APP";
    }

    private static boolean isHumanDisplay(int displayId) {
        return displayId == Display.DEFAULT_DISPLAY;
    }

    private void recordHostAdapter(String type, JSONObject payload) throws JSONException {
        synchronized (mLock) {
            recordLocked(type, payload.optString("opportunityId", ""), Binder.getCallingUid(), "", payload);
        }
    }

    private void recordLocked(String type, String id, int uid, String pkg, JSONObject payload)
            throws JSONException {
        pruneLocked();
        JSONObject e = new JSONObject();
        e.put("seq", mNextSeq++);
        e.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
        e.put("wallTimeMs", System.currentTimeMillis());
        e.put("type", type);
        e.put("id", safe(id));
        e.put("uid", uid);
        e.put("packageName", safe(pkg));
        if (payload != null) {
            e.put("payload", new JSONObject(payload.toString()));
        }
        mEvents.addLast(e);
        while (mEvents.size() > DEFAULT_MAX_EVENTS) {
            mEvents.removeFirst();
            mDropped++;
        }
    }

    private void pruneLocked() {
        long cutoff = SystemClock.elapsedRealtime() - DEFAULT_TTL_MS;
        while (!mEvents.isEmpty() && mEvents.peekFirst().optLong("elapsedRealtimeMs") < cutoff) {
            mEvents.removeFirst();
            mDropped++;
        }
    }

    private static JSONObject defaultPolicy() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", true);
            o.put("consent", true);
            o.put("suppressOnAgentDisplay", true);
            o.put("deferAgentOpportunities", true);
            o.put("systemHostOnMainDisplay", false);
            o.put("passthroughDebug", false);
            o.put("frequencyCapPerPlacement", 3);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static JSONObject ok() throws JSONException {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject copyWithOk(JSONObject in) {
        try {
            JSONObject out = in != null ? new JSONObject(in.toString()) : new JSONObject();
            out.put("ok", true);
            return out;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String error(String code, String message) {
        try {
            return new JSONObject()
                    .put("ok", false)
                    .put("error", true)
                    .put("code", code)
                    .put("message", message != null ? message : "")
                    .toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":true}";
        }
    }

    private static JSONObject parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(json);
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static final class SlotRecord {
        String slotId;
        int ownerUid;
        String packageName;
        String format;
        String placement;
        int displayId;
        long createdAt;
    }
}
