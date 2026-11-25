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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generated from the frozen S1 operation schema by generate_schema_validator.py.
 * Supports exactly the schema vocabulary checked by that generator, not arbitrary JSON Schema.
 * Input schema SHA-256: 438686d99201810aa463ce6c98d13e58df7cb2edef152801e0df5335eeacc33c
 * @hide
 */
public final class SecondUiSchema {
    private static final String SOURCE_SCHEMA = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"urn:second-ui:operation:0.1\",\"title\":\"Second UI operation contract 0.1\",\"type\":\"object\",\"properties\":{\"schema_version\":{\"const\":\"second-ui.operation/0.1\"},\"operation_id\":{\"type\":\"string\",\"minLength\":1},\"target\":{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"source_snapshot\",\"apk\"]},\"application_id\":{\"type\":\"string\",\"minLength\":1},\"version\":{\"type\":\"string\",\"minLength\":1},\"files\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/definitions/file\"},\"minItems\":1,\"uniqueItems\":true}},\"required\":[\"kind\",\"application_id\",\"version\",\"files\"],\"additionalProperties\":false},\"entry\":{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"accepted_callsite\",\"accepted_method\",\"accepted_region\"]},\"evidence_id\":{\"type\":\"string\",\"minLength\":1},\"position\":{\"enum\":[\"after_acceptance_before_business\"]}},\"required\":[\"kind\",\"evidence_id\",\"position\"],\"additionalProperties\":false},\"admission\":{\"type\":\"object\",\"properties\":{\"preservation\":{\"enum\":[\"original\"]},\"scope\":{\"enum\":[\"original_owner\",\"shared_object\",\"none\"]},\"checks\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"evidence\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":1,\"uniqueItems\":true}},\"required\":[\"preservation\",\"scope\",\"checks\",\"evidence\"],\"additionalProperties\":false},\"invocation\":{\"type\":\"object\",\"properties\":{\"delay_allowed\":{\"type\":\"boolean\"},\"input_mode\":{\"enum\":[\"snapshot_values\",\"immutable_request\",\"no_inputs\",\"mutable_reference\"]},\"captured_fields\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"adapter\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"minLength\":1},\"status\":{\"enum\":[\"planned\",\"implemented\"]}},\"required\":[\"id\",\"status\"],\"additionalProperties\":false},\"root_context\":{\"enum\":[\"original_main\",\"original_executor\"]},\"return_kind\":{\"enum\":[\"void\",\"completion_stage\",\"synchronous_value\"]},\"immediate_result_required\":{\"type\":\"boolean\"},\"synchronous_exception_dependency\":{\"type\":\"boolean\"},\"receiver_lifetime\":{\"enum\":[\"process_service\",\"independent_request\",\"owner_bound\",\"unknown\"]}},\"required\":[\"delay_allowed\",\"input_mode\",\"captured_fields\",\"adapter\",\"root_context\",\"return_kind\",\"immediate_result_required\",\"synchronous_exception_dependency\",\"receiver_lifetime\"],\"additionalProperties\":false},\"domain\":{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"app_runtime\"]},\"scope_id\":{\"type\":\"string\",\"minLength\":1},\"mode\":{\"enum\":[\"exclusive\"]},\"key_components\":{\"type\":\"array\",\"items\":{\"enum\":[\"process_epoch\",\"scope_id\",\"ui_origin\"]},\"minItems\":1,\"uniqueItems\":true}},\"required\":[\"kind\",\"scope_id\",\"mode\",\"key_components\"],\"additionalProperties\":false},\"policy\":{\"enum\":[\"fifo_wait\"]},\"completion\":{\"type\":\"object\",\"properties\":{\"model\":{\"enum\":[\"sync_body\",\"tracked_work\",\"terminal_contract\"]},\"library_models\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"unmodeled_async\":{\"type\":\"boolean\"},\"release_rule\":{\"enum\":[\"root_and_all_work_terminated\",\"root_return\",\"result_terminal\",\"timeout\"]},\"result_drain_separate\":{\"type\":\"boolean\"},\"work_protocol\":{\"anyOf\":[{\"type\":\"null\"},{\"$ref\":\"#/definitions/work_protocol\"}]},\"terminal\":{\"anyOf\":[{\"type\":\"null\"},{\"$ref\":\"#/definitions/terminal\"}]}},\"required\":[\"model\",\"library_models\",\"unmodeled_async\",\"release_rule\",\"result_drain_separate\",\"work_protocol\",\"terminal\"],\"additionalProperties\":false},\"finalization\":{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"included_in_completion\":{\"type\":\"boolean\"}},\"required\":[\"steps\",\"included_in_completion\"],\"additionalProperties\":false},\"lifecycle\":{\"type\":\"object\",\"properties\":{\"accepted_business\":{\"enum\":[\"continue\"]},\"owner_delivery\":{\"enum\":[\"none\",\"original_owner_generation\"]},\"owner_gone\":{\"enum\":[\"not_applicable\",\"skip_ui_delivery\",\"needs_contract\"]}},\"required\":[\"accepted_business\",\"owner_delivery\",\"owner_gone\"],\"additionalProperties\":false},\"coverage\":{\"type\":\"object\",\"properties\":{\"level\":{\"enum\":[\"selected_operations\"]},\"family_id\":{\"type\":\"string\",\"minLength\":1},\"selected_paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":1,\"uniqueItems\":true},\"input_preconditions\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"bypasses\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"unresolved_calls\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true},\"limitations\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":0,\"uniqueItems\":true}},\"required\":[\"level\",\"family_id\",\"selected_paths\",\"input_preconditions\",\"bypasses\",\"unresolved_calls\",\"limitations\"],\"additionalProperties\":false},\"required_capabilities\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":1,\"uniqueItems\":true},\"extensions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1},\"required\":{\"type\":\"boolean\"},\"value\":{}},\"required\":[\"name\",\"required\",\"value\"],\"additionalProperties\":false},\"minItems\":0,\"uniqueItems\":true},\"obligations\":{\"type\":\"object\",\"properties\":{\"entry_coverage\":{\"$ref\":\"#/definitions/obligation\"},\"acceptance_preserved\":{\"$ref\":\"#/definitions/obligation\"},\"delay_safe\":{\"$ref\":\"#/definitions/obligation\"},\"snapshot_complete\":{\"$ref\":\"#/definitions/obligation\"},\"execution_preserved\":{\"$ref\":\"#/definitions/obligation\"},\"completion_closed\":{\"$ref\":\"#/definitions/obligation\"},\"lifecycle_defined\":{\"$ref\":\"#/definitions/obligation\"},\"bypasses_audited\":{\"$ref\":\"#/definitions/obligation\"}},\"required\":[\"entry_coverage\",\"acceptance_preserved\",\"delay_safe\",\"snapshot_complete\",\"execution_preserved\",\"completion_closed\",\"lifecycle_defined\",\"bypasses_audited\"],\"additionalProperties\":false},\"evidence\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/definitions/evidence\"},\"minItems\":1,\"uniqueItems\":true},\"provenance\":{\"type\":\"object\",\"properties\":{\"origin\":{\"enum\":[\"manual\",\"application\",\"analysis\",\"mixed\"]},\"author\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"origin\",\"author\"],\"additionalProperties\":false},\"metadata\":{\"type\":\"object\"}},\"required\":[\"schema_version\",\"operation_id\",\"target\",\"entry\",\"admission\",\"invocation\",\"domain\",\"policy\",\"completion\",\"finalization\",\"lifecycle\",\"coverage\",\"required_capabilities\",\"extensions\",\"obligations\",\"evidence\",\"provenance\",\"metadata\"],\"additionalProperties\":false,\"definitions\":{\"file\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1},\"sha256\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"}},\"required\":[\"path\",\"sha256\"],\"additionalProperties\":false},\"evidence\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"minLength\":1},\"kind\":{\"enum\":[\"source_review\",\"application_contract\",\"fixture_definition\",\"analysis_rule\"]},\"file\":{\"$ref\":\"#/definitions/file\"},\"line\":{\"type\":\"integer\",\"minimum\":1},\"anchor\":{\"type\":\"string\",\"minLength\":1},\"note\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"id\",\"kind\",\"file\",\"line\",\"anchor\",\"note\"],\"additionalProperties\":false},\"obligation\":{\"type\":\"object\",\"properties\":{\"status\":{\"enum\":[\"satisfied\",\"needs_contract\",\"unknown\"]},\"basis\":{\"enum\":[\"manual_review\",\"application_contract\",\"analysis_rule\"]},\"evidence\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1},\"minItems\":1,\"uniqueItems\":true},\"reason\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"status\",\"basis\",\"evidence\",\"reason\"],\"additionalProperties\":false},\"work_protocol\":{\"type\":\"object\",\"properties\":{\"register_before_publish\":{\"type\":\"boolean\"},\"settle_exactly_once\":{\"type\":\"boolean\"},\"ownership\":{\"enum\":[\"registration_context\",\"execution_thread\"]},\"cancel_running\":{\"enum\":[\"wait_for_exit\",\"release_on_cancel\"]},\"close_registration\":{\"enum\":[\"atomic\",\"unspecified\"]}},\"required\":[\"register_before_publish\",\"settle_exactly_once\",\"ownership\",\"cancel_running\",\"close_registration\"],\"additionalProperties\":false},\"terminal\":{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"stage\",\"callback\"]},\"event\":{\"type\":\"string\",\"minLength\":1},\"register_before_publish\":{\"type\":\"boolean\"},\"guarantees_no_more_work\":{\"type\":\"boolean\"},\"cancel_signal\":{\"enum\":[\"request_only\",\"stopped_acknowledged\",\"unknown\"]}},\"required\":[\"kind\",\"event\",\"register_before_publish\",\"guarantees_no_more_work\",\"cancel_signal\"],\"additionalProperties\":false}}}";
    private static final JSONObject SCHEMA = SecondUiJson.parse(SOURCE_SCHEMA);
    private SecondUiSchema() {}

    public static void validate(JSONObject contract) {
        try {
            validateAt(contract, SCHEMA, "$", 0);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Invalid embedded schema", impossible);
        }
    }

    private static void validateAt(Object value, JSONObject schema, String path, int depth)
            throws JSONException {
        require(depth <= 64, path, "schema/input nesting limit exceeded");
        if (schema.has("$ref")) {
            String reference = schema.getString("$ref");
            require(reference.startsWith("#/definitions/"), path, "unsupported schema reference");
            validateAt(value, SCHEMA.getJSONObject("definitions")
                    .getJSONObject(reference.substring("#/definitions/".length())), path, depth + 1);
            return;
        }
        if (schema.has("anyOf")) {
            JSONArray alternatives = schema.getJSONArray("anyOf");
            boolean matched = false;
            for (int i = 0; i < alternatives.length(); i++) {
                try {
                    validateAt(value, alternatives.getJSONObject(i), path, depth + 1);
                    matched = true;
                    break;
                } catch (IllegalArgumentException ignored) {
                    // Each branch is validated independently against the same value.
                }
            }
            require(matched, path, "no schema alternative matched");
        }
        if (schema.has("type")) {
            String type = schema.getString("type");
            boolean matches;
            switch (type) {
                case "object": matches = value instanceof JSONObject; break;
                case "array": matches = value instanceof JSONArray; break;
                case "string": matches = value instanceof String; break;
                case "boolean": matches = value instanceof Boolean; break;
                case "null": matches = value == JSONObject.NULL; break;
                case "integer": matches = value instanceof Number
                        && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0; break;
                default: throw new IllegalStateException("Unsupported embedded schema type: " + type);
            }
            require(matches, path, "expected " + type);
        }
        if (schema.has("const")) {
            require(canonical(value).equals(canonical(schema.get("const"))), path, "constant mismatch");
        }
        if (schema.has("enum")) {
            JSONArray choices = schema.getJSONArray("enum");
            boolean matched = false;
            String key = canonical(value);
            for (int i = 0; i < choices.length(); i++) {
                if (key.equals(canonical(choices.get(i)))) { matched = true; break; }
            }
            require(matched, path, "value is not in enum");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray required = schema.optJSONArray("required");
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    require(object.has(required.getString(i)), path, "missing " + required.getString(i));
                }
            }
            JSONObject properties = schema.optJSONObject("properties");
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                if (properties != null && properties.has(key)) {
                    validateAt(object.get(key), properties.getJSONObject(key), path + "/" + key, depth + 1);
                } else {
                    require(!Boolean.FALSE.equals(schema.opt("additionalProperties")), path,
                            "unknown field " + key);
                }
            }
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            require(array.length() >= schema.optInt("minItems", 0), path, "array too short");
            Set<String> seen = schema.optBoolean("uniqueItems", false) ? new HashSet<>() : null;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (seen != null) require(seen.add(canonical(item)), path, "duplicate array item");
                if (schema.has("items")) {
                    validateAt(item, schema.getJSONObject("items"), path + "/" + i, depth + 1);
                }
            }
        }
        if (value instanceof String) {
            String string = (String) value;
            require(string.codePointCount(0, string.length()) >= schema.optInt("minLength", 0),
                    path, "string too short");
            if (schema.has("pattern")) {
                require(Pattern.compile(schema.getString("pattern"), Pattern.UNIX_LINES).matcher(string).find(),
                        path, "pattern mismatch");
            }
        }
        if (value instanceof Number && schema.has("minimum")) {
            require(new BigDecimal(value.toString()).compareTo(
                    new BigDecimal(schema.get("minimum").toString())) >= 0, path, "number too small");
        }
    }

    private static String canonical(Object value) throws JSONException {
        if (value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros().toString();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i != 0) result.append(',');
                result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() != 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return result.append('}').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value type");
    }

    private static void require(boolean condition, String path, String reason) {
        if (!condition) throw new IllegalArgumentException(path + ": " + reason);
    }
}
