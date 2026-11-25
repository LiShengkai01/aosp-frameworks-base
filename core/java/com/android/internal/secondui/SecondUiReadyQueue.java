/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Cross-thread eligibility publication; one Main consumer. No execution under this lock.
 *
 * @hide
 */
public final class SecondUiReadyQueue {
    private final ArrayDeque<Runnable> mUser = new ArrayDeque<>();
    private final ArrayDeque<Runnable> mAgent = new ArrayDeque<>();
    private final LinkedHashMap<Object, Runnable> mTraversals = new LinkedHashMap<>();
    public synchronized void offer(boolean user, Runnable action) {
        (user ? mUser : mAgent).addLast(Objects.requireNonNull(action));
    }
    public synchronized boolean publish(boolean user, Runnable action,
            java.util.function.BooleanSupplier wake) {
        ArrayDeque<Runnable> queue = user ? mUser : mAgent;
        queue.addLast(Objects.requireNonNull(action));
        boolean posted = false;
        try { posted = wake.getAsBoolean(); return posted; }
        finally { if (!posted) queue.removeLastOccurrence(action); }
    }
    public synchronized List<Runnable> takeInputUsers(int limit) {
        ArrayList<Runnable> batch = new ArrayList<>();
        while (batch.size() < limit && !mUser.isEmpty()) batch.add(mUser.removeFirst());
        return batch;
    }
    public synchronized void traversal(Object owner, Runnable action) {
        mTraversals.put(Objects.requireNonNull(owner), Objects.requireNonNull(action));
    }
    public synchronized void removeTraversal(Object owner) { mTraversals.remove(owner); }
    public synchronized Runnable takeSlack() {
        if (!mUser.isEmpty()) return mUser.removeFirst();
        if (!mAgent.isEmpty()) return mAgent.removeFirst();
        if (mTraversals.isEmpty()) return null;
        Object first = mTraversals.keySet().iterator().next();
        return mTraversals.remove(first);
    }
    public synchronized boolean hasWork() {
        return !mUser.isEmpty() || !mAgent.isEmpty() || !mTraversals.isEmpty();
    }
}
