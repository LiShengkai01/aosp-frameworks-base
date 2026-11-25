/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import dalvik.system.SecondUiWork;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.RejectedExecutionException;

/**
 * Process-local ordered multi-scope admission and physical work accounting.
 *
 * @hide
 */
public final class SecondUiCoordinator {
    /** Serial asynchronous control dispatch. False means the event cannot run. */
    public interface Dispatcher { boolean post(Runnable runnable); }
    public interface Listener { void onComplete(long requestId, Throwable failure); }
    public interface Trace {
        void event(long sequence, String phase, long requestId, String domain, String operation,
                int outstanding, Throwable failure);
    }
    /** Publishes eligibility; only the Main scheduler may invoke the supplied body. */
    public interface ReadyDispatcher extends Dispatcher {
        boolean offer(long id, boolean user, Runnable body);
    }
    public static final class Contract {
        public final String domain;
        public final String operation;
        public final boolean trackedWork;
        public final Set<SecondUiScope> scopes;
        /** Legacy static-domain descriptor. Not an application-wide fallback. */
        public Contract(String domain, String operation, boolean trackedWork) {
            this(Collections.singleton(SecondUiScope.repository(domain)), operation, trackedWork);
        }
        public Contract(Set<SecondUiScope> scopes, String operation, boolean trackedWork) {
            if (Objects.requireNonNull(scopes).isEmpty()) {
                throw new IllegalArgumentException("Missing scope; use application fallback");
            }
            LinkedHashSet<SecondUiScope> copy = new LinkedHashSet<>();
            for (SecondUiScope scope : scopes) copy.add(Objects.requireNonNull(scope));
            this.scopes = Collections.unmodifiableSet(copy);
            this.domain = copy.toString();
            this.operation = Objects.requireNonNull(operation);
            this.trackedWork = trackedWork;
        }
    }
    // Ticket state is shared with work threads. Admission records are control-thread owned.
    private final Object mLock = new Object();
    private final Object mSubmitLock = new Object();
    private final Dispatcher mControl;
    private final ReadyDispatcher mDispatcher;
    private final Trace mTrace;
    private final List<Request> mUnfinished = new ArrayList<>();
    private long mNextId = 1;
    private long mNextEvent = 1;
    private boolean mCoordinationEnabled = true;
    private boolean mEverSubmitted;

