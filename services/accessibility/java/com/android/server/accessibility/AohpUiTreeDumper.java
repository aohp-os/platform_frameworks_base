/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.android.server.accessibility;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.JsonWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONException;
import org.json.JSONObject;
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
        Integer rangeType;
        Float rangeMin;
        Float rangeMax;
        Float rangeCurrent;
        Float rangePercent;
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

    public static String setNodeProgressFromWindows(AccessibilityManagerService service,
            List<AccessibilityWindowInfo> windows, int userId, int displayId, int nodeId,
            float percent, int flags) {
        if (nodeId <= 0) {
            return writeSetProgressError(displayId, nodeId, "bad_node_id",
                    "nodeId must be a positive id from ui.tree");
        }
        if (Float.isNaN(percent) || percent < 0f || percent > 100f) {
            return writeSetProgressError(displayId, nodeId, "bad_percent",
                    "percent must be 0..100");
        }

        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        int nextId = 1;
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
                continue;
            }

            LongSparseArray<AccessibilityNodeInfo> bySource;
            try {
                bySource = fetchEntireWindow(conn, new AtomicInteger(0), deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return writeSetProgressError(displayId, nodeId, "interrupted", e.getMessage());
            }

            for (int i = 0; i < bySource.size(); i++) {
                AccessibilityNodeInfo raw = bySource.valueAt(i);
                int currentId = nextId++;
                if (currentId != nodeId) {
                    continue;
                }
                return performSetProgress(conn.getRemote(), raw, displayId, wid, nodeId, percent,
                        deadline);
            }
        }

        return writeSetProgressError(displayId, nodeId, "node_not_found", "id=" + nodeId);
    }

    /**
     * AOHP: clear text with {@link AccessibilityNodeInfo#ACTION_SET_TEXT} (empty argument).
     * {@code nodeId > 0}: same id space as {@link #buildJsonFromWindows}. {@code nodeId <= 0}:
     * focused editable on the display (prefers {@link AccessibilityWindowInfo#isFocused()} windows).
     */
    public static String clearEditableTextFromWindows(AccessibilityManagerService service,
            List<AccessibilityWindowInfo> windows, int userId, int displayId, int nodeId, int flags) {
        if (nodeId > 0) {
            return clearEditableByTreeId(service, windows, userId, displayId, nodeId, flags);
        }
        return clearFocusedEditableFromWindows(service, windows, userId, displayId, flags);
    }

    private static String clearEditableByTreeId(AccessibilityManagerService service,
            List<AccessibilityWindowInfo> windows, int userId, int displayId, int nodeId, int flags) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        int nextId = 1;
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
                continue;
            }

            LongSparseArray<AccessibilityNodeInfo> bySource;
            try {
                bySource = fetchEntireWindow(conn, new AtomicInteger(0), deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return writeClearTextError(displayId, nodeId, "interrupted", e.getMessage());
            }

            for (int i = 0; i < bySource.size(); i++) {
                AccessibilityNodeInfo raw = bySource.valueAt(i);
                int currentId = nextId++;
                if (currentId != nodeId) {
                    continue;
                }
                return tryClearEditableChain(conn.getRemote(), bySource, raw, displayId, wid,
                        nodeId, deadline);
            }
        }

        return writeClearTextError(displayId, nodeId, "node_not_found", "id=" + nodeId);
    }

    private static String clearFocusedEditableFromWindows(AccessibilityManagerService service,
            List<AccessibilityWindowInfo> windows, int userId, int displayId, int flags) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        ArrayList<Integer> focused = new ArrayList<>();
        ArrayList<Integer> other = new ArrayList<>();
        for (int wi = 0; wi < windows.size(); wi++) {
            AccessibilityWindowInfo win = windows.get(wi);
            if (win == null) {
                continue;
            }
            if ((flags & FLAG_APPLICATION_ONLY) != 0
                    && win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            if (win.isFocused()) {
                focused.add(wi);
            } else {
                other.add(wi);
            }
        }
        ArrayList<Integer> order = new ArrayList<>(focused.size() + other.size());
        order.addAll(focused);
        order.addAll(other);

        for (int oi = 0; oi < order.size(); oi++) {
            int wi = order.get(oi);
            AccessibilityWindowInfo win = windows.get(wi);
            int wid = win.getId();
            RemoteAccessibilityConnection conn = service.getAccessibilityConnectionForDump(
                    userId, wid);
            if (conn == null || conn.getRemote() == null) {
                continue;
            }
            LongSparseArray<AccessibilityNodeInfo> bySource;
            try {
                bySource = fetchEntireWindow(conn, new AtomicInteger(0), deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return writeClearTextError(displayId, 0, "interrupted", e.getMessage());
            }
            for (int i = 0; i < bySource.size(); i++) {
                AccessibilityNodeInfo raw = bySource.valueAt(i);
                if (!raw.isFocused()) {
                    continue;
                }
                String attempt = tryClearEditableChain(conn.getRemote(), bySource, raw, displayId,
                        wid, 0, deadline);
                if (jsonClearSucceeded(attempt)) {
                    return attempt;
                }
            }
        }
        return writeClearTextError(displayId, 0, "no_focused_editable",
                "No focused text input: the focused node is not an editable field "
                        + "(no ACTION_SET_TEXT path). Focus an EditText or pick a node id from ui.tree.");
    }

    private static boolean jsonClearSucceeded(String json) {
        if (json == null) {
            return false;
        }
        try {
            return new JSONObject(json).optBoolean("success", false);
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Try {@code ACTION_SET_TEXT} on {@code start} and editable ancestors (in {@code map}).
     */
    private static String tryClearEditableChain(IAccessibilityInteractionConnection remote,
            LongSparseArray<AccessibilityNodeInfo> map, AccessibilityNodeInfo start, int displayId,
            int windowId, int responseNodeId, long deadlineMs) {
        boolean sawEditableCandidate = false;
        String lastFailureDetail = null;
        AccessibilityNodeInfo cur = start;
        for (int hop = 0; hop < 16 && cur != null; hop++) {
            if (cur.isEditable() || hasAction(cur, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
                sawEditableCandidate = true;
                String r = performClearText(remote, cur, displayId, windowId, responseNodeId,
                        deadlineMs);
                if (jsonClearSucceeded(r)) {
                    return r;
                }
                lastFailureDetail = summarizeClearFailureJson(r);
            }
            long psid = cur.getParentNodeId();
            AccessibilityNodeInfo parent = map.get(psid);
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        if (!sawEditableCandidate) {
            String hint = describeNonTextInputNode(start);
            if (responseNodeId > 0) {
                return writeClearTextError(displayId, responseNodeId, "not_text_input",
                        "node id=" + responseNodeId + " is not a text input field (" + hint
                                + "). Choose a ui.tree node with editable=true or class EditText/"
                                + "TextInput (or a descendant of the field).");
            }
            return writeClearTextError(displayId, 0, "not_text_input",
                    "Focused node is not a text input field (" + hint
                            + "). Focus an EditText or use act.clear_node / act.input_node with "
                            + "the correct node id.");
        }
        String extra = (lastFailureDetail != null && !lastFailureDetail.isEmpty())
                ? (" Last attempt: " + lastFailureDetail)
                : "";
        return writeClearTextError(displayId, responseNodeId, "not_clearable",
                "ACTION_SET_TEXT failed on editable candidate(s) in this node chain." + extra);
    }

    /** Short hint for logs / errors: class name and editable flag. */
    private static String describeNonTextInputNode(AccessibilityNodeInfo n) {
        if (n == null) {
            return "null node";
        }
        CharSequence cn = n.getClassName();
        String cls = cn != null ? cn.toString() : "unknown";
        return "class=" + cls + ", editable=" + n.isEditable();
    }

    private static String summarizeClearFailureJson(String json) {
        if (json == null) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(json);
            String err = o.optString("error", "");
            String msg = o.optString("message", "");
            if (!msg.isEmpty()) {
                return err.isEmpty() ? msg : err + ": " + msg;
            }
            return err;
        } catch (JSONException e) {
            return "";
        }
    }

    private static String performClearText(IAccessibilityInteractionConnection remote,
            AccessibilityNodeInfo node, int displayId, int windowId, int responseNodeId,
            long deadlineMs) {
        if (node == null) {
            return writeClearTextError(displayId, responseNodeId, "node_null", "null node");
        }
        if (node.isPassword()) {
            return writeClearTextError(displayId, responseNodeId, "password_field",
                    "refusing to clear password field via ACTION_SET_TEXT");
        }
        int actionSetText = AccessibilityNodeInfo.ACTION_SET_TEXT;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        int iid = sNextInteractionId.getAndIncrement();
        ActionCallback cb = new ActionCallback();
        try {
            remote.performAccessibilityAction(
                    node.getSourceNodeId(),
                    actionSetText,
                    args,
                    iid,
                    cb,
                    PREFETCH_FLAGS,
                    Process.myPid(),
                    Process.myTid());
        } catch (RemoteException e) {
            Log.w(TAG, "performAccessibilityAction ACTION_SET_TEXT: " + e.getMessage());
            return writeClearTextError(displayId, responseNodeId, "remote_exception", e.getMessage());
        }

        try {
            long wait = Math.max(1L, deadlineMs - SystemClock.uptimeMillis());
            cb.await(Math.min(wait, TIMEOUT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeClearTextError(displayId, responseNodeId, "interrupted", e.getMessage());
        }

        Boolean performed = cb.getResult();
        if (!Boolean.TRUE.equals(performed)) {
            return writeClearTextError(displayId, responseNodeId, "action_failed",
                    performed == null ? "no callback result" : "ACTION_SET_TEXT returned false");
        }
        return writeClearTextSuccess(displayId, windowId, responseNodeId, actionSetText);
    }

    private static String writeClearTextSuccess(int displayId, int windowId, int responseNodeId,
            int actionId) {
        try {
            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("success").value(true);
            jw.name("displayId").value(displayId);
            jw.name("windowId").value(windowId);
            jw.name("nodeId").value(responseNodeId);
            jw.name("action").value("ACTION_SET_TEXT");
            jw.name("actionId").value(actionId);
            jw.endObject();
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            return "{\"success\":true}";
        }
    }

    private static String writeClearTextError(int displayId, int nodeId, String code,
            String message) {
        try {
            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("success").value(false);
            jw.name("error").value(code != null ? code : "error");
            jw.name("message").value(message != null ? message : "");
            jw.name("displayId").value(displayId);
            jw.name("nodeId").value(nodeId);
            jw.endObject();
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            return "{\"success\":false,\"error\":\"serialize_failed\"}";
        }
    }

    private static String performSetProgress(IAccessibilityInteractionConnection remote,
            AccessibilityNodeInfo node, int displayId, int windowId, int nodeId, float percent,
            long deadlineMs) {
        if (node == null) {
            return writeSetProgressError(displayId, nodeId, "node_not_found", "id=" + nodeId);
        }
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range == null) {
            return writeSetProgressError(displayId, nodeId, "node_not_range",
                    "node has no AccessibilityNodeInfo.RangeInfo");
        }
        int actionSetProgress =
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId();
        if (!hasAction(node, actionSetProgress)) {
            return writeSetProgressError(displayId, nodeId, "action_unavailable",
                    "node does not expose ACTION_SET_PROGRESS");
        }

        float min = range.getMin();
        float max = range.getMax();
        if (Float.isNaN(min) || Float.isNaN(max) || max < min) {
            return writeSetProgressError(displayId, nodeId, "bad_range",
                    "node range is invalid");
        }
        float target = min + ((max - min) * percent / 100f);

        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target);
        int iid = sNextInteractionId.getAndIncrement();
        ActionCallback cb = new ActionCallback();
        try {
            remote.performAccessibilityAction(
                    node.getSourceNodeId(),
                    actionSetProgress,
                    args,
                    iid,
                    cb,
                    PREFETCH_FLAGS,
                    Process.myPid(),
                    Process.myTid());
        } catch (RemoteException e) {
            Log.w(TAG, "performAccessibilityAction ACTION_SET_PROGRESS: " + e.getMessage());
            return writeSetProgressError(displayId, nodeId, "remote_exception", e.getMessage());
        }

        try {
            long wait = Math.max(1L, deadlineMs - SystemClock.uptimeMillis());
            cb.await(Math.min(wait, TIMEOUT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeSetProgressError(displayId, nodeId, "interrupted", e.getMessage());
        }

        Boolean performed = cb.getResult();
        if (!Boolean.TRUE.equals(performed)) {
            return writeSetProgressError(displayId, nodeId, "action_failed",
                    performed == null ? "no callback result"
                            : "ACTION_SET_PROGRESS returned false");
        }
        return writeSetProgressSuccess(displayId, windowId, nodeId, percent, target, range);
    }

    private static boolean hasAction(AccessibilityNodeInfo node, int actionId) {
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (int i = 0; i < actions.size(); i++) {
            AccessibilityNodeInfo.AccessibilityAction action = actions.get(i);
            if (action != null && action.getId() == actionId) {
                return true;
            }
        }
        return false;
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
        AccessibilityNodeInfo.RangeInfo range = n.getRangeInfo();
        if (range != null) {
            nr.rangeType = range.getType();
            nr.rangeMin = range.getMin();
            nr.rangeMax = range.getMax();
            nr.rangeCurrent = range.getCurrent();
            if (!Float.isNaN(nr.rangeMin) && !Float.isNaN(nr.rangeMax)
                    && !Float.isNaN(nr.rangeCurrent) && nr.rangeMax > nr.rangeMin) {
                nr.rangePercent = ((nr.rangeCurrent - nr.rangeMin) * 100f)
                        / (nr.rangeMax - nr.rangeMin);
            }
        }
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
        if (n.rangeMin != null && n.rangeMax != null && n.rangeCurrent != null) {
            jw.name("range");
            jw.beginObject();
            if (n.rangeType != null) {
                jw.name("type").value(n.rangeType);
            }
            jw.name("min").value(n.rangeMin);
            jw.name("max").value(n.rangeMax);
            jw.name("currentValue").value(n.rangeCurrent);
            jw.name("currentPercent");
            if (n.rangePercent == null || Float.isNaN(n.rangePercent)
                    || Float.isInfinite(n.rangePercent)) {
                jw.nullValue();
            } else {
                jw.value(n.rangePercent);
            }
            jw.endObject();
        }
        jw.name("marks");
        jw.beginArray();
        for (int i = 0; i < n.marks.size(); i++) {
            jw.value(n.marks.get(i));
        }
        jw.endArray();
        jw.endObject();
    }

    private static String writeSetProgressSuccess(int displayId, int windowId, int nodeId,
            float percent, float targetValue, AccessibilityNodeInfo.RangeInfo range) {
        try {
            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("success").value(true);
            jw.name("displayId").value(displayId);
            jw.name("windowId").value(windowId);
            jw.name("nodeId").value(nodeId);
            jw.name("percent").value(percent);
            jw.name("targetValue").value(targetValue);
            jw.name("range");
            jw.beginObject();
            jw.name("min").value(range.getMin());
            jw.name("max").value(range.getMax());
            jw.name("currentBefore").value(range.getCurrent());
            jw.endObject();
            jw.name("action").value("ACTION_SET_PROGRESS");
            jw.endObject();
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            return "{\"success\":true}";
        }
    }

    private static String writeSetProgressError(int displayId, int nodeId, String code,
            String message) {
        try {
            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("success").value(false);
            jw.name("error").value(code != null ? code : "error");
            jw.name("message").value(message != null ? message : "");
            jw.name("displayId").value(displayId);
            jw.name("nodeId").value(nodeId);
            jw.endObject();
            jw.close();
            return sw.toString();
        } catch (IOException e) {
            return "{\"success\":false,\"error\":\"serialize_failed\"}";
        }
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

    private static final class ActionCallback extends IAccessibilityInteractionConnectionCallback.Stub {
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private volatile Boolean mSucceeded;

        void await(long timeoutMs) throws InterruptedException {
            mLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        Boolean getResult() {
            return mSucceeded;
        }

        @Override
        public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info,
                int interactionId) {
            mLatch.countDown();
        }

        @Override
        public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
            mLatch.countDown();
        }

        @Override
        public void setPrefetchAccessibilityNodeInfoResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
            mLatch.countDown();
        }

        @Override
        public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId) {
            mSucceeded = succeeded;
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
