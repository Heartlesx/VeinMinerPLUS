package com.extrarawstyle.veinminerplus.compat;

import com.extrarawstyle.veinminerplus.NetworkTarget;

import org.jetbrains.annotations.Nullable;

public record BindingResult(Status status, @Nullable NetworkTarget target) {
    public static BindingResult success(NetworkTarget target) {
        return new BindingResult(Status.SUCCESS, target);
    }

    public static BindingResult notNode() {
        return new BindingResult(Status.NOT_NODE, null);
    }

    public static BindingResult invalid() {
        return new BindingResult(Status.INVALID, null);
    }

    public enum Status {
        SUCCESS,
        NOT_NODE,
        INVALID
    }
}