    public SecondUiCoordinator(Dispatcher control, ReadyDispatcher main, Trace trace) {
        mControl = Objects.requireNonNull(control);
        mDispatcher = Objects.requireNonNull(main);
        mTrace = Objects.requireNonNull(trace);
    }
    /** Legacy host-test adapter. Production always supplies a dedicated control dispatcher. */
    public SecondUiCoordinator(Dispatcher main, Trace trace) {
        this(new ImmediateSerialDispatcher(), new ReadyDispatcher() {
            public boolean post(Runnable r) { return main.post(r); }
            public boolean offer(long id, boolean user, Runnable r) { return main.post(r); }
        }, trace);
    }
    private static final class ImmediateSerialDispatcher implements Dispatcher {
        private final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        private boolean draining;
        void enqueue(Runnable runnable) { synchronized (queue) { queue.addLast(runnable); } }
        public boolean post(Runnable runnable) { enqueue(runnable); drain(); return true; }
        void drain() {
            synchronized (queue) {
                if (draining) return;
                draining = true;
            }
            while (true) {
                Runnable next;
                synchronized (queue) {
                    next = queue.pollFirst();
                    if (next == null) { draining = false; return; }
                }
                try { next.run(); }
                catch (RuntimeException | Error e) {
                    synchronized (queue) { draining = false; }
                    throw e;
                }
            }
        }
    }
    public void setCoordinationEnabled(boolean enabled) {
        synchronized (mSubmitLock) {
            if (mEverSubmitted) throw new IllegalStateException("Mode frozen after submission");
            mCoordinationEnabled = enabled;
        }
    }
    public long submit(Contract contract, Runnable action, Listener listener) {
        return submit(contract, true, action, listener);
    }
    public long submit(Contract contract, boolean user, Runnable action, Listener listener) {
        Objects.requireNonNull(contract);
        Objects.requireNonNull(action);
        if (SecondUiWork.current() != null) {
            throw new IllegalStateException("A protected root cannot enqueue another root");
        }
        // Assign and enqueue together: concurrent callers cannot reverse submission order.
        final Request request;
        synchronized (mSubmitLock) {
            if (mNextId == Long.MAX_VALUE) throw new IllegalStateException("Identity exhausted");
            mEverSubmitted = true;
            request = new Request(mNextId++, contract, user, action, listener);
            trace("SUBMITTED", request, 1, null);
            Runnable accept = () -> {
                mUnfinished.add(request);
                trace("ACCEPTED", request, 1, null);
                recompute();
            };
            if (mControl instanceof ImmediateSerialDispatcher) {
                ((ImmediateSerialDispatcher) mControl).enqueue(accept);
            } else if (!mControl.post(accept)) {
                throw new RejectedExecutionException("Control dispatcher stopped");
            }
        }
        if (mControl instanceof ImmediateSerialDispatcher) {
            ((ImmediateSerialDispatcher) mControl).drain();
        }
        return request.id;
    }
    /** Control thread only. Waiting entries contribute just as running entries do. */
    private void recompute() {
        Set<SecondUiScope> earlier = new LinkedHashSet<>();
        List<Request> newlyReady = new ArrayList<>();
        for (Request request : mUnfinished) {
            boolean conflict = false;
            if (mCoordinationEnabled) {
                for (SecondUiScope scope : request.contract.scopes) {
                    for (SecondUiScope prior : earlier) conflict |= scope.overlaps(prior);
                }
            }
            if (!request.granted && (!mCoordinationEnabled || !conflict)) {
                request.granted = true;
                newlyReady.add(request);
            }
            if (mCoordinationEnabled) earlier.addAll(request.contract.scopes);
        }
        // Publish after scanning so a rejecting dispatcher cannot mutate this iteration.
        for (Request request : newlyReady) {
            trace("GRANTED", request, 1, null);
            try {
                if (!mDispatcher.offer(request.id, request.user, () -> runRoot(request))) {
                    request.root.onNeverStarted(new RejectedExecutionException("Main stopped"));
                }
            } catch (RuntimeException failure) {
                request.root.onNeverStarted(failure);
            }
        }
    }
    private void runRoot(Request request) {
        request.root.onStarted();
        trace(request.user ? "USER_HANDLER_START" : "SLACK_HANDLER_START", request, 1, null);
        Throwable failure = null;
        SecondUiWork.Scope scope = null;
        try {
            scope = SecondUiWork.enter(request.root);
            request.action.run();
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            try { if (scope != null) scope.close(); }
            finally {
                request.action = null;
                trace("HANDLER_RETURNED", request, -1, failure);
                request.root.onExited(failure);
            }
        }
        if (failure instanceof Error) throw (Error) failure;
    }
    private Event eventLocked(String phase, Request request, int count, Throwable failure) {
        return new Event(mNextEvent++, phase, request, count, failure);
    }
    private void trace(String phase, Request request, int count, Throwable failure) {
        Event event;
        synchronized (mLock) { event = eventLocked(phase, request, count, failure); }
        trace(event);
    }
    private void trace(Event event) {
        try {
            mTrace.event(event.sequence, event.phase, event.id, event.domain,
                    event.operation, event.count, event.failure);
        } catch (RuntimeException ignored) { }
    }
    private void settled(Ticket ticket, Throwable failure, boolean neverStarted) {
        Request request = ticket.request;
        Event exit;
        boolean drained;
        synchronized (mLock) {
            int expected = neverStarted ? Ticket.RESERVED : Ticket.RUNNING;
            if (ticket.state != expected || request.closed) {
                throw new IllegalStateException("Duplicate or invalid ticket settlement");
            }
            ticket.state = neverStarted ? Ticket.NEVER_STARTED : Ticket.EXITED;
            if (failure != null && request.failure == null) request.failure = failure;
            drained = --request.outstanding == 0;
            if (drained) request.closed = true;
            exit = eventLocked(neverStarted ? "NEVER_STARTED" : "EXITED", request,
                    request.outstanding, failure);
        }
        trace(exit);
        if (drained && !mControl.post(() -> completed(request))) {
            // Never release on the wrong thread. A dead controller fails closed.
            trace("CONTROL_STOPPED", request, 0, request.failure);
        }
    }
    private void completed(Request request) {
        if (!mUnfinished.remove(request)) throw new IllegalStateException("Unknown completion");
        trace("DRAINED", request, 0, request.failure);
        Listener observer = request.listener;
        request.listener = null;
        request.action = null;
        recompute();
        if (observer != null) {
            try {
                if (!mDispatcher.post(() -> {
                    try { observer.onComplete(request.id, request.failure); }
                    catch (RuntimeException e) { trace("OBSERVER_FAILED", request, 0, e); }
                })) trace("OBSERVER_REJECTED", request, 0, request.failure);
            } catch (RuntimeException e) { trace("OBSERVER_REJECTED", request, 0, e); }
        }
    }
    private static final class Event {
        final long sequence, id;
        final String phase, domain, operation;
        final int count;
        final Throwable failure;
        Event(long sequence, String phase, Request request, int count, Throwable failure) {
            this.sequence = sequence; this.phase = phase; this.id = request.id;
            this.domain = request.contract.domain; this.operation = request.contract.operation;
            this.count = count; this.failure = failure;
        }
    }
    private final class Request {
        final long id;
        final Contract contract;
        final boolean user;
        final Ticket root;
        Runnable action;
        Listener listener;
        int outstanding = 1;
        boolean closed, granted;
        Throwable failure;
        Request(long id, Contract contract, boolean user, Runnable action, Listener listener) {
            this.id = id; this.contract = contract; this.user = user;
            this.action = action; this.listener = listener; root = new Ticket(this);
        }
    }
    /**
     * Explicit completion adapter. Unlike owner delivery, required shared-state finalization
     * still runs if the originating page has gone away. The callback retains original context.
     */
    public static boolean postCompletion(Dispatcher main, Runnable notification) {
        Objects.requireNonNull(main); Objects.requireNonNull(notification);
        SecondUiWork.Context parent = SecondUiWork.current();
        SecondUiWork.Context child = parent == null ? null
                : SecondUiCoordinator.registerUiDelivery(parent);
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable action = () -> {
            if (!claimed.compareAndSet(false, true)) return;
            if (child != null) child.onStarted();
            Throwable failure = null;
            SecondUiWork.Scope scope = null;
            try {
                if (child != null) scope = SecondUiWork.enter(child);
                notification.run();
            } catch (RuntimeException | Error e) { failure = e; throw e; }
            finally {
                try { if (scope != null) scope.close(); }
                finally { if (child != null) child.onExited(failure); }
            }
        };
        boolean posted = false;
        try { posted = main.post(action); return posted; }
        finally {
            if (!posted && claimed.compareAndSet(false, true) && child != null) {
                child.onNeverStarted(new java.util.concurrent.RejectedExecutionException("UI dispatcher stopped"));
            }
        }
    }

