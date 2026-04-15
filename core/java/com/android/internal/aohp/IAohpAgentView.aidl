/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpAgentView {
    /**
     * Full-display screenshot. Returns JPEG or PNG bytes.
     * quality &gt; 0: JPEG at that quality (1-100); quality &lt;= 0: PNG.
     */
    byte[] captureDisplay(int displayId, int quality);

    /**
     * Region screenshot: full display capture cropped to the given rect.
     */
    byte[] captureRegion(int displayId, int left, int top, int right, int bottom, int quality);
}
