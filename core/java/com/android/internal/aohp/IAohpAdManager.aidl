/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpAdManager {
    String registerSlot(String slotJson);
    String requestDecision(String slotId, String requestJson);
    String reportEvent(String slotId, String eventJson);
    boolean unregisterSlot(String slotId);

    String submitOpportunity(String opportunityJson);
    String getHostState(String queryJson);
    String recordHostEvent(String eventJson);
    String runAdapterTest(String testJson);

    String getState(String queryJson);
    String setPolicy(String policyJson);
    void clearEvents();
    String drainEvents(String optionsJson);
    String runSelfTest(String optionsJson);
}
