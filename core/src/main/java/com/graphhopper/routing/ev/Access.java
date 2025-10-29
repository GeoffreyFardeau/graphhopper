package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;

public enum Access {
    MISSING,
    YES,
    NO;

    public static final String KEY = "access";

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static Access find(String name) {
        if (name == null)
            return MISSING;
        try {
            return Access.valueOf(Helper.toUpperCase(name));
        } catch (IllegalArgumentException ex) {
            return MISSING;
        }
    }

    public static String key() {
        return "access";
    }

    public static EnumEncodedValue<Access> create() {
        return new EnumEncodedValue<>(key(), Access.class);
    }
}
