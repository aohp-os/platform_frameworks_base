/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.android.server.accessibility;

import android.graphics.Rect;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.JsonWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.server.accessibility.AccessibilityWindowManager.RemoteAccessibilityConnection;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Privileged UI tree JSON for AOHP: fetches {@link AccessibilityNodeInfo} from
 * {@link IAccessibilityInteractionConnection} in system_server.
 */
public final class AohpUiTreeDumper {
    private static final String TAG = "AohpUiTree";

    public static final int FLAG_FILTER_DECORATIVE = 0x1;
    public static final int FLAG_INCLUDE_OFFSCREEN_MARKS = 0x2;
    public static final int FLAG_MARK_VISUAL = 0x4;
    public static final int FLAG_APPLICATION_ONLY = 0x8;

    private static final int MAX_ROUNDS = 200;
    private static final int TIMEOUT_MS = 5000;
    private static final int PREFETCH_FLAGS =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                    | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
                    | AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS
                    | AccessibilityNodeInfo.FLAG_SERVICE_IS_ACCESSIBILITY_TOOL;

    private static final AtomicInteger sNextInteractionId = new AtomicInteger(1);

    private AohpUiTreeDumper() {
    }

    /** Record used before JSON serialization. */
    private static final class NodeRec {
        int id;
        int windowId;
        Integer parentId;
        final List<Integer> children = new ArrayList<>();
        String className;
        String pkg;
        String resourceId;
        String text;
        String contentDescription;
        int left;
        int top;
        int right;
        int bottom;
        boolean visible;
        boolean enabled;
        boolean focusable;
        boolean focused;
        boolean clickable;
        boolean longClickable;
        boolean scrollable;
        boolean checkable;
        boolean checked;
        boolean selected;
        boolean editable;
        boolean password;
        boolean importantForA11y;
        final List<String> marks = new ArrayList<>();
    }

    private static final class WindowRec {
        int windowId;
        String type;
        int layer;
        Rect bounds = new Rect();
        boolean focused;
        boolean active;
        boolean accessibilityFocused;
        boolean pictureInPicture;
        boolean secure;
        String pkg;
        int rootOutId = -1;
    }

    public static String buildJsonFromWindows(AccessibilityManagerService service,
            List<AccessibilityWindowInfo> windows, int userId, int displayId, int flags) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        ArrayList<WindowRec> windowRecs = new ArrayList<>();
        ArrayList<NodeRec> nodes = new ArrayList<>();
        int nextId = 1;
        boolean truncated = false;
        int totalRounds = 0;

