package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;

/**
 * This enum defines the access rights for ways and nodes according to OSM tagging.
 * See: https://wiki.openstreetmap.org/wiki/Key:access
 */
public enum Access {
    MISSING,        // No access tag specified
    YES,            // Public has official right of access
    NO,             // Public access prohibited
    PERMISSIVE,     // Open to general traffic, but can be revoked
    PRIVATE,        // Private access only
    DESIGNATED,     // Designated/preferred route (used with specific transport modes)
    DISCOURAGED,    // Legal but officially discouraged
    CUSTOMERS,      // Only for customers
    DESTINATION,    // Only for destination/local traffic
    AGRICULTURAL,   // Only for agricultural traffic
    FORESTRY,       // Only for forestry traffic
    DELIVERY,       // Only for delivery
    MILITARY,       // Only for military
    PERMIT,         // Requires a permit
    UNKNOWN;        // Access conditions unknown

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
