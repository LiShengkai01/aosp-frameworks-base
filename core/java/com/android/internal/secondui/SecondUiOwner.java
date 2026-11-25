/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import dalvik.system.SecondUiWork;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * UI-root identity and explicitly adapted state deliveries. Bound to the UI thread.
 *
 * @hide
 */
public final class SecondUiOwner {
    private static final ThreadLocal<SecondUiOwner> CURRENT = new ThreadLocal<>();
    private final Thread mThread = Thread.currentThread();
    private final ArrayDeque<Runnable> mDeferred = new ArrayDeque<>();
    public final boolean agent;
    public volatile boolean managedFrames;
    private Runnable mFrameRequest;
    public void setFrameRequest(Runnable request) { checkThread(); mFrameRequest = request; }
    public void requestFrame() { if (mAlive && mFrameRequest != null) mFrameRequest.run(); }
    private boolean mAlive = true;
    private boolean mTraversal;
    private boolean mDraining;
    private boolean mCheckpointSafe;
    public SecondUiOwner(boolean agent) { this.agent = agent; }
    public static SecondUiOwner current() { return CURRENT.get(); }
    public void checkThread() {
        if (Thread.currentThread() != mThread) throw new IllegalStateException("Wrong UI thread");
    }
    public boolean alive() { checkThread(); return mAlive; }
    public void destroy() { checkThread(); mAlive = false; if (!mTraversal) drain(); }
    /** Set only after UI deliveries and foreground frame callback ownership are closed. */
    public void setCheckpointSafe(boolean safe) { checkThread(); mCheckpointSafe = safe; }
    public boolean checkpointSafe() { checkThread(); return mCheckpointSafe; }
    public void run(Runnable body) {
        checkThread();
        if (!mAlive) throw new CancellationException("UI owner destroyed before invocation");
        SecondUiOwner previous = CURRENT.get(); CURRENT.set(this);
        try { body.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public void beginTraversal() {
        checkThread();
        if (mTraversal) throw new IllegalStateException("Reentrant traversal");
        mTraversal = true;
    }
    public void endTraversal() { checkThread(); mTraversal = false; drain(); }
    /**
     * Adapter for an already-routed UI notification, including synchronous observers.
     * Registers before deferral and retains the initiating operation until actual delivery.
     */
    public void deliver(Runnable notification) {
        checkThread(); Objects.requireNonNull(notification);
        if (!mTraversal && !mDraining && mDeferred.isEmpty()) {
            if (mAlive) run(notification);
            return;
        }
        SecondUiWork.Context parent = SecondUiWork.current();
        SecondUiWork.Context child = parent == null ? null : SecondUiCoordinator.registerUiDelivery(parent);
        Runnable delivery = () -> {
            if (child != null) child.onStarted();
            if (!mAlive) {
                // The declared lifecycle policy skips UI delivery. It must not mark an
                // already completed business write as failed and invite duplicate retries.
                if (child != null) child.onExited(null);
                return;
            }
            Throwable failure = null;
            SecondUiWork.Scope scope = null;
            try {
                if (child != null) scope = SecondUiWork.enter(child);
                run(notification);
            } catch (RuntimeException | Error e) { failure = e; throw e; }
            finally {
                try { if (scope != null) scope.close(); }
                finally { if (child != null) child.onExited(failure); }
            }
        };
        if (mTraversal || mDraining || !mDeferred.isEmpty()) mDeferred.addLast(delivery);
        else delivery.run();
    }
    /** Explicit worker-to-UI adapter; ownership is checked on Main, not on the worker. */
    public boolean post(SecondUiCoordinator.Dispatcher main, Runnable notification) {
        Objects.requireNonNull(notification);
        return SecondUiCoordinator.postCompletion(main, () -> deliver(notification));
    }
    private void drain() {
        if (mDraining) return;
        mDraining = true;
        Throwable first = null;
        try {
            while (!mDeferred.isEmpty()) {
                try { mDeferred.removeFirst().run(); }
                catch (RuntimeException | Error e) { if (first == null) first = e; }
            }
        } finally { mDraining = false; }
        if (first instanceof Error) throw (Error) first;
        if (first != null) throw (RuntimeException) first;
    }
}