        for (int wi = 0; wi < windows.size(); wi++) {
            AccessibilityWindowInfo win = windows.get(wi);
            if (win == null) {
                continue;
            }
            if ((flags & FLAG_APPLICATION_ONLY) != 0
                    && win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            int wid = win.getId();
            RemoteAccessibilityConnection conn = service.getAccessibilityConnectionForDump(
                    userId, wid);
            if (conn == null || conn.getRemote() == null) {
                Log.w(TAG, "no connection for windowId=" + wid);
                continue;
            }

            AtomicInteger rounds = new AtomicInteger(0);
            LongSparseArray<AccessibilityNodeInfo> bySource;
            try {
                bySource = fetchEntireWindow(conn, rounds, deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                truncated = true;
                continue;
            }
            totalRounds += rounds.get();
            if (rounds.get() >= MAX_ROUNDS || SystemClock.uptimeMillis() >= deadline) {
                truncated = true;
            }

            WindowRec wr = new WindowRec();
            wr.windowId = wid;
            wr.type = windowTypeToString(win.getType());
            wr.layer = win.getLayer();
            win.getBoundsInScreen(wr.bounds);
            wr.focused = win.isFocused();
            wr.active = win.isActive();
            wr.accessibilityFocused = win.isAccessibilityFocused();
            wr.pictureInPicture = win.isInPictureInPictureMode();
            wr.secure = inferSecure(bySource);

            HashMap<Long, NodeRec> sidToRec = new HashMap<>();
            for (int i = 0; i < bySource.size(); i++) {
                AccessibilityNodeInfo raw = bySource.valueAt(i);
                NodeRec nr = new NodeRec();
                nr.id = nextId++;
                nr.windowId = wid;
                fillFromA11yInfo(nr, raw);
                sidToRec.put(raw.getSourceNodeId(), nr);
                nodes.add(nr);
            }

            AccessibilityNodeInfo rootLike = findRootLike(bySource);
            if (rootLike != null && rootLike.getPackageName() != null) {
                wr.pkg = rootLike.getPackageName().toString();
            }

            for (int i = 0; i < bySource.size(); i++) {
                long sid = bySource.keyAt(i);
                AccessibilityNodeInfo raw = bySource.valueAt(i);
                NodeRec self = sidToRec.get(sid);
                if (self == null) {
                    continue;
                }
                long parentSid = raw.getParentNodeId();
                NodeRec parent = sidToRec.get(parentSid);
                self.parentId = (parent != null) ? parent.id : null;

                int cc = raw.getChildCount();
                for (int c = 0; c < cc; c++) {
                    long childSid = raw.getChildId(c);
                    NodeRec child = sidToRec.get(childSid);
                    if (child != null) {
                        self.children.add(child.id);
                    }
                }
                if (self.parentId == null && wr.rootOutId < 0) {
                    wr.rootOutId = self.id;
                }
            }
            if (wr.rootOutId < 0 && !sidToRec.isEmpty()) {
                wr.rootOutId = sidToRec.values().iterator().next().id;
            }

            applyFlagsToNodes(nodes, flags, wid);
            windowRecs.add(wr);
        }

        // Decorative filter: remove matching nodes and rewire (only nodes of last processed window
        // were annotated — apply globally to nodes list)
        if ((flags & FLAG_FILTER_DECORATIVE) != 0) {
            removeDecorativeNodes(nodes);
        }

        try {
            return writeJson(displayId, flags, windowRecs, nodes, truncated, totalRounds);
        } catch (IOException e) {
            Log.e(TAG, "writeJson", e);
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    private static void fillFromA11yInfo(NodeRec nr, AccessibilityNodeInfo n) {
        if (n.getClassName() != null) {
            nr.className = n.getClassName().toString();
        }
        if (n.getPackageName() != null) {
            nr.pkg = n.getPackageName().toString();
        }
        if (n.getViewIdResourceName() != null) {
            nr.resourceId = n.getViewIdResourceName();
        }
        if (n.getText() != null) {
            nr.text = n.getText().toString();
        }
        if (n.getContentDescription() != null) {
            nr.contentDescription = n.getContentDescription().toString();
        }
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        nr.left = b.left;
        nr.top = b.top;
        nr.right = b.right;
        nr.bottom = b.bottom;
        nr.visible = n.isVisibleToUser();
        nr.enabled = n.isEnabled();
        nr.focusable = n.isFocusable();
        nr.focused = n.isFocused();
        nr.clickable = n.isClickable();
        nr.longClickable = n.isLongClickable();
        nr.scrollable = n.isScrollable();
        nr.checkable = n.isCheckable();
        nr.checked = n.isChecked();
        nr.selected = n.isSelected();
        nr.editable = n.isEditable();
        nr.password = n.isPassword();
        nr.importantForA11y = n.isImportantForAccessibility();
    }

    private static void applyFlagsToNodes(ArrayList<NodeRec> nodes, int flags, int onlyWindowId) {
        for (int i = 0; i < nodes.size(); i++) {
            NodeRec n = nodes.get(i);
            if (n.windowId != onlyWindowId) {
                continue;
            }
            if ((flags & FLAG_MARK_VISUAL) != 0) {
                markVisual(n);
            }
            if ((flags & FLAG_INCLUDE_OFFSCREEN_MARKS) != 0) {
                // Heuristic: scrollable container may host offscreen children — mark scrollables
                if (n.scrollable) {
                    n.marks.add("scrollable_container");
                }
            }
        }
    }

    private static void markVisual(NodeRec n) {
        String cn = n.className != null ? n.className : "";
        if (cn.endsWith("Button") || cn.contains("Button")) {
            n.marks.add("visual:button");
        } else if (cn.contains("EditText") || cn.contains("TextInput")) {
            n.marks.add("visual:input");
        } else if (cn.contains("ImageView") || cn.contains("Image")) {
            n.marks.add("visual:image");
        }
    }

    private static void removeDecorativeNodes(ArrayList<NodeRec> nodes) {
        HashSet<Integer> remove = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeRec n = nodes.get(i);
            if (!n.importantForA11y && !n.clickable && !n.scrollable && !n.editable
                    && (n.text == null || n.text.isEmpty())
                    && (n.contentDescription == null || n.contentDescription.isEmpty())) {
                remove.add(n.id);
            }
        }
        if (remove.isEmpty()) {
            return;
        }
        ArrayList<NodeRec> kept = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeRec n = nodes.get(i);
            if (!remove.contains(n.id)) {
                kept.add(n);
            }
        }
        // Rewire children / parents
        for (int i = 0; i < kept.size(); i++) {
            NodeRec n = kept.get(i);
            ArrayList<Integer> newCh = new ArrayList<>();
            for (int j = 0; j < n.children.size(); j++) {
                int cid = n.children.get(j);
                if (!remove.contains(cid)) {
                    newCh.add(cid);
                }
            }
            n.children.clear();
            n.children.addAll(newCh);
            if (n.parentId != null && remove.contains(n.parentId)) {
                n.parentId = null;
            }
        }
        nodes.clear();
        nodes.addAll(kept);
    }

    private static String writeJson(int displayId, int flags, ArrayList<WindowRec> windows,
            ArrayList<NodeRec> nodes, boolean truncated, int rounds) throws IOException {
        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.setIndent("  ");
        jw.beginObject();
        jw.name("displayId").value(displayId);
        jw.name("flags").value(flags);
        jw.name("stats");
        jw.beginObject();
        jw.name("windowCount").value(windows.size());
        jw.name("nodeCount").value(nodes.size());
        jw.name("truncated").value(truncated);
        jw.name("fetchRounds").value(rounds);
        jw.endObject();

        jw.name("windows");
        jw.beginArray();
        for (int i = 0; i < windows.size(); i++) {
            WindowRec w = windows.get(i);
            jw.beginObject();
            jw.name("windowId").value(w.windowId);
            jw.name("type").value(w.type);
            jw.name("layer").value(w.layer);
            writeBounds(jw, w.bounds);
            jw.name("focused").value(w.focused);
            jw.name("active").value(w.active);
            jw.name("inputFocused").value(w.accessibilityFocused);
            jw.name("pip").value(w.pictureInPicture);
            jw.name("secure").value(w.secure);
            writeNullableString(jw, "package", w.pkg);
            jw.name("rootNodeId").value(w.rootOutId);
            jw.endObject();
        }
        jw.endArray();

        jw.name("nodes");
        jw.beginArray();
        for (int i = 0; i < nodes.size(); i++) {
            writeNode(jw, nodes.get(i));
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }

    private static void writeBounds(JsonWriter jw, Rect r) throws IOException {
        jw.name("bounds");
        jw.beginArray();
        jw.beginArray();
        jw.value(r.left);
        jw.value(r.top);
        jw.endArray();
        jw.beginArray();
        jw.value(r.right);
        jw.value(r.bottom);
        jw.endArray();
        jw.endArray();
    }

    private static void writeNode(JsonWriter jw, NodeRec n) throws IOException {
        jw.beginObject();
        jw.name("id").value(n.id);
        jw.name("windowId").value(n.windowId);
        jw.name("parent");
        if (n.parentId == null) {
            jw.nullValue();
        } else {
            jw.value(n.parentId);
        }
        jw.name("children");
        jw.beginArray();
        for (int i = 0; i < n.children.size(); i++) {
            jw.value(n.children.get(i));
        }
        jw.endArray();
        writeNullableString(jw, "class", n.className);
        writeNullableString(jw, "package", n.pkg);
        writeNullableString(jw, "resourceId", n.resourceId);
        writeNullableString(jw, "text", n.text);
        writeNullableString(jw, "contentDescription", n.contentDescription);
        jw.name("bounds");
        jw.beginArray();
        jw.beginArray();
        jw.value(n.left);
        jw.value(n.top);
        jw.endArray();
        jw.beginArray();
        jw.value(n.right);
        jw.value(n.bottom);
        jw.endArray();
        jw.endArray();
        jw.name("visible").value(n.visible);
        jw.name("enabled").value(n.enabled);
        jw.name("focusable").value(n.focusable);
        jw.name("focused").value(n.focused);
        jw.name("clickable").value(n.clickable);
        jw.name("longClickable").value(n.longClickable);
        jw.name("scrollable").value(n.scrollable);
        jw.name("checkable").value(n.checkable);
        jw.name("checked").value(n.checked);
        jw.name("selected").value(n.selected);
        jw.name("editable").value(n.editable);
        jw.name("password").value(n.password);
        jw.name("marks");
        jw.beginArray();
        for (int i = 0; i < n.marks.size(); i++) {
            jw.value(n.marks.get(i));
        }
        jw.endArray();
        jw.endObject();
    }

    private static void writeNullableString(JsonWriter jw, String name, String v)
            throws IOException {
        jw.name(name);
        if (v == null) {
            jw.nullValue();
        } else {
            jw.value(v);
        }
    }

    @SuppressWarnings("unused")
    private static boolean inferSecure(LongSparseArray<AccessibilityNodeInfo> unusedBySource) {
        // Window-level FLAG_SECURE is not exposed on AccessibilityWindowInfo; keep conservative
        // default. Callers may fall back to screenshot/OCR when needed.
        return false;
    }

    private static AccessibilityNodeInfo findRootLike(LongSparseArray<AccessibilityNodeInfo> map) {
        for (int i = 0; i < map.size(); i++) {
            AccessibilityNodeInfo n = map.valueAt(i);
            long p = n.getParentNodeId();
            AccessibilityNodeInfo parent = map.get(p);
            if (parent == null) {
                return n;
            }
        }
        return map.size() > 0 ? map.valueAt(0) : null;
    }

    private static LongSparseArray<AccessibilityNodeInfo> fetchEntireWindow(
            RemoteAccessibilityConnection conn,
            AtomicInteger rounds,
            long deadlineMs) throws InterruptedException {
        LongSparseArray<AccessibilityNodeInfo> map = new LongSparseArray<>();
        IAccessibilityInteractionConnection remote = conn.getRemote();

        List<AccessibilityNodeInfo> rootBatch =
                fetchOnce(remote, AccessibilityNodeInfo.ROOT_NODE_ID, rounds, deadlineMs);
        mergeIntoMap(rootBatch, map);

        ArrayDeque<Long> queue = new ArrayDeque<>();
        enqueueMissingChildren(map, queue);

        while (!queue.isEmpty()
                && rounds.get() < MAX_ROUNDS
                && SystemClock.uptimeMillis() < deadlineMs) {
            long sid = queue.pollFirst();
            if (map.indexOfKey(sid) >= 0) {
                continue;
            }
            List<AccessibilityNodeInfo> batch = fetchOnce(remote, sid, rounds, deadlineMs);
            mergeIntoMap(batch, map);
            enqueueMissingChildren(map, queue);
        }
        return map;
    }

    private static void enqueueMissingChildren(LongSparseArray<AccessibilityNodeInfo> map,
            ArrayDeque<Long> queue) {
        HashSet<Long> queued = new HashSet<>();
        for (int i = 0; i < map.size(); i++) {
            AccessibilityNodeInfo n = map.valueAt(i);
            int cc = n.getChildCount();
            for (int c = 0; c < cc; c++) {
                long cid = n.getChildId(c);
                if (map.indexOfKey(cid) < 0 && queued.add(cid)) {
                    queue.addLast(cid);
                }
            }
        }
    }

    private static void mergeIntoMap(List<AccessibilityNodeInfo> batch,
            LongSparseArray<AccessibilityNodeInfo> map) {
        if (batch == null) {
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            AccessibilityNodeInfo n = batch.get(i);
            if (n != null) {
                map.put(n.getSourceNodeId(), new AccessibilityNodeInfo(n));
            }
        }
    }

    private static List<AccessibilityNodeInfo> fetchOnce(IAccessibilityInteractionConnection remote,
            long accessibilityNodeId,
            AtomicInteger rounds,
            long deadlineMs) throws InterruptedException {
        rounds.incrementAndGet();
        int iid = sNextInteractionId.getAndIncrement();
        CollectCallback cb = new CollectCallback();
        try {
            remote.findAccessibilityNodeInfoByAccessibilityId(
                    accessibilityNodeId,
                    null,
                    iid,
                    cb,
                    PREFETCH_FLAGS,
                    Process.myPid(),
                    Process.myTid(),
                    null,
                    null,
                    null);
        } catch (RemoteException e) {
            Log.w(TAG, "findAccessibilityNodeInfoByAccessibilityId: " + e.getMessage());
            return new ArrayList<>();
        }
        long wait = Math.max(1L, deadlineMs - SystemClock.uptimeMillis());
        cb.await(Math.min(wait, TIMEOUT_MS));
        return cb.takeNodes();
    }

    private static final class CollectCallback extends IAccessibilityInteractionConnectionCallback.Stub {
        private final ArrayList<AccessibilityNodeInfo> mNodes = new ArrayList<>();
        private final CountDownLatch mLatch = new CountDownLatch(1);

        void await(long timeoutMs) throws InterruptedException {
            mLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        List<AccessibilityNodeInfo> takeNodes() {
            return mNodes;
        }

        private void addAll(List<AccessibilityNodeInfo> infos) {
            if (infos != null) {
                for (int i = 0; i < infos.size(); i++) {
                    AccessibilityNodeInfo n = infos.get(i);
                    if (n != null) {
                        mNodes.add(new AccessibilityNodeInfo(n));
                    }
                }
            }
            mLatch.countDown();
        }

        @Override
        public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info, int interactionId) {
            if (info != null) {
                mNodes.add(new AccessibilityNodeInfo(info));
            }
            mLatch.countDown();
        }

        @Override
        public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
            addAll(infos);
        }

        @Override
        public void setPrefetchAccessibilityNodeInfoResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
            addAll(infos);
        }

        @Override
        public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId) {
            mLatch.countDown();
        }

        @Override
        public void sendTakeScreenshotOfWindowError(int errorCode, int interactionId) {
            mLatch.countDown();
        }

        @Override
        public void sendAttachOverlayResult(int result, int interactionId) {
            mLatch.countDown();
        }
    }

    private static String windowTypeToString(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "ACCESSIBILITY_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT_SCREEN_DIVIDER";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }
}