    /** Framework deferral may extend a synchronous root with a known UI delivery. */
    public static SecondUiWork.Context registerUiDelivery(SecondUiWork.Context parent) {
        if (parent instanceof SecondUiCoordinator.Ticket) {
            return ((SecondUiCoordinator.Ticket) parent).registerChild(true);
        }
        return parent.registerChild();
    }

    private final class Ticket implements SecondUiWork.Context {
        static final int RESERVED = 0;
        static final int RUNNING = 1;
        static final int EXITED = 2;
        static final int NEVER_STARTED = 3;
        final Request request;
        int state = RESERVED;

        Ticket(Request request) { this.request = request; }

        @Override
        public SecondUiWork.Context registerChild() { return registerChild(false); }

        private SecondUiWork.Context registerChild(boolean frameworkDelivery) {
            Ticket child;
            int count;
            Event registered;
            synchronized (mLock) {
                if (state != RUNNING || request.closed) {
                    throw new IllegalStateException("Child registration requires a live parent");
                }
                if (!request.contract.trackedWork && !frameworkDelivery) {
                    throw new IllegalStateException("SYNC_BODY contract cannot publish async work");
                }
                if (request.outstanding == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Work identity capacity exhausted");
                }
                child = new Ticket(request);
                count = ++request.outstanding;
                registered = eventLocked("REGISTERED", request, count, null);
            }
            trace(registered);
            return child;
        }

        @Override
        public void onStarted() {
            int count;
            Event started;
            synchronized (mLock) {
                if (state != RESERVED || request.closed) {
                    throw new IllegalStateException("Ticket has already started or settled");
                }
                state = RUNNING;
                count = request.outstanding;
                started = eventLocked("STARTED", request, count, null);
            }
            trace(started);
        }

        @Override
        public void onExited(Throwable failure) { settled(this, failure, false); }

        @Override
        public void onNeverStarted(Throwable failure) {
            settled(this, Objects.requireNonNull(failure), true);
        }
    }
}
