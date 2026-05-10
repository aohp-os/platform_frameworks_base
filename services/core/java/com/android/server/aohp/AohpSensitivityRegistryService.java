/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Binder;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.aohp.IAohpSensitivityRegistry;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads per-app {@code aohp_sensitivity.xml} (via manifest meta-data) and stores skill policies.
 */
public final class AohpSensitivityRegistryService extends IAohpSensitivityRegistry.Stub {
    public static final String SERVICE_NAME = "aohp_sensitivity_registry";
    private static final String TAG = "AohpSensitivityRegistry";
    private static final String META_NAME = "aohp_sensitivity_manifest";

    public static final class FieldSpec {
        public final boolean sensitive;
        public final boolean sink;
        public final String description;

        FieldSpec(boolean sensitive, boolean sink, String description) {
            this.sensitive = sensitive;
            this.sink = sink;
            this.description = description != null ? description : "";
        }
    }

    private final Context mContext;
    private final Map<String, Map<String, FieldSpec>> mFields = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> mActions = new ConcurrentHashMap<>();
    private final Map<String, String> mSkillJson = new ConcurrentHashMap<>();
    private final Set<String> mLoadedPackages = ConcurrentHashMap.newKeySet();
    private final Map<String, String> mLoadStatus = new ConcurrentHashMap<>();

