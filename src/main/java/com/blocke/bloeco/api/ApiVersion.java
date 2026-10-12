package com.blocke.bloeco.api;

/** Semantic version of the public Bloeco API contract, independent from the plugin version. */
public record ApiVersion(int major, int minor, int patch) {
    public static final ApiVersion CURRENT = new ApiVersion(1, 0, 0);

    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("API version components cannot be negative");
        }
    }

    public boolean supportsMajor(int requestedMajor) {
        return major == requestedMajor;
    }
}
