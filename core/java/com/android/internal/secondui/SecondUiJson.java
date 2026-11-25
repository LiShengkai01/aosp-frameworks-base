/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.secondui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Bounded strict JSON object reader; does not use JSONTokener's lenient grammar.
 *
 * @hide
 */
public final class SecondUiJson {
    private static final int MAX_LENGTH = 131072;
    private static final int MAX_DEPTH = 64;
    private final String input;
    private int offset;

    private SecondUiJson(String input) { this.input = input; }

    /**
     * Reads one object document with unique decoded keys and standard JSON syntax.
     * The root object counts as nesting depth one. Length is measured in UTF-16
     * code units, as for String.length(). Numbers remain numbers, with exact
     * integer/decimal values; values outside org.json's finite numeric magnitude
     * are rejected rather than silently becoming a string or infinity.
     */
    public static JSONObject parse(String input) {
        if (input == null || input.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Missing or oversized Second UI JSON");
        }
        SecondUiJson reader = new SecondUiJson(input);
        try {
            Object result = reader.value(0);
            reader.whitespace();
            if (!(result instanceof JSONObject) || reader.offset != input.length()) {
                throw reader.error("Expected one complete object document");
            }
            return (JSONObject) result;
        } catch (JSONException | NumberFormatException malformed) {
            throw new IllegalArgumentException("Unsupported JSON value at offset " + reader.offset,
                    malformed);
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + offset);
    }

    private void whitespace() {
        while (offset < input.length()) {
            char c = input.charAt(offset);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
            offset++;
        }
    }

    private boolean consume(char expected) {
        if (offset < input.length() && input.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        whitespace();
        if (!consume(expected)) throw error("Expected '" + expected + "'");
    }

    private Object value(int depth) throws JSONException {
        whitespace();
        if (offset == input.length()) throw error("Missing JSON value");
        char c = input.charAt(offset);
        if (c == '{' || c == '[') {
            if (depth >= MAX_DEPTH) throw error("JSON nesting exceeds " + MAX_DEPTH);
            return c == '{' ? object(depth + 1) : array(depth + 1);
        }
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return JSONObject.NULL; }
        if (c == '-' || digit(c)) return number();
        throw error("Invalid JSON value");
    }

    private JSONObject object(int depth) throws JSONException {
        offset++; // '{'
        JSONObject result = new JSONObject();
        whitespace();
        if (consume('}')) return result;
        while (true) {
            whitespace();
            if (offset == input.length() || input.charAt(offset) != '"') {
                throw error("Object key must be a quoted string");
            }
            String key = string();
            if (result.has(key)) throw error("Duplicate JSON key");
            expect(':');
            result.put(key, value(depth));
            whitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private JSONArray array(int depth) throws JSONException {
        offset++; // '['
        JSONArray result = new JSONArray();
        whitespace();
        if (consume(']')) return result;
        while (true) {
            result.put(value(depth));
            whitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private void literal(String value) {
        if (!input.startsWith(value, offset)) throw error("Invalid JSON literal");
        offset += value.length();
    }

    private String string() {
        offset++; // '"'
        StringBuilder result = new StringBuilder();
        while (offset < input.length()) {
            char c = input.charAt(offset++);
            if (c == '"') return result.toString();
            if (c < 0x20) throw error("Unescaped control character in string");
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (offset == input.length()) throw error("Incomplete string escape");
            char escape = input.charAt(offset++);
            switch (escape) {
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case '/': result.append('/'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (input.length() - offset < 4) throw error("Incomplete unicode escape");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        char h = input.charAt(offset++);
                        int n = h >= '0' && h <= '9' ? h - '0'
                                : h >= 'a' && h <= 'f' ? h - 'a' + 10
                                : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                        if (n < 0) throw error("Invalid unicode escape");
                        code = (code << 4) | n;
                    }
                    result.append((char) code);
                    break;
                default: throw error("Invalid string escape");
            }
        }
        throw error("Unterminated string");
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private void digits() {
        int start = offset;
        while (offset < input.length() && digit(input.charAt(offset))) offset++;
        if (offset == start) throw error("Expected JSON digit");
    }

    private Number number() {
        int start = offset;
        consume('-');
        if (consume('0')) {
            if (offset < input.length() && digit(input.charAt(offset))) {
                throw error("Leading zero in JSON number");
            }
        } else {
            digits();
        }
        boolean decimal = false;
        if (consume('.')) { decimal = true; digits(); }
        if (consume('e') || consume('E')) {
            decimal = true;
            if (!consume('+')) consume('-');
            digits();
        }
        String literal = input.substring(start, offset);
        Number result;
        if (decimal) {
            result = new BigDecimal(literal);
        } else {
            BigInteger integer = new BigInteger(literal);
            if (integer.bitLength() < 32) result = integer.intValue();
            else if (integer.bitLength() < 64) result = integer.longValue();
            else result = integer;
        }
        if (!Double.isFinite(result.doubleValue())) {
            throw error("JSON number exceeds finite supported magnitude");
        }
        return result;
    }
}
