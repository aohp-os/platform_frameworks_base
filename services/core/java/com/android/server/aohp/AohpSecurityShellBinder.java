/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Base64;

import org.json.JSONException;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * adb shell cmd aohp_security_cmd ... (shell / root / system only).
 */
public final class AohpSecurityShellBinder extends android.os.Binder {

    public static final String SERVICE_NAME = "aohp_security_cmd";

    private final AohpSecurityBridgeService mBridge;
    private final AohpSecurityAuditLog mAudit;
    private final AohpSensitivityRegistryService mRegistry;
    private final AohpTaintTrackerService mTaint;

    public AohpSecurityShellBinder(AohpSecurityBridgeService bridge,
            AohpSecurityAuditLog audit,
            AohpSensitivityRegistryService registry,
            AohpTaintTrackerService taint) {
        mBridge = bridge;
        mAudit = audit;
        mRegistry = registry;
        mTaint = taint;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, android.os.ShellCallback callback,
            android.os.ResultReceiver resultReceiver) throws RemoteException {
        (new AohpSecurityShellCmd()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    private final class AohpSecurityShellCmd extends ShellCommand {

        @Override
        public int onCommand(String cmd) {
            int uid = Binder.getCallingUid();
            if (uid != Process.SHELL_UID && uid != Process.ROOT_UID
                    && uid != Process.SYSTEM_UID) {
                getErrPrintWriter().println("permission denied");
                return -1;
            }
            PrintWriter pw = getOutPrintWriter();
            if (cmd == null || "help".equals(cmd) || "-h".equals(cmd)) {
                pw.println("usage: cmd aohp_security_cmd [subcommand]");
                pw.println("  consent complete <consentId> <true|false>");
                pw.println("  consent state <consentId>");
                pw.println("  consent reset-test-state");
                pw.println("  audit clear");
                pw.println("  audit tail <n>");
                pw.println("  audit json-tail <n>");
                pw.println("  registry reload <packageName>");
                pw.println("  registry dump <packageName>");
                pw.println("  registry skill-policy <skillName>");
                pw.println("  security register-skill-b64 <skillName> <base64utf8>");
                pw.println("  security check-skill-output-b64 <skillName> <base64utf8>");
                pw.println(
                        "  security check-skill-input <skillName> <paramName> <value words...>");
                pw.println("  security filter-file-list-b64 <base64utf8>");
                pw.println("  security check-file-share <devicePath> <targetPackage>");
                pw.println("  security check-file-read <devicePath>");
                pw.println("  security check-file-write <devicePath>");
                pw.println("  security sanitize-event-b64 <base64utf8>");
                pw.println("  taint sensitive");
                pw.println("  help");
                return 0;
            }
            switch (cmd) {
                case "consent": {
                    String sub = getNextArgRequired();
                    if ("complete".equals(sub)) {
                        String cid = getNextArgRequired();
                        String ok = getNextArgRequired();
                        boolean approved = Boolean.parseBoolean(ok);
                        mBridge.completeConsentTrusted(cid, approved);
                        pw.println("ok");
                        return 0;
                    }
                    if ("state".equals(sub)) {
                        String cid = getNextArgRequired();
                        pw.println(mBridge.getConsentStateTrusted(cid));
                        return 0;
                    }
                    if ("reset-test-state".equals(sub)) {
                        mBridge.resetTestConsentStateTrusted();
                        pw.println("ok");
                        return 0;
                    }
                    return -1;
                }
                case "audit": {
                    String sub = getNextArgRequired();
                    if ("clear".equals(sub)) {
                        mAudit.clear();
                        pw.println("ok");
                        return 0;
                    }
                    if ("tail".equals(sub)) {
                        String n = peekNextArg();
                        int lines = 50;
                        if (n != null) {
                            lines = Integer.parseInt(getNextArgRequired());
                        }
                        pw.print(mAudit.tail(lines));
                        return 0;
                    }
                    if ("json-tail".equals(sub)) {
                        String n = peekNextArg();
                        int lines = 50;
                        if (n != null) {
                            lines = Integer.parseInt(getNextArgRequired());
                        }
                        try {
                            pw.println(mAudit.tailJson(lines));
                        } catch (JSONException e) {
                            pw.println("[]");
                        }
                        return 0;
                    }
                    return -1;
                }
                case "registry": {
                    String sub = getNextArgRequired();
                    if ("reload".equals(sub)) {
                        String pkg = getNextArgRequired();
                        mRegistry.reloadAppManifestTrusted(pkg);
                        pw.println("ok");
                        return 0;
                    }
                    if ("dump".equals(sub)) {
                        String pkg = getNextArgRequired();
                        pw.println(mRegistry.dumpPackageJsonTrusted(pkg));
                        return 0;
                    }
                    if ("skill-policy".equals(sub)) {
                        String name = getNextArgRequired();
                        pw.println(mRegistry.getSkillPolicyJsonInternal(name));
                        return 0;
                    }
                    return -1;
                }
                case "security": {
                    String sub = getNextArgRequired();
                    switch (sub) {
                        case "register-skill-b64": {
                            String skill = getNextArgRequired();
                            String b64 = getNextArgRequired();
                            byte[] raw = Base64.decode(b64.getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT);
                            String json =
                                    new String(raw, StandardCharsets.UTF_8);
                            pw.println(mBridge.registerSkillPolicyTrusted(skill, json));
                            return 0;
                        }
                        case "check-skill-output-b64": {
                            String skill = getNextArgRequired();
                            String b64 = getNextArgRequired();
                            byte[] raw = Base64.decode(b64.getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT);
                            String payload =
                                    new String(raw, StandardCharsets.UTF_8);
                            pw.println(mBridge.checkSkillOutputPolicyTrusted(skill, payload));
                            return 0;
                        }
                        case "check-skill-input": {
                            String skill = getNextArgRequired();
                            String param = getNextArgRequired();
                            String first = getNextArgRequired();
                            StringBuilder vb = new StringBuilder(first);
                            String extra;
                            while ((extra = getNextArg()) != null) {
                                vb.append(' ').append(extra);
                            }
                            pw.println(
                                    mBridge.checkSkillInputPolicyTrusted(
                                            skill, param, vb.toString()));
                            return 0;
                        }
                        case "filter-file-list-b64": {
                            String b64 = getNextArgRequired();
                            byte[] raw = Base64.decode(b64.getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT);
                            String payload =
                                    new String(raw, StandardCharsets.UTF_8);
                            pw.println(mBridge.filterFileListJsonTrusted(payload));
                            return 0;
                        }
                        case "check-file-share": {
                            String path = getNextArgRequired();
                            String targetPkg = getNextArgRequired();
                            pw.println(mBridge.checkFileSharePolicyTrusted(path, targetPkg));
                            return 0;
                        }
                        case "check-file-read": {
                            String path = getNextArgRequired();
                            pw.println(mBridge.checkFileReadPolicyTrusted(path));
                            return 0;
                        }
                        case "check-file-write": {
                            String path = getNextArgRequired();
                            pw.println(mBridge.checkFileWritePolicyTrusted(path));
                            return 0;
                        }
                        case "sanitize-event-b64": {
                            String b64 = getNextArgRequired();
                            byte[] raw = Base64.decode(b64.getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT);
                            String payload =
                                    new String(raw, StandardCharsets.UTF_8);
                            pw.println(mBridge.sanitizeEventJsonTrustedShell(payload));
                            return 0;
                        }
                        default:
                            return -1;
                    }
                }
                case "taint": {
                    String sub = getNextArgRequired();
                    if ("sensitive".equals(sub)) {
                        pw.println(mTaint.listSensitiveTaintsTrusted());
                        return 0;
                    }
                    return -1;
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            onCommand("help");
        }
    }
}
