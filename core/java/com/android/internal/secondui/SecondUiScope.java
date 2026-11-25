/* Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.android.internal.secondui;

import java.util.Objects;

/**
 * Immutable logical scope. Identity never includes a UI or repository object address.
 *
 * @hide
 */
public final class SecondUiScope {
    public final String repository;
    public final String group;
    private SecondUiScope(String repository, String group) {
        this.repository = repository;
        this.group = group;
    }
    public static SecondUiScope application() { return new SecondUiScope(null, null); }
    public static SecondUiScope repository(String repository) {
        return new SecondUiScope(name(repository), null);
    }
    public static SecondUiScope data(String repository, String group) {
        return new SecondUiScope(name(repository), name(group));
    }
    private static String name(String value) {
        if (Objects.requireNonNull(value).isEmpty()) throw new IllegalArgumentException("Empty scope");
        return value;
    }
    public boolean overlaps(SecondUiScope other) {
        return repository == null || other.repository == null
                || repository.equals(other.repository)
                && (group == null || other.group == null || group.equals(other.group));
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof SecondUiScope)) return false;
        SecondUiScope s = (SecondUiScope) other;
        return Objects.equals(repository, s.repository) && Objects.equals(group, s.group);
    }
    @Override public int hashCode() { return Objects.hash(repository, group); }
    @Override public String toString() {
        return repository == null ? "application" : "repository(" + repository + ")/" + group;
    }
}
