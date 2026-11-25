/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.android.internal.secondui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;

/**
 * Runtime compatibility checks, not a proof of supplied application assertions.
 *
 * @hide
 */
public final class SecondUiContract {
    private static final int MAX_JSON_LENGTH = 131072;
    private static final String[] CAPABILITIES = {
        "domain.appRuntime.exclusive.v1", "admission.preserve.v1", "admission.fifo.v1",
        "invocation.snapshotValues.v1", "invocation.immutableRequest.v1", "invocation.main.v1",
        "result.void.v1", "completion.syncBody.v1", "completion.trackedWork.v1",
        "work.protocol.v1", "work.cf.runAsync.defaultExecutor.v1"
    };
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(CAPABILITIES));
    private static final Set<String> OBLIGATIONS = new HashSet<>(Arrays.asList(
            "entry_coverage", "acceptance_preserved", "delay_safe", "snapshot_complete",
            "execution_preserved", "completion_closed", "lifecycle_defined", "bypasses_audited"));
    public final String id;
    public final SecondUiCoordinator.Contract execution;

    private SecondUiContract(String id, SecondUiCoordinator.Contract execution) {
        this.id = id;
        this.execution = execution;
    }

    public static String[] capabilities() { return CAPABILITIES.clone(); }

    /**
     * Checks the full S1 schema, evidence-reference consistency and supported execution semantics.
     * Files, APK identity and source assertions are verified by the build/install tooling;
     * this process-local check rejects incompatible execution semantics and capabilities.
     */
    public static SecondUiContract parse(String json) {
        if (json == null || json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Missing or oversized Second UI contract");
        }
        try {
            JSONObject c = SecondUiJson.parse(json);
            SecondUiSchema.validate(c);
            validateReferences(c);
            equal(c, "schema_version", "second-ui.operation/0.1");
            String operation = identifier(c.getString("operation_id"));
            equal(c, "policy", "fifo_wait");
            JSONObject domain = c.getJSONObject("domain");
            equal(domain, "kind", "app_runtime");
            equal(domain, "mode", "exclusive");
            setEquals(strings(domain.getJSONArray("key_components")),
                    "process_epoch", "scope_id");
            String scope = identifier(domain.getString("scope_id"));
            JSONObject invocation = c.getJSONObject("invocation");
            bool(invocation, "delay_allowed", true);
            bool(invocation, "immediate_result_required", false);
            bool(invocation, "synchronous_exception_dependency", false);
            equal(invocation, "root_context", "original_main");
            equal(invocation, "return_kind", "void");
            oneOf(invocation.getString("receiver_lifetime"), "independent_request", "process_service");
            String inputs = invocation.getString("input_mode");
            oneOf(inputs, "snapshot_values", "immutable_request", "no_inputs");
            JSONArray captures = invocation.getJSONArray("captured_fields");
            require(!inputs.equals("snapshot_values") || captures.length() != 0,
                    "Value snapshot has no fields");
            require(!inputs.equals("no_inputs") || captures.length() == 0,
                    "no_inputs has captured fields");
            equal(invocation.getJSONObject("adapter"), "status", "implemented");
            equal(c.getJSONObject("entry"), "position", "after_acceptance_before_business");
            equal(c.getJSONObject("admission"), "preservation", "original");
            JSONObject completion = c.getJSONObject("completion");
            String model = completion.getString("model");
            oneOf(model, "sync_body", "tracked_work");
            bool(completion, "unmodeled_async", false);
            bool(completion, "result_drain_separate", true);
            equal(completion, "release_rule", "root_and_all_work_terminated");
            require(completion.has("terminal") && completion.isNull("terminal"),
                    "Terminal protocols are not implemented");
            Set<String> models = strings(completion.getJSONArray("library_models"));
            for (String library : models) {
                oneOf(library, "cf.runAsync.defaultExecutor.v1", "room.syncDao.v1");
            }
            boolean tracked = model.equals("tracked_work");
            if (tracked) {
                require(models.contains("cf.runAsync.defaultExecutor.v1"), "Missing CF model");
                JSONObject work = completion.getJSONObject("work_protocol");
                bool(work, "register_before_publish", true);
                bool(work, "settle_exactly_once", true);
                equal(work, "ownership", "registration_context");
                equal(work, "cancel_running", "wait_for_exit");
                equal(work, "close_registration", "atomic");
            } else {
                require(completion.has("work_protocol") && completion.isNull("work_protocol"),
                        "SYNC_BODY cannot declare work protocol");
                require(!models.contains("cf.runAsync.defaultExecutor.v1"),
                        "SYNC_BODY cannot declare asynchronous CF model");
            }
            JSONObject finalization = c.getJSONObject("finalization");
            bool(finalization, "included_in_completion", true);
            require(finalization.getJSONArray("steps").length() == 0,
                    "Separate finalization adapters are not implemented");
            JSONObject lifecycle = c.getJSONObject("lifecycle");
            equal(lifecycle, "accepted_business", "continue");
            equal(lifecycle, "owner_delivery", "none");
            equal(lifecycle, "owner_gone", "not_applicable");
            require(c.getJSONObject("coverage").getJSONArray("unresolved_calls").length() == 0,
                    "Unresolved calls prevent completion closure");
            JSONArray extensions = c.getJSONArray("extensions");
            Set<String> extensionNames = new HashSet<>();
            for (int i = 0; i < extensions.length(); i++) {
                JSONObject extension = extensions.getJSONObject(i);
                bool(extension, "required", false);
                require(extensionNames.add(extension.getString("name")), "Duplicate extension name");
            }
            JSONObject obligations = c.getJSONObject("obligations");
            Set<String> names = new HashSet<>();
            for (Iterator<String> it = obligations.keys(); it.hasNext();) names.add(it.next());
            require(names.equals(OBLIGATIONS), "Missing or unknown contract obligations");
            for (String name : names) equal(obligations.getJSONObject(name), "status", "satisfied");
            Set<String> required = strings(c.getJSONArray("required_capabilities"));
            require(SUPPORTED.containsAll(required), "Unsupported required capability");
            Set<String> derived = new HashSet<>(Arrays.asList(
                    "domain.appRuntime.exclusive.v1", "admission.preserve.v1", "admission.fifo.v1",
                    "invocation.main.v1", "result.void.v1",
                    tracked ? "completion.trackedWork.v1" : "completion.syncBody.v1"));
            if (inputs.equals("snapshot_values")) derived.add("invocation.snapshotValues.v1");
            if (inputs.equals("immutable_request")) derived.add("invocation.immutableRequest.v1");
            if (tracked) {
                derived.add("work.protocol.v1");
                derived.add("work.cf.runAsync.defaultExecutor.v1");
            }
            require(required.containsAll(derived), "Required capability declaration is incomplete");
            return new SecondUiContract(digest(json),
                    new SecondUiCoordinator.Contract(scope, operation, tracked));
        } catch (JSONException failure) {
            throw new IllegalArgumentException("Malformed Second UI contract", failure);
        }
    }

    // These checks establish descriptor consistency, not the truth of source assertions.
    // Build-host paths are never opened by the Android process.
    private static void validateReferences(JSONObject contract) throws JSONException {
        Map<String, JSONObject> evidence = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();
        JSONArray files = contract.getJSONObject("target").getJSONArray("files");
        for (int i = 0; i < files.length(); i++) bindFile(bindings, files.getJSONObject(i));
        JSONArray records = contract.getJSONArray("evidence");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            require(evidence.put(record.getString("id"), record) == null, "Duplicate evidence ID");
            bindFile(bindings, record.getJSONObject("file"));
        }
        Set<String> references = strings(contract.getJSONObject("admission").getJSONArray("evidence"));
        references.add(contract.getJSONObject("entry").getString("evidence_id"));
        Set<String> origins = new HashSet<>();
        JSONObject obligations = contract.getJSONObject("obligations");
        for (Iterator<String> names = obligations.keys(); names.hasNext();) {
            JSONObject obligation = obligations.getJSONObject(names.next());
            String basis = obligation.getString("basis");
            origins.add(origin(basis));
            for (String reference : strings(obligation.getJSONArray("evidence"))) {
                references.add(reference);
                JSONObject record = evidence.get(reference);
                require(record != null, "Missing evidence reference: " + reference);
                String kind = record.getString("kind");
                boolean matches = basis.equals("manual_review")
                        ? kind.equals("source_review") || kind.equals("fixture_definition")
                        : basis.equals(kind);
                require(matches, "Evidence does not match obligation basis");
            }
        }
        for (String reference : references) {
            JSONObject record = evidence.get(reference);
            require(record != null, "Missing evidence reference: " + reference);
            origins.add(origin(record.getString("kind")));
        }
        String declared = contract.getJSONObject("provenance").getString("origin");
        require(declared.equals("mixed") || origins.equals(new HashSet<>(Arrays.asList(declared))),
                "Evidence provenance mismatch");
    }

    private static String origin(String basisOrKind) {
        switch (basisOrKind) {
            case "manual_review":
            case "source_review":
            case "fixture_definition": return "manual";
            case "application_contract": return "application";
            case "analysis_rule": return "analysis";
            default: throw new IllegalArgumentException("Unknown evidence origin");
        }
    }

    private static void bindFile(Map<String, String> bindings, JSONObject file) throws JSONException {
        String path = file.getString("path");
        require(path.startsWith("/"), "Evidence file path must be absolute");
        String hash = file.getString("sha256");
        require(hash.matches("[0-9a-f]{64}"), "Evidence file hash must be 64 lowercase hex digits");
        String previous = bindings.put(path, hash);
        require(previous == null || previous.equals(hash), "Conflicting evidence file hash");
    }

    private static Set<String> strings(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            require(value instanceof String && result.add((String) value),
                    "Expected unique string entries");
        }
        return result;
    }

    private static void equal(JSONObject object, String key, String value) throws JSONException {
        require(value.equals(object.get(key)), "Unsupported " + key);
    }

    private static void bool(JSONObject object, String key, boolean value) throws JSONException {
        require(Boolean.valueOf(value).equals(object.get(key)), "Unsupported " + key);
    }

    private static void oneOf(String value, String... choices) {
        require(Arrays.asList(choices).contains(value), "Unsupported value: " + value);
    }

    private static void setEquals(Set<String> actual, String... expected) {
        require(actual.equals(new HashSet<>(Arrays.asList(expected))), "Invalid domain key");
    }

    private static String identifier(String value) {
        require(!value.isEmpty() && value.length() <= 256 && value.matches("[A-Za-z0-9_.:-]+"),
                "Invalid operation/domain identifier");
        return value;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
