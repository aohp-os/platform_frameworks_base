/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpSecurityBridge {
    String filterUiTreeJson(String rawTreeJson, String foregroundPackage, int displayId);
    String checkInputPolicy(String foregroundPackage,
            String targetResourceId, String textOrToken);
    String checkTapPolicy(String foregroundPackage, String targetResourceId);
    String getConsentState(String consentId);
    String listAuditTail(int maxLines);
    String resolveVaultToken(String token, String purpose);
    String registerSkillPolicy(String skillName, String securityJson);
    String filterFileListJson(String rawResultJson);
    String checkFileSharePolicy(String devicePath, String targetPackage);
    String checkSkillOutputPolicy(String skillName, String rawOutputJson);
    String checkSkillInputPolicy(String skillName, String paramName, String value);
    String checkFileReadPolicy(String devicePath);
    String checkFileWritePolicy(String devicePath);
    void completeConsent(String consentId, boolean approved);
    String sanitizeEventJson(String eventDataJson);
}
