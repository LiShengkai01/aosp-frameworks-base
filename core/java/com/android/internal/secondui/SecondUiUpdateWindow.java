/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

/**
 * Main-thread action/observation protocol; business completion does not close this window.
 *
 * @hide
 */
public final class SecondUiUpdateWindow {
    private long mRevision;
    private volatile boolean mOpen;
    public long open() {
        if (mRevision == Long.MAX_VALUE) throw new IllegalStateException("Update identity exhausted");
        mOpen = true;
        return ++mRevision;
    }
    public boolean isOpen() { return mOpen; }
    public void observed(long revision) {
        if (revision == mRevision) mOpen = false;
    }
}
