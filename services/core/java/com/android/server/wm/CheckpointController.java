/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.server.wm;

import android.app.ActivityManager;
import android.app.servertransaction.ActivityLifecycleItem;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** In-memory checkpoint protocol inside ATMS. No service, file IO or model calls. */
final class CheckpointController {
    static final int PROTOCOL_VERSION = 3;
    private static final int MAX_OPERATIONS = 4096;

    private static final class Snapshot {
        final java.util.ArrayList<android.content.ComponentName> components = new java.util.ArrayList<>();
        final java.util.ArrayList<Bundle> states = new java.util.ArrayList<>();
        // Keep task/alias routing separate from Activity.getIntent() runtime state.
        final java.util.ArrayList<android.content.Intent> launchIntents = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> launchModes = new java.util.ArrayList<>();
        final java.util.ArrayList<Long> versionCodes = new java.util.ArrayList<>();
        final java.util.ArrayList<android.content.Intent> intents = new java.util.ArrayList<>();
    }

    private static int serializedSize(Snapshot checkpoint) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeTypedList(checkpoint.components, 0);
            parcel.writeTypedList(checkpoint.states, 0);
            parcel.writeTypedList(checkpoint.intents, 0);
            parcel.writeTypedList(checkpoint.launchIntents, 0);
            parcel.writeList(checkpoint.launchModes);
            parcel.writeList(checkpoint.versionCodes);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    // Copy without unpacking application Parcelable extras in system_server.
    static Intent copyIntent(Intent intent) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Intent.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private final ActivityTaskManagerService service;
    private Session session;
    // Attached tasks remain owned by WM; quarantine must not retain removed tasks.
    private final java.util.Set<Task> quarantined =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Task, Boolean>());

    CheckpointController(ActivityTaskManagerService service) { this.service = service; }

    private static String id() { return UUID.randomUUID().toString(); }
    private static long now() { return SystemClock.elapsedRealtimeNanos(); }
    private static final class Entry {
        ActivityRecord record;
        WindowProcessController process;
        long generation;
        long payloadVersion;
        int pid;
        int tokenIdentity;
        String nonce = id();
        boolean fresh;
        boolean deferred;
        boolean replied;
        long clientInstance;
        Bundle state;
        Intent intent;
        String component = "";
        boolean stateNull = true;
        long sampleStartNs, sampleEndNs, lifecycleCompletedNs;
        Entry(ActivityRecord record) {
            this.record = record;
            if (record != null) {
                process = record.app;
                tokenIdentity = System.identityHashCode(record);
                generation = record.mCheckpointGeneration;
                payloadVersion = record.getCheckpointPayloadVersion();
                pid = process != null ? process.getPid() : 0;
            }
        }
    }
    private static final class Op {
        final String id, kind, args;
        final long started = now();
        long completed;
        String status, error;
        Snapshot snapshot;
        final ArrayList<Entry> entries = new ArrayList<>();
        long bytes = -1;
        Runnable timeout;
        boolean drainKnown;
        java.util.concurrent.CountDownLatch completion = new java.util.concurrent.CountDownLatch(1);
        Op(String id, String kind, String args, String status) {
            this.id = id; this.kind = kind; this.args = args; this.status = status;
        }
        boolean pending() { return "COLLECTING".equals(status) || "DISPATCHED".equals(status); }
    }
    private static final class Published {
        final String checkpointId = id();
        final String step, manifest;
        final Op candidate;
        Published(String step, String manifest, Op candidate) {
            this.step = step; this.manifest = manifest; this.candidate = candidate;
        }
    }
    private static final class Session {
        final String token = id();
        final String run, packageName;
        final Task task;
        final int owner;
        IBinder ownerToken;
        IBinder.DeathRecipient ownerDeath;
        final ArrayMap<String, Op> ops = new ArrayMap<>();
        final ArrayMap<String, Published> byStep = new ArrayMap<>();
        final ArrayMap<String, Published> byId = new ArrayMap<>();
        final int maxCount;
        final long maxBytes;
        long publishedBytes;
        Session(String run, String packageName, Task task, int owner, int maxCount, long maxBytes) {
            this.run = run; this.packageName = packageName; this.task = task; this.owner = owner;
            this.maxCount = maxCount; this.maxBytes = maxBytes;
        }
    }

    static void enforceCaller() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
            throw new SecurityException("Checkpoint requires the controlled shell or system");
        }
    }
    private static ArrayList<ActivityRecord> records(Task task) {
        final ArrayList<ActivityRecord> out = new ArrayList<>();
        task.forAllActivities((Consumer<ActivityRecord>) r -> { if (!r.finishing) out.add(r); }, false);
        return out;
    }
    private void supported(Session s) {
        if (!s.task.isAttached() || s.task.getDisplayId() != 0) fail("SESSION_INVALID");
        // A live session can enter split-screen/PiP after open; keep the same boundary.
        if (s.task.inMultiWindowMode()) fail("UNSUPPORTED_TOPOLOGY");
        final DisplayContent display = service.mRootWindowContainer.getDisplayContent(0);
        final Task focused = display != null ? display.getTopRootTask() : null;
        if (focused == null || focused.getTopMostTask() != s.task) fail("WRONG_FOREGROUND_TASK");
        final ArrayList<ActivityRecord> acts = records(s.task);
        if (acts.isEmpty() || acts.size() > 32) fail("UNSUPPORTED_TOPOLOGY");
        for (ActivityRecord r : acts) {
            if (!s.packageName.equals(r.packageName) || r.isEmbedded()) {
                fail("UNSUPPORTED_TOPOLOGY");
            }
            if ((r.results != null && !r.results.isEmpty())
                    || (r.newIntents != null && !r.newIntents.isEmpty())) {
                fail("PENDING_LIFECYCLE_WORK");
            }
        }
    }
    private static void fail(String message) { throw new IllegalStateException(message); }
    private Session require(Bundle q, int caller) {
        if (session == null || !session.token.equals(q.getString("session"))
                || session.owner != caller) fail("SESSION_INVALID");
        return session;
    }
    private static String text(Bundle q, String name) {
        final String value = q.getString(name);
        if (value == null || value.isEmpty() || value.length() > 256) fail("INVALID_" + name);
        return value;
    }
    private static JSONObject json(Object... args) {
        final JSONObject object = new JSONObject();
        try {
            for (int i = 0; i < args.length; i += 2) object.put((String) args[i], args[i + 1]);
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
        return object;
    }
    private static JSONObject publication(Published p) {
        return json("status", "PUBLISHED", "checkpoint_id", p.checkpointId, "step_key", p.step,
                "candidate_id", p.candidate.id, "manifest_hash", p.manifest,
                "serialized_bytes", p.candidate.bytes);
    }
    private static JSONObject status(Op op) {
        final JSONArray entries = new JSONArray();
        for (Entry e : op.entries) {
            entries.put(json("component", e.record != null ? e.record.mActivityComponent.flattenToString() : e.component,
                    "fresh", e.fresh, "deferred", e.deferred, "replied", e.replied,
                    "state_null", "CAPTURE".equals(op.kind) && e.replied && e.stateNull,
                    "client_instance_id", e.clientInstance,
                    "token_identity", e.tokenIdentity, "generation", e.generation, "pid", e.pid,
                    "sample_start_ns", e.sampleStartNs,
                    "sample_end_ns", e.sampleEndNs,
                    "lifecycle_completed_ns", e.lifecycleCompletedNs));
        }
        return json("op_id", op.id, "kind", op.kind, "status", op.status,
                "capture_id", "CAPTURE".equals(op.kind) ? op.id : "",
                "candidate_id", "CAPTURE".equals(op.kind) ? op.id : "",
                "error", op.error == null ? "" : op.error,
                "started_ns", op.started, "completed_ns", op.completed,
                "serialized_bytes", op.bytes, "participants", entries, "drain_known", op.drainKnown);
    }

    // CLOCK_BOOTTIME inside system_server: never includes host/ADB or app_process startup.
    // The end excludes serializing this timing envelope and Binder reply transmission.
    String command(Bundle q, int caller, long entryNs) {
        final long lockAcquiredNs = now();
        return envelope(commandResult(q, caller), entryNs, lockAcquiredNs);
    }

    private String envelope(JSONObject result, long entryNs, long lockAcquiredNs) {
        final long completedNs = now();
        try {
            result.put("protocol_version", PROTOCOL_VERSION);
            result.put("system_dispatch_timing", json("clock", "CLOCK_BOOTTIME",
                    "entry_ns", entryNs, "lock_acquired_ns", lockAcquiredNs,
                    "completed_ns", completedNs));
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
        return result.toString();
    }

    /** One bounded wait on the existing Binder API; never hold the ATMS lock while waiting. */
    String awaitCompletion(Bundle q, int caller, long entryNs) {
        final Session owner;
        final Op op;
        final java.util.concurrent.CountDownLatch signal;
        final long acquired;
        final long waitMs = q.getLong("wait_ms", 12000);
        synchronized (service.mGlobalLock) {
            acquired = now();
            try {
                if (q.getInt("protocol_version", -1) != PROTOCOL_VERSION) fail("UNSUPPORTED_PROTOCOL_VERSION");
                if (waitMs < 1 || waitMs > 15000) fail("INVALID_WAIT_MS");
                owner = require(q, caller);
                op = requireOp(owner, text(q, "op_id"));
                signal = op.completion;
            } catch (RuntimeException error) {
                return envelope(json("status", "ERROR", "error", error.getMessage()), entryNs, acquired);
            }
        }
        final long waitStarted = now();
        boolean interrupted = false;
        try {
            if (signal != null) signal.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interrupted = true;
        }
        synchronized (service.mGlobalLock) {
            final JSONObject result;
            if (session != owner || owner.ops.get(op.id) != op) {
                result = json("status", "ERROR", "error", "SESSION_INVALID");
            } else if (interrupted || op.pending()) {
                // A wait deadline does not imply cancellation or a known terminal effect.
                result = json("status", "ERROR", "error", interrupted
                        ? "AWAIT_INTERRUPTED_COMPLETION_UNKNOWN" : "AWAIT_TIMEOUT_COMPLETION_UNKNOWN");
            } else {
                result = status(op);
            }
            try {
                result.put("system_wait_timing", json("clock", "CLOCK_BOOTTIME",
                        "started_ns", waitStarted, "completed_ns", now()));
            } catch (JSONException error) { throw new IllegalArgumentException(error); }
            return envelope(result, entryNs, acquired);
        }
    }

    private JSONObject commandResult(Bundle q, int caller) {
        try {
            if (q.getInt("protocol_version", -1) != PROTOCOL_VERSION) fail("UNSUPPORTED_PROTOCOL_VERSION");
            final String command = text(q, "command");
            if ("open".equals(command)) {
                if (q.getBinder("owner_token") == null) fail("SESSION_OWNER_REQUIRED");
                final String run = text(q, "run_id");
                final String pkg = text(q, "package");
                if (session != null) {
                    if (session.owner == caller && session.run.equals(run) && session.packageName.equals(pkg)) {
                        if (session.ownerToken != q.getBinder("owner_token")) fail("SESSION_OWNER_CONFLICT");
                        return sessionInfo(session);
                    }
                    fail("BUSY");
                }
                final DisplayContent dc = service.mRootWindowContainer.getDisplayContent(0);
                final Task root = dc != null ? dc.getTopRootTask() : null;
                final Task task = root != null ? root.getTopMostTask() : null;
                if (task == null || task.mUserId != 0 || task.inMultiWindowMode()) fail("UNSUPPORTED_TOPOLOGY");
                quarantined.removeIf(t -> !t.isAttached());
                if (quarantined.contains(task)) fail("DRAIN_UNKNOWN_RESET_TASK_REQUIRED");
                if (q.containsKey("task_id") && q.getInt("task_id") != task.mTaskId) fail("WRONG_TASK");
                final Session next = new Session(run, pkg, task, caller,
                        Math.max(1, Math.min(512, q.getInt("max_checkpoints", 128))),
                        Math.max(1024, Math.min(256L << 20, q.getLong("max_bytes", 64L << 20))));
                supported(next);
                next.ownerToken = q.getBinder("owner_token");
                next.ownerDeath = () -> service.mH.post(() -> {
                    synchronized (service.mGlobalLock) {
                        if (session == next) {
                            closeSession(next, true);
                            Slog.i("Checkpoint", "Released session after controller process death");
                        }
                    }
                });
                try { next.ownerToken.linkToDeath(next.ownerDeath, 0); }
                catch (RemoteException error) { fail("SESSION_OWNER_DEAD"); }

                session = next;
                return sessionInfo(next);
            }
            final Session s = require(q, caller);
            if ("info".equals(command)) return sessionInfo(s);
            if ("get".equals(command)) return status(requireOp(s, text(q, "op_id")));
            if ("published".equals(command)) {
                final Published p = s.byStep.get(text(q, "step_key"));
                return p == null ? json("status", "NOT_PUBLISHED") : publication(p);
            }
            if ("capture".equals(command)) return capture(s, q);
            if ("restore".equals(command)) return restore(s, q);
            if ("publish".equals(command)) return publish(s, q);
            if ("discard".equals(command)) {
                final Op op = requireOp(s, text(q, "candidate_id"));
                for (Published p : s.byStep.values()) if (p.candidate == op) fail("ALREADY_PUBLISHED");
                if (!"CAPTURE".equals(op.kind) || (!"STAGED".equals(op.status)
                        && !"DISCARDED".equals(op.status))) fail("CANDIDATE_NOT_STAGED");
                finish(op, "DISCARDED", null); op.snapshot = null; op.entries.clear();
                return status(op);
            }
            if ("cancel".equals(command)) {
                final Op op = requireOp(s, text(q, "op_id"));
                if (op.pending()) {
                    quarantined.add(s.task);
                    finish(op, "CANCELLED", "DRAIN_UNKNOWN_RESET_TASK_REQUIRED");
                }
                return status(op);
            }
            if ("close".equals(command)) return closeSession(s, false);
            fail("UNKNOWN_COMMAND");
            return json("status", "ERROR", "error", "UNKNOWN_COMMAND");
        } catch (RuntimeException error) {
            return json("status", "ERROR", "error", error.getMessage());
        }
    }

    /** Runs under the ATMS lock for explicit close and controller Binder death. */
    private JSONObject closeSession(Session s, boolean ownerDied) {
        boolean pending = false;
        for (Op op : s.ops.values()) if (op.pending()) {
            pending = true; finish(op, "CANCELLED", "SESSION_CLOSED");
        }
        if (pending && !quarantined.contains(s.task)) quarantined.add(s.task);
        final boolean drainKnown = !pending && !quarantined.contains(s.task);
        for (ActivityRecord r : records(s.task)) r.clearCheckpoints();
        if (!ownerDied) {
            s.ownerToken.unlinkToDeath(s.ownerDeath, 0);
        }
        s.ops.clear(); s.byStep.clear(); s.byId.clear(); s.publishedBytes = 0;
        session = null;
        return json("status", "CLOSED", "drain_known", drainKnown);
    }

    private JSONObject sessionInfo(Session s) {
        final JSONArray bindings = new JSONArray();
        for (ActivityRecord r : records(s.task)) {
            bindings.put(json("token_identity", System.identityHashCode(r),
                    "generation", r.mCheckpointGeneration, "pid", r.app != null ? r.app.getPid() : 0,
                    "component", r.mActivityComponent.flattenToString()));
        }
        return json("status", "OPEN", "session", s.token, "run_id", s.run,
                "protocol_version", PROTOCOL_VERSION, "owner_lifetime", "BINDER",
                "task_id", s.task.mTaskId, "task_generation", System.identityHashCode(s.task),
                "display_id", 0, "checkpoints", s.byStep.size(), "serialized_bytes", s.publishedBytes,
                "max_checkpoints", s.maxCount, "max_bytes", s.maxBytes,
                "operations", s.ops.size(), "max_operations", MAX_OPERATIONS, "bindings", bindings);
    }
    private static Op requireOp(Session s, String id) {
        final Op op = s.ops.get(id);
        if (op == null) fail("UNKNOWN_OPERATION");
        return op;
    }
    private void idle(Session s) {
        for (Op op : s.ops.values()) if (op.pending()) fail("BUSY");
        if (quarantined.contains(s.task)) fail("DRAIN_UNKNOWN_RESET_TASK_REQUIRED");
    }
    private Op existing(Session s, String id, String kind, String args) {
        final Op op = s.ops.get(id);
        if (op != null && (!op.kind.equals(kind) || !op.args.equals(args))) fail("REQUEST_CONFLICT");
        return op;
    }
    private void timer(Session s, Op op, long ms) {
        op.timeout = () -> {
            synchronized (service.mGlobalLock) {
                if (session == s && op.pending()) {
                    quarantined.add(s.task);
                    finish(op, "FAILED", "TIMEOUT_DRAIN_UNKNOWN");
                }
            }
        };
        service.mH.postDelayed(op.timeout, ms);
    }
    private static boolean allReplied(Op op) {
        for (Entry e : op.entries) if (!e.replied) return false;
        return true;
    }
    private void finish(Op op, String result, String error) {
        finish(op, result, error, !op.pending() || "STAGED".equals(result)
                || "LIFECYCLE_COMPLETE".equals(result));
    }
    private void finish(Op op, String result, String error, boolean drainKnown) {
        if (op.pending() && !drainKnown && ("FAILED".equals(result) || "CANCELLED".equals(result))
                && session != null && !quarantined.contains(session.task)) quarantined.add(session.task);
        op.status = result; op.error = error; op.completed = now(); op.drainKnown = drainKnown;
        if (op.timeout != null) {
            service.mH.removeCallbacks(op.timeout);
            op.timeout = null;
        }
        if (!"STAGED".equals(result)) {
            releaseParticipants(op);
            op.snapshot = null;
        }
        if (op.completion != null) {
            op.completion.countDown();
            op.completion = null;
        }
    }
    // Only scalar receipts survive terminal operations. A staged capture needs its
    // live participants until publication validates them and installs the baseline.
    private static void releaseParticipants(Op op) {
        for (Entry e : op.entries) {
            if (e.record != null) e.component = e.record.mActivityComponent.flattenToString();
            e.record = null;
            e.process = null;
            e.state = null;
            e.intent = null;
            e.nonce = null;
        }
    }
    private boolean sameParticipants(Session s, Op op) {
        final ArrayList<ActivityRecord> current = records(s.task);
        if (current.size() != op.entries.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            final Entry e = op.entries.get(i);
            final ActivityRecord r = current.get(i);
            if (r != e.record || r.mCheckpointGeneration != e.generation
                    || r.getCheckpointPayloadVersion() != e.payloadVersion || r.app != e.process
                    || (r.app != null ? r.app.getPid() : 0) != e.pid) return false;
        }
        return true;
    }
    private JSONObject capture(Session s, Bundle q) {
        final String id = text(q, "op_id");
        final String scope = text(q, "scope");
        if (!"TOP_FRESH".equals(scope) && !"STACK_FRESH".equals(scope)) fail("INVALID_SCOPE");
        final Op prior = existing(s, id, "CAPTURE", scope);
        if (prior != null) return status(prior);
        supported(s);
        idle(s);
        if (s.ops.size() >= MAX_OPERATIONS) fail("OPERATION_QUOTA");
        long staged = s.ops.values().stream().filter(o -> "STAGED".equals(o.status)).count();
        if (staged >= 4 || s.byStep.size() >= s.maxCount) fail("CHECKPOINT_QUOTA");
        final ArrayList<ActivityRecord> acts = records(s.task);
        if (!acts.get(acts.size() - 1).attachedToProcess()) fail("NO_CONNECTED_TOP");
        if ("STACK_FRESH".equals(scope)) {
            for (ActivityRecord r : acts) if (!r.attachedToProcess()) fail("MISSING_STACK_PARTICIPANT");
        }
        final Op op = new Op(id, "CAPTURE", scope, "COLLECTING");
        for (ActivityRecord r : acts) {
            final Entry e = new Entry(r); e.fresh = r.attachedToProcess();
            if (!e.fresh) {
                // A TOP_FRESH restore may leave an unstarted lower record with
                // a selected historical launch payload. Inherit that state/Intent
                // pair, not the previously published baseline. Publication is the
                // completion point; an interruption before it is retryable.
                final ActivityRecord.CheckpointPayload payload = r.getCheckpointLaunchPayload();
                final Bundle saved = payload != null ? payload.state : r.getSavedState();
                e.state = saved != null ? new Bundle(saved) : null;
                e.stateNull = e.state == null;
                e.intent = copyIntent(payload != null ? payload.intent : r.intent);
                e.replied = true;
            }
            op.entries.add(e);
        }
        s.ops.put(id, op); // Register the complete expected set before dispatch.
        timer(s, op, 2000);
        for (Entry e : op.entries) if (e.fresh) {
            final Bundle context = new Bundle();
            context.putString("op_id", id); context.putString("nonce", e.nonce);
            try {
                e.process.getThread().scheduleCheckpointCapture(e.record.token, context);
            } catch (Exception error) {
                finish(op, "FAILED", "DISPATCH_FAILED"); break;
            }
        }
        return status(op);
    }

    void onResult(IBinder token, Bundle result, Bundle state, Intent intent, int uid, int pid) {
        final Session s = session;
        if (s == null || result == null) return;
        final Op op = s.ops.get(result.getString("op_id"));
        if (op == null || !op.pending()) return;
        final ActivityRecord r = ActivityRecord.isInAnyTask(token);
        if (r == null || r.getTask() != s.task || uid != r.getUid()
                || r.app == null || pid != r.app.getPid()) return;
        Entry found = null;
        for (Entry e : op.entries) {
            if (e.record == r && e.nonce.equals(result.getString("nonce"))) { found = e; break; }
        }
        if (found == null || found.replied || !op.kind.equals(result.getString("kind"))) return;
        if ("CAPTURE".equals(op.kind)) {
            // This validated callback has returned. A stale capture is drained only
            // if every expected participant has replied; otherwise quarantine stays.
            found.replied = true;
            if (!sameParticipants(s, op) || found.process != r.app || found.pid != pid) {
                finish(op, "FAILED", "STALE_INSTANCE", allReplied(op)); return;
            }
            if (!"FRESH".equals(result.getString("status")) || intent == null) {
                finish(op, "FAILED", "CLIENT_CAPTURE_FAILED"); return;
            }
            found.state = state != null ? new Bundle(state) : null;
            found.stateNull = found.state == null;
            found.intent = CheckpointController.copyIntent(intent);
        } else {
            final Bundle context = r.mCheckpointRestoreContext;
            if (context == null || !found.nonce.equals(context.getString("nonce"))
                    || result.getInt("actual_state", -1) != context.getInt("expected_state", -2)
                    || !"LIFECYCLE_COMPLETE".equals(result.getString("status"))) {
                finish(op, "FAILED", "RESTORE_LIFECYCLE_MISMATCH"); return;
            }
            // Retain only the acknowledged live binding in the scalar receipt.
            // A later process death before the first public observation must
            // not turn an unfinished recovery into an ordinary mismatch.
            found.tokenIdentity = System.identityHashCode(r);
            found.generation = r.mCheckpointGeneration;
            found.pid = pid;
            r.mCheckpointRestoreContext = null;
            r.clearCheckpointLaunchIntent();
        }
        found.replied = true; found.clientInstance = result.getLong("client_instance_id");
        found.sampleStartNs = result.getLong("sample_start_ns");
        found.sampleEndNs = result.getLong("sample_end_ns");
        found.lifecycleCompletedNs = result.getLong("completed_ns");
        if ("RESTORE".equals(op.kind)) startUnattachedRestoreParticipants(s, op);
        for (Entry e : op.entries) if (!e.replied) return;
        if ("CAPTURE".equals(op.kind)) {
            final Snapshot cp = new Snapshot();
            for (Entry e : op.entries) {
                cp.components.add(e.record.mActivityComponent);
                cp.launchIntents.add(CheckpointController.copyIntent(e.record.intent));
                cp.launchModes.add(e.record.info.launchMode);
                cp.versionCodes.add(e.record.info.applicationInfo.longVersionCode);
                // Entries already own these copies; publication drops the transient owners.
                cp.states.add(e.state);
                cp.intents.add(e.intent);
            }
            op.snapshot = cp;
            finish(op, "STAGED", null); // Never writes ActivityRecord baseline.
        } else {
            final ArrayList<ActivityRecord> current = records(s.task);
            if (current.size() != op.entries.size()) { finish(op, "FAILED", "RESTORE_STACK_MISMATCH", true); return; }
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i) != op.entries.get(i).record) {
                    finish(op, "FAILED", "RESTORE_ORDER_MISMATCH", true); return;
                }
            }
            // Detached lower records have no live client to acknowledge this
            // operation. Keep the selected payload for launch/capture, but never
            // retain a nonce belonging to an already terminal operation.
            for (Entry e : op.entries) if (e.deferred) {
                final Bundle context = e.record.mCheckpointRestoreContext;
                if (context != null && op.id.equals(context.getString("op_id"))
                        && e.nonce.equals(context.getString("nonce"))) {
                    e.record.mCheckpointRestoreContext = null;
                }
            }
            finish(op, "LIFECYCLE_COMPLETE", null);
        }
    }

    /** Explicit size inspection outside capture/restore and outside the ATMS lock. */
    String inspect(Bundle q, int caller) {
        final Session s;
        final Op op;
        final Snapshot snapshot;
        synchronized (service.mGlobalLock) {
            try {
                s = require(q, caller); op = requireOp(s, text(q, "candidate_id"));
                if (op.snapshot == null) fail("NO_CANDIDATE");
                snapshot = op.snapshot;
            } catch (RuntimeException error) {
                return json("status", "ERROR", "error", error.getMessage()).toString();
            }
        }
        final long started = now();
        final int bytes;
        try { bytes = serializedSize(snapshot); }
        catch (RuntimeException error) { return json("status", "ERROR", "error", "PAYLOAD_REJECTED").toString(); }
        synchronized (service.mGlobalLock) {
            if (session != s || op.snapshot != snapshot) return json("status", "ERROR", "error", "SESSION_INVALID").toString();
            op.bytes = bytes;
            return json("status", "INSPECTED", "candidate_id", op.id, "serialized_bytes", bytes,
                    "inspect_started_ns", started, "inspect_completed_ns", now()).toString();
        }
    }
    private JSONObject publish(Session s, Bundle q) {
        final String step = text(q, "step_key");
        final String candidateId = text(q, "candidate_id");
        final String manifest = text(q, "manifest_hash");
        if (!step.startsWith(s.run + ":") || !manifest.matches("[0-9a-f]{64}")) fail("INVALID_PUBLICATION");
        final Published prior = s.byStep.get(step);
        if (prior != null) {
            if (!prior.candidate.id.equals(candidateId) || !prior.manifest.equals(manifest)) fail("REQUEST_CONFLICT");
            return publication(prior);
        }
        supported(s);
        idle(s);
        final Op op = requireOp(s, candidateId);
        if (!"STAGED".equals(op.status) || op.snapshot == null) fail("CANDIDATE_NOT_STAGED");
        if (!sameParticipants(s, op)) fail("STALE_INSTANCE");
        if (op.bytes < 0) fail("PAYLOAD_NOT_INSPECTED");
        if (s.byStep.size() >= s.maxCount || s.publishedBytes + op.bytes > s.maxBytes) fail("CHECKPOINT_QUOTA");
        final Published published = new Published(step, manifest, op);
        for (Entry e : op.entries) {
            e.record.setCheckpointBaseline(e.state, e.intent);
        }
        s.byStep.put(step, published); s.byId.put(published.checkpointId, published);
        s.publishedBytes += op.bytes;
        op.status = "PUBLISHED";
        releaseParticipants(op);
        return publication(published);
    }

    /** Use the existing detached-launch path, not a second activation service. */
    private boolean canDeferHiddenRestore(ActivityRecord r, ActivityRecord top) {
        // Same-client ordering lets the final top lifecycle acknowledgement also
        // fence the queued destruction of old hidden instances.
        return r != top && r.attachedToProcess() && top.attachedToProcess()
                && r.app == top.app && r.isState(ActivityRecord.State.STOPPED)
                && top.isState(ActivityRecord.State.RESUMED)
                && !r.isRelaunching() && !top.isRelaunching()
                && !r.isVisibleRequested() && !r.isVisible() && !r.nowVisible
                && !r.isClientVisible() && !r.inTransition() && !top.inTransition()
                && !r.getTask().inTransition()
                && !service.mBackNavigationController
                        .isMonitoringFinishTransition();
    }

    private JSONObject restore(Session s, Bundle q) {
        final String id = text(q, "op_id");
        final String checkpointId = text(q, "checkpoint_id");
        final Op prior = existing(s, id, "RESTORE", checkpointId);
        if (prior != null) return status(prior);
        supported(s);
        idle(s);
        final Published p = s.byId.get(checkpointId);
        if (p == null) fail("UNKNOWN_CHECKPOINT");
        final Snapshot cp = p.candidate.snapshot;
        final ArrayList<ActivityRecord> original = records(s.task);
        final long installedVersion = original.get(original.size() - 1).info.applicationInfo.longVersionCode;
        for (Long version : cp.versionCodes) if (version != installedVersion) fail("APP_VERSION_CHANGED");
        // Preflight route restrictions before removing any record.
        for (int i = 0; i < cp.components.size(); i++) {
            if (i < original.size() && cp.components.get(i).equals(original.get(i).mActivityComponent)) continue;
            final int mode = cp.launchModes.get(i);
            if (mode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
                    || mode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                if (i != 0 || s.task.getBaseIntent() == null || !Objects.equals(
                        s.task.getBaseIntent().getComponent(), cp.launchIntents.get(i).getComponent())) {
                    fail("UNSUPPORTED_TASK_ROUTE");
                }
            }
        }
        if (s.ops.size() >= MAX_OPERATIONS) fail("OPERATION_QUOTA");
        final Op op = new Op(id, "RESTORE", checkpointId, "DISPATCHED");
        op.snapshot = cp;
        for (int i = 0; i < cp.components.size(); i++) op.entries.add(new Entry(null));
        s.ops.put(id, op); timer(s, op, 10000);
        int match = 0;
        while (match < Math.min(original.size(), cp.components.size())
                && cp.components.get(match).equals(original.get(match).mActivityComponent)) match++;
        for (int i = original.size() - 1; i >= match; i--) {
            original.get(i).finishIfPossible("checkpoint_v2_suffix", false);
        }
        // Only an unchanged route with a live top can fence deferred destruction.
        // Missing records, cross-process, visible and transitional layers remain eager.
        final ActivityRecord originalTop = original.get(original.size() - 1);
        final boolean deferHidden = "TOP_FRESH".equals(p.candidate.args)
                && match == original.size() && match == cp.components.size();
        boolean destroyedHidden = false;
        for (int i = 0; i < cp.components.size(); i++) {
            final Entry e = op.entries.get(i);
            final Bundle context = new Bundle();
            context.putString("op_id", id); context.putString("nonce", e.nonce);
            context.putInt("index", i);
            context.putInt("expected_state", i == cp.components.size() - 1
                    ? ActivityLifecycleItem.ON_RESUME : ActivityLifecycleItem.ON_STOP);
            if (i < match) {
                final ActivityRecord r = original.get(i);
                e.record = r;
                r.setCheckpointRestoreBinding(cp.states.get(i), cp.intents.get(i), context);
                if (r.attachedToProcess() && deferHidden
                        && canDeferHiddenRestore(r, originalTop)) {
                    // Keep the record and selected state/Intent. Normal Back will
                    // cold-launch it; TOP_FRESH does not claim it was reconstructed.
                    if (!r.destroyImmediatelyForCheckpoint("checkpoint_deferred_restore")) {
                        finish(op, "FAILED", "DEFERRED_DESTROY_DISPATCH_FAILED"); break;
                    }
                    if (r.finishing || r.getTask() != s.task || r.attachedToProcess()
                            || !r.isState(ActivityRecord.State.DESTROYED)) {
                        finish(op, "FAILED", "DEFERRED_DESTROY_NOT_ESTABLISHED"); break;
                    }
                    e.replied = true; e.deferred = true; destroyedHidden = true;
                } else if (r.attachedToProcess()) {
                    if (r == originalTop && destroyedHidden) {
                        if (!service.getLifecycleManager()
                                .dispatchPendingTransactionWithResult(r.app.getThread())) {
                            finish(op, "FAILED", "DEFERRED_DESTROY_DISPATCH_FAILED"); break;
                        }
                    }
                    if (!r.relaunchCheckpointActivityLocked()) {
                        finish(op, "FAILED", "RELAUNCH_DISPATCH_FAILED"); break;
                    }
                } else if (i == cp.components.size() - 1 || "STACK_FRESH".equals(p.candidate.args)) {
                    service.mTaskSupervisor.startSpecificActivity(r, i == cp.components.size() - 1, true);
                } else {
                    // TOP_FRESH promises the top endpoint; detached lower layers are explicit.
                    e.replied = true; e.deferred = true;
                }
            } else {
                final int result = service.getActivityStartController().obtainStarter(
                        CheckpointController.copyIntent(cp.launchIntents.get(i)), "checkpoint_v2_rebuild")
                        .setInTask(s.task).setCheckpointSavedState(cp.states.get(i))
                        .setCheckpointSavedIntent(cp.intents.get(i))
                        .setCheckpointRestoreContext(context).execute();
                if (result != ActivityManager.START_SUCCESS) {
                    finish(op, "FAILED", "REBUILD_START_" + result); break;
                }
            }
        }
        startUnattachedRestoreParticipants(s, op);
        return status(op);
    }

    private void startUnattachedRestoreParticipants(Session s, Op op) {
        if (!op.pending() || !service.mRootWindowContainer.allPausedActivitiesComplete()) return;
        // ActivityStarter can build several records while a previous Activity is pausing.
        // Normal resume then launches only the top; hidden suffix records remain INITIALIZING.
        // Launch those participants explicitly after pauses complete. A real launch attaches
        // the record before returning, so later callbacks cannot dispatch it a second time.
        // The top follows the normal resume path and its callback also retries this pass.
        for (int i = 0; i < op.entries.size() - 1; i++) {
            final Entry e = op.entries.get(i);
            final ActivityRecord r = e.record;
            if (e.replied || r == null || r.finishing || r.getTask() != s.task
                    || r.attachedToProcess()) continue;
            final Bundle context = r.mCheckpointRestoreContext;
            if (context == null || !op.id.equals(context.getString("op_id"))
                    || !e.nonce.equals(context.getString("nonce"))) continue;
            service.mTaskSupervisor.startSpecificActivity(r, false /* andResume */, true);
        }
    }

    void onRestoreRecordCreated(ActivityRecord r, Bundle context) {
        if (session == null || context == null) return;
        final Op op = session.ops.get(context.getString("op_id"));
        final int index = context.getInt("index", -1);
        if (op == null || !op.pending() || index < 0 || index >= op.entries.size()) return;
        final Entry e = op.entries.get(index);
        if (!e.nonce.equals(context.getString("nonce"))) return;
        e.record = r;
    }
}
