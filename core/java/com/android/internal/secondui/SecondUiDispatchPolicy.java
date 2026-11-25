/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import java.util.Objects;

/**
 * Admission/completion-preserving Main-dispatch ablation.
 *
 * @hide
 */
public final class SecondUiDispatchPolicy implements SecondUiCoordinator.ReadyDispatcher {
    private final SecondUiCoordinator.Dispatcher mOrdinary;
    private final SecondUiCoordinator.ReadyDispatcher mPriority;
    private boolean mPriorityEnabled = true;
    private boolean mFrozen;

    public SecondUiDispatchPolicy(SecondUiCoordinator.Dispatcher ordinary,
            SecondUiCoordinator.ReadyDispatcher priority) {
        mOrdinary = Objects.requireNonNull(ordinary);
        mPriority = Objects.requireNonNull(priority);
    }

    public synchronized void setPriorityEnabled(boolean enabled) {
        if (mFrozen) throw new IllegalStateException("Main dispatch frozen after submission");
        mPriorityEnabled = enabled;
    }

    /** Called at root submission, before asynchronous admission can occur. */
    public synchronized void freeze() { mFrozen = true; }

    @Override public boolean post(Runnable action) { return mOrdinary.post(action); }

    @Override public boolean offer(long id, boolean user, Runnable action) {
        final boolean priority;
        synchronized (this) { mFrozen = true; priority = mPriorityEnabled; }
        return priority ? mPriority.offer(id, user, action) : mOrdinary.post(action);
    }
}
