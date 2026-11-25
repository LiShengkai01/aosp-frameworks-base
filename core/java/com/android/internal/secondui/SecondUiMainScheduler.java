/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.Trace;
import android.view.Choreographer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One scheduler per UI Looper for admitted operations and deferred traversals.
 *
 * @hide
 */
public final class SecondUiMainScheduler implements SecondUiCoordinator.ReadyDispatcher {
    private static final ThreadLocal<SecondUiMainScheduler> LOCAL = new ThreadLocal<>();
    private final Handler mHandler;
    private final Handler mWakeHandler;
    private final Choreographer mChoreographer;
    private final SecondUiReadyQueue mReady = new SecondUiReadyQueue();
    private boolean mIdleArmed;
    private final AtomicBoolean mWakeQueued = new AtomicBoolean();
    private final Runnable mWake = () -> {
        mWakeQueued.set(false);
        onOpportunity();
    };
    private final MessageQueue.IdleHandler mIdle = this::onIdle;
    private boolean onIdle() {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#idle");
        try {
        mIdleArmed = false;
        // MessageQueue has dispatched due messages. Also check a received VSYNC and deadline.
        if (!mChoreographer.isForegroundFrameDueForSecondUi()) {
            Runnable action = mReady.takeSlack();
            try { if (action != null) action.run(); }
            finally { wakeIfPending(); }
        } else if (mReady.hasWork()) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#deferForeground");
            // An overdue frame owns Main. Retry after it gets an event-loop opportunity.
            scheduleWake(1);
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        return false;
        } finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }
    private SecondUiMainScheduler() {
        mHandler = new Handler(Looper.myLooper());
        // A pending traversal's barrier must not turn an otherwise unused interval
        // into a whole-frame wait. Only this wake bypasses it; result delivery keeps
        // ordinary Handler semantics. The opportunity checks deliverable messages
        // and foreground readiness before running one admitted operation.
        mWakeHandler = Handler.createAsync(Looper.myLooper());
        mChoreographer = Choreographer.getInstance();
    }
    public static SecondUiMainScheduler get() {
        if (Looper.myLooper() == null) throw new IllegalStateException("UI Looper required");
        SecondUiMainScheduler scheduler = LOCAL.get();
        if (scheduler == null) { scheduler = new SecondUiMainScheduler(); LOCAL.set(scheduler); }
        return scheduler;
    }
    private void checkThread() {
        if (Looper.myLooper() != mHandler.getLooper()) throw new IllegalStateException("Wrong UI Looper");
    }
    @Override public boolean post(Runnable action) { return mHandler.post(action); }
    @Override public boolean offer(long id, boolean user, Runnable action) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, user ? "SecondUi#publishUser" : "SecondUi#publishAgent");
        try { return mReady.publish(user, action, () -> scheduleWake(0)); }
        finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }
    private void onOpportunity() {
        checkThread();
        MessageQueue queue = mHandler.getLooper().getQueue();
        if (queue.hasDueMessageForSecondUi()) {
            armIdle();
            // The last wake can precede a later ordinary message. That message
            // may install a barrier; recheck after yielding to the event loop.
            if (mReady.hasWork()) scheduleWake(1);
            return;
        }
        if (mIdleArmed) {
            queue.removeIdleHandler(mIdle);
            mIdleArmed = false;
        }
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#opportunity");
        try { onIdle(); }
        finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }
    private void armIdle() {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#armIdle");
        try {
        checkThread();
        if (mReady.hasWork() && !mIdleArmed) {
            mIdleArmed = true;
            mHandler.getLooper().getQueue().addIdleHandler(mIdle);
        }
        } finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }
    private boolean scheduleWake(long delay) {
        if (!mWakeQueued.compareAndSet(false, true)) return true;
        if (mWakeHandler.postDelayed(mWake, delay)) return true;
        mWakeQueued.set(false);
        return false;
    }
    private void wakeIfPending() { if (mReady.hasWork()) scheduleWake(0); }
    public void traversal(Object owner, Runnable action) {
        checkThread(); mReady.traversal(owner, action); scheduleWake(0);
    }
    public void removeTraversal(Object owner) { checkThread(); mReady.removeTraversal(owner); }
    public static void onInputPhaseEnd() {
        SecondUiMainScheduler scheduler = LOCAL.get();
        if (scheduler == null) return;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#inputUsers");
        try {
        // A bounded snapshot; arrivals during this batch stay queued for another opportunity.
        List<Runnable> batch = scheduler.mReady.takeInputUsers(16);
        for (Runnable action : batch) action.run();
        scheduler.wakeIfPending();
        } finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }
}