    public AohpSensitivityRegistryService(Context context) {
        mContext = context;
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    @Override
    public void registerSkillPolicy(String skillName, String securityJson) {
        enforce();
        registerSkillPolicyInternal(skillName, securityJson);
    }

    /** In-process: no permission re-check (caller must be system_server component). */
    public void registerSkillPolicyInternal(String skillName, String securityJson) {
        if (TextUtils.isEmpty(skillName)) {
            return;
        }
        mSkillJson.put(skillName, securityJson != null ? securityJson : "{}");
    }

    @Override
    public String getSkillPolicyJson(String skillName) {
        enforce();
        return getSkillPolicyJsonInternal(skillName);
    }

    public String getSkillPolicyJsonInternal(String skillName) {
        return mSkillJson.getOrDefault(skillName, "{}");
    }

    @Override
    public void reloadAppManifest(String packageName) {
        enforce();
        reloadAppManifestTrusted(packageName);
    }

    /** For {@link AohpSecurityShellBinder} only (shell / root uid). */
    public void reloadAppManifestTrusted(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        mLoadedPackages.remove(packageName);
        mFields.remove(packageName);
        mActions.remove(packageName);
        ensurePackageLoaded(packageName);
    }

    /** Idempotent load for the given installed package. */
    public void ensurePackageLoaded(String packageName) {
        if (TextUtils.isEmpty(packageName) || mLoadedPackages.contains(packageName)) {
            return;
        }
        synchronized (this) {
            if (mLoadedPackages.contains(packageName)) {
                return;
            }
            loadPackageUnsafe(packageName);
            mLoadedPackages.add(packageName);
        }
    }

    public FieldSpec getField(String packageName, String resourceId) {
        ensurePackageLoaded(packageName);
        if (TextUtils.isEmpty(resourceId)) {
            return null;
        }
        Map<String, FieldSpec> m = mFields.get(packageName);
        if (m == null) {
            return null;
        }
        String shortId = shortenResourceId(resourceId);
        FieldSpec fs = m.get(shortId);
        if (fs != null) {
            return fs;
        }
        return m.get(resourceId);
    }

    public boolean hasLoadedFieldDeclarations(String packageName) {
        ensurePackageLoaded(packageName);
        Map<String, FieldSpec> m = mFields.get(packageName);
        return m != null && !m.isEmpty();
    }

    public boolean isAction(String packageName, String resourceId) {
        ensurePackageLoaded(packageName);
        if (TextUtils.isEmpty(resourceId)) {
            return false;
        }
        Set<String> s = mActions.get(packageName);
        if (s == null) {
            return false;
        }
        return s.contains(shortenResourceId(resourceId)) || s.contains(resourceId);
    }

    /** Debug shell visibility for validating loaded app declarations. */
    public String dumpPackageJsonTrusted(String packageName) {
        ensurePackageLoaded(packageName);
        try {
            JSONObject root = new JSONObject();
            root.put("packageName", packageName);
            root.put("loaded", mLoadedPackages.contains(packageName));
            root.put("loadStatus", mLoadStatus.getOrDefault(packageName, ""));

            JSONObject fieldsJson = new JSONObject();
            Map<String, FieldSpec> fields = mFields.get(packageName);
            if (fields != null) {
                for (Map.Entry<String, FieldSpec> entry : fields.entrySet()) {
                    FieldSpec fs = entry.getValue();
                    JSONObject f = new JSONObject();
                    f.put("sensitive", fs.sensitive);
                    f.put("sink", fs.sink);
                    f.put("description", fs.description);
                    fieldsJson.put(entry.getKey(), f);
                }
            }
            root.put("fields", fieldsJson);

            JSONArray actionsJson = new JSONArray();
            Set<String> actions = mActions.get(packageName);
            if (actions != null) {
                for (String action : actions) {
                    actionsJson.put(action);
                }
            }
            root.put("actions", actionsJson);
            return root.toString();
        } catch (Exception e) {
            return "{\"error\":\"dump_failed\"}";
        }
    }

    private void loadPackageUnsafe(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        final long ident = Binder.clearCallingIdentity();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            if (ai.metaData == null) {
                mLoadStatus.put(packageName, "no_meta_data");
                return;
            }
            int resId = ai.metaData.getInt(META_NAME, 0);
            if (resId == 0) {
                mLoadStatus.put(packageName, "meta_missing:" + META_NAME);
                return;
            }
            Resources res = pm.getResourcesForApplication(ai);
            try (XmlResourceParser parser = res.getXml(resId)) {
                if (parser == null) {
                    mLoadStatus.put(packageName, "parser_null:0x" + Integer.toHexString(resId));
                    return;
                }
                parseSensitivityXml(packageName, parser);
            }
            int fieldCount = mFields.containsKey(packageName) ? mFields.get(packageName).size() : 0;
            int actionCount = mActions.containsKey(packageName) ? mActions.get(packageName).size() : 0;
            mLoadStatus.put(packageName, "ok:res=0x" + Integer.toHexString(resId)
                    + ",fields=" + fieldCount + ",actions=" + actionCount);
        } catch (PackageManager.NameNotFoundException e) {
            mLoadStatus.put(packageName, "package_not_installed");
            Log.d(TAG, "Package not installed: " + packageName);
        } catch (Exception e) {
            mLoadStatus.put(packageName, "exception:" + e.getClass().getSimpleName()
                    + ":" + e.getMessage());
            Log.w(TAG, "Failed to load sensitivity for " + packageName, e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void parseSensitivityXml(String packageName, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        Map<String, FieldSpec> fields = new HashMap<>();
        Set<String> actions = new HashSet<>();
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String n = parser.getName();
                if ("field".equals(n)) {
                    String rid = getStringAttribute(parser, "resourceId");
                    boolean sensitive = getBooleanAttribute(parser, "sensitive", false);
                    boolean sink = getBooleanAttribute(parser, "sink", false);
                    String desc = getStringAttribute(parser, "description");
                    if (!TextUtils.isEmpty(rid)) {
                        fields.put(rid,
                                new FieldSpec(sensitive, sink, desc));
                    }
                } else if ("action".equals(n)) {
                    String rid = getStringAttribute(parser, "resourceId");
                    if (!TextUtils.isEmpty(rid)) {
                        actions.add(rid);
                    }
                }
            }
            eventType = parser.next();
        }
        if (!fields.isEmpty()) {
            mFields.put(packageName, new HashMap<>(fields));
        }
        if (!actions.isEmpty()) {
            mActions.put(packageName, new HashSet<>(actions));
        }
    }

    private static String getStringAttribute(XmlResourceParser parser, String name) {
        String byName = parser.getAttributeValue(XmlPullParser.NO_NAMESPACE, name);
        if (!TextUtils.isEmpty(byName)) {
            return byName;
        }
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (name.equals(parser.getAttributeName(i))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    private static boolean getBooleanAttribute(XmlResourceParser parser, String name,
            boolean defaultValue) {
        boolean byName = parser.getAttributeBooleanValue(
                XmlPullParser.NO_NAMESPACE, name, defaultValue);
        if (byName != defaultValue) {
            return byName;
        }
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (name.equals(parser.getAttributeName(i))) {
                return parser.getAttributeBooleanValue(i, defaultValue);
            }
        }
        return defaultValue;
    }

    static String shortenResourceId(String resourceId) {
        if (resourceId == null) {
            return "";
        }
        int slash = resourceId.indexOf('/');
        if (slash >= 0 && slash + 1 < resourceId.length()) {
            return resourceId.substring(slash + 1);
        }
        return resourceId;
    }
}
