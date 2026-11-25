/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package android.app;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.MainThread;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.Trace;
import android.view.View;
import com.android.internal.secondui.SecondUiMainScheduler;
import com.android.internal.secondui.SecondUiDispatchPolicy;
import com.android.internal.secondui.SecondUiOwner;
import android.os.Looper;
import android.util.Log;

import com.android.internal.secondui.SecondUiContract;
import com.android.internal.secondui.SecondUiCoordinator;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Executes explicitly described operations in a process-local shared domain.
 *
 * <p>Adapters must preserve acceptance checks, capture stable values before submission,
 * and cover all relevant asynchronous work. This API checks compatibility with supported
 * execution semantics; it does not prove application assertions or discover operations.
 * Domains are shared by all UI instances in this process and do not include UI identity.
 */
@FlaggedApi(Flags.FLAG_SECOND_UI_OPERATION_CONTROL)
public final class SecondUiBridge {
    private static final String TAG = "SecondUiOperation";
    private static final Map<String, SecondUiContract> CONTRACTS = new HashMap<>();

    private SecondUiBridge() {}

    private static final class Runtime {
        static final Handler MAIN = new Handler(Looper.getMainLooper());
        static final HandlerThread CONTROL_THREAD = startControlThread();
        static final Handler CONTROL = new Handler(CONTROL_THREAD.getLooper());
        static final boolean VERBOSE_LOG = SystemProperties.getBoolean(
                "debug.second_ui.operation_log", true);
        static final SecondUiDispatchPolicy DISPATCH = new SecondUiDispatchPolicy(
                MAIN::post, SecondUiMainScheduler.get());
        static final SecondUiCoordinator COORDINATOR = new SecondUiCoordinator(action -> CONTROL.post(() -> {
                    Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#control");
                    try { action.run(); } finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
                }),
                DISPATCH,
                (sequence, phase, id, domain, operation, outstanding, failure) -> {
                    if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUiEvent " + sequence
                                + " " + phase + " " + id + " " + outstanding);
                        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                    }
                    if (!VERBOSE_LOG && failure == null) return;
                    String message = "event=" + sequence + " phase=" + phase
                            + " request=" + id + " domain=" + domain
                            + " operation=" + operation + " outstanding=" + outstanding;
                    if (failure == null) Log.i(TAG, message);
                    else Log.e(TAG, message, failure);
                });
    }

    private static HandlerThread startControlThread() {
        HandlerThread thread = new HandlerThread("SecondUiControl");
        thread.start();
        return thread;
    }

    /**
     * Platform adapter for an accepted COMPLETE handler, not a repository-call fragment.
     * Caller preserves its local pending guard through the completion protocol, snapshots
     * stable inputs and supplies all scopes. Unknown dependencies require application scope.
     * The explicitly supported lifecycle policy cancels a body whose owner dies before start;
     * already started work retains its scope until drain. Required result handling belongs
     * inside tracked work; listener is only an after-drain observer.
     * @hide
     */
    public static long submitHandler(View ownerView, SecondUiCoordinator.Contract contract,
            Runnable body, CompletionListener listener) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "SecondUi#submitHandler");
        try {
            requireEnabled();
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Handler admission requires Main");
            }
            if (ownerView.getViewRootImpl() == null) {
                throw new IllegalStateException("Handler must have an attached UI owner");
            }
            SecondUiOwner owner = ownerView.getViewRootImpl().getSecondUiOwner();
            Objects.requireNonNull(body);
            Runtime.DISPATCH.freeze();
            return Runtime.COORDINATOR.submit(contract, !owner.agent, () -> owner.run(body),
                    listener == null ? null : listener::onComplete);
        } finally { Trace.traceEnd(Trace.TRACE_TAG_VIEW); }
    }

    /**
     * Adapter for result handlers that are part of an operation's completion boundary.
     * Capture the owner on Main before starting work; call this from a live tracked task.
     * Unlike CompletionListener, this callback retains the operation's scope until delivery.
     * @hide
     */
    public static boolean postResult(SecondUiOwner owner, Runnable resultHandler) {
        requireEnabled();
        return Objects.requireNonNull(owner).post(
                new Handler(Looper.getMainLooper())::post, resultHandler);
    }

    /**
     * Required Main-thread completion work independent of an originating page's lifetime.
     *
     * @hide
     */
    public static boolean postCompletion(Runnable completion) {
        requireEnabled();
        return SecondUiCoordinator.postCompletion(
                new Handler(Looper.getMainLooper())::post, completion);
    }

    /** Returns this experimental operation ABI version. */
    public static int getAbiVersion() { return 1; }

    /** Returns the execution capabilities implemented by this runtime. */
    public static @NonNull String[] getCapabilities() {
        return Flags.secondUiOperationControl() ? SecondUiContract.capabilities() : new String[0];
    }

    /**
     * Registers a build-validated full second-ui.operation/0.1 JSON contract.
     *
     * @return SHA-256 of the supplied UTF-8 descriptor, used for subsequent submissions
     * @throws IllegalArgumentException if execution semantics or capabilities are unsupported
     * @throws UnsupportedOperationException if the platform feature is disabled
     */
    public static @NonNull String registerContract(@NonNull String descriptorJson) {
        requireEnabled();
        SecondUiContract contract = SecondUiContract.parse(Objects.requireNonNull(descriptorJson));
        synchronized (CONTRACTS) {
            if (!CONTRACTS.containsKey(contract.id) && CONTRACTS.size() >= 1024) {
                throw new IllegalStateException("Too many registered operation contracts");
            }
            CONTRACTS.put(contract.id, contract);
        }
        return contract.id;
    }

    /**
     * Enqueues an already accepted request, starting its body asynchronously on the main thread.
     * The caller must capture stable request values and must not retain an owner UI in the body.
     * Runtime body failures are delivered after all tracked work exits. Fatal root Errors are
     * also rethrown on the main thread. Original CompletableFuture result timing is unchanged.
     *
     * @return process-local request sequence assigned at acceptance by the common coordinator
     * @throws IllegalArgumentException if the contract was not registered
     * @throws IllegalStateException if called off the main thread or from another protected root
     */
    // This constrained ABI deliberately delivers its observation on the same main
    // dispatcher as roots. An arbitrary Executor would change the stated callback
    // contract; MainThread is not recognized by the generic callback-overload lint.
    @SuppressLint("ExecutorRegistration")
    @MainThread
    public static long submitMain(@NonNull String contractId, @NonNull Runnable body,
            @Nullable CompletionListener listener) {
        requireEnabled();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("submitMain requires the main thread");
        }
        SecondUiContract contract;
        synchronized (CONTRACTS) { contract = CONTRACTS.get(Objects.requireNonNull(contractId)); }
        if (contract == null) throw new IllegalArgumentException("Unknown operation contract");
        Runtime.DISPATCH.freeze();
        return Runtime.COORDINATOR.submit(contract.execution, false, Objects.requireNonNull(body),
                listener == null ? null : listener::onComplete);
    }

    /**
     * Configures the experimental control comparison before the first request is submitted.
     * Disabled coordination retains request identity, work tracking and completion reporting,
     * while allowing overlapping roots. It must not be used to claim exclusive execution.
     *
     * @throws IllegalStateException if any request has already been submitted
     */
    public static void setCoordinationEnabled(boolean enabled) {
        requireEnabled();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Configure coordination on Main");
        }
        Runtime.COORDINATOR.setCoordinationEnabled(enabled);
    }

    /**
     * Experimental M0/M1 comparison. Both modes retain identical repository admission,
     * work lifetime, UI ownership and completion delivery. Only eligible root dispatch
     * changes: ordinary Handler or the priority/slack scheduler. Configure on Main before
     * the first submission. Traversal/window controls are independent of this switch.
     * @hide
     */
    public static void setMainSchedulingPriority(boolean enabled) {
        requireEnabled();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Configure Main dispatch on Main");
        }
        Runtime.DISPATCH.setPriorityEnabled(enabled);
    }

    /** Receives an observation after root and tracked work have physically exited. */
    @FlaggedApi(Flags.FLAG_SECOND_UI_OPERATION_CONTROL)
    public interface CompletionListener {
        /**
         * Called on the main thread outside the protected operation, after its domain is released.
         * The listener must not perform required business finalization or retain a departed UI.
         *
         * @param requestId the process-local request sequence
         * @param failure first observed work failure, or null if all tracked work succeeded
         */
        @MainThread
        void onComplete(long requestId, @Nullable Throwable failure);
    }

    private static void requireEnabled() {
        if (!Flags.secondUiOperationControl()) {
            throw new UnsupportedOperationException("Second UI operation control is disabled");
        }
    }
}
