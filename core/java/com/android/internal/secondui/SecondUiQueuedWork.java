/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import dalvik.system.SecondUiWork;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit adapter for a declared Runnable queue. Register before publication,
 * then call rejectBeforeStart if posting fails or the original queue removes it.
 * Removing an already-running task cannot release its operation. Arbitrary queue
 * APIs, persistent services and detached callbacks are not inferred by this class.
 * @hide
 */
public final class SecondUiQueuedWork implements Runnable {
    private final Runnable mBody;
    private final SecondUiWork.Context mTicket;
    private final AtomicInteger mState = new AtomicInteger(); // pending, running, settled

    private SecondUiQueuedWork(Runnable body, SecondUiWork.Context ticket) {
        mBody = body;
        mTicket = ticket;
    }

    /** Returns null outside a tracked operation, preserving the ordinary queue path. */
    public static SecondUiQueuedWork prepare(Runnable body) {
        Objects.requireNonNull(body);
        SecondUiWork.Context parent = SecondUiWork.current();
        return parent == null ? null : new SecondUiQueuedWork(body, parent.registerChild());
    }

    /** True only when the claim guarantees that this body will never execute. */
    public boolean rejectBeforeStart(Throwable cause) {
        Objects.requireNonNull(cause);
        if (!mState.compareAndSet(0, 2)) return false;
        mTicket.onNeverStarted(cause);
        return true;
    }

    @Override public void run() {
        if (!mState.compareAndSet(0, 1)) return;
        mTicket.onStarted();
        Throwable failure = null;
        SecondUiWork.Scope binding = null;
        try {
            binding = SecondUiWork.enter(mTicket);
            mBody.run();
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try { if (binding != null) binding.close(); }
            finally {
                mState.set(2);
                mTicket.onExited(failure);
            }
        }
    }
}
