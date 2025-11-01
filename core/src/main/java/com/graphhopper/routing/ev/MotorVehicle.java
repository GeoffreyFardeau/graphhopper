package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;

/**
 * This enum defines the access rights for motor vehicles according to OSM tagging.
 * Motor vehicles include cars, motorcycles, HGVs, buses, etc.
 * See: https://wiki.openstreetmap.org/wiki/Key:motor_vehicle
 */
public enum MotorVehicle {
    MISSING,        // No motor_vehicle tag specified
    YES,            // Motor vehicles allowed
    NO,             // Motor vehicles prohibited
    PERMISSIVE,     // Open to motor vehicles, but can be revoked
    PRIVATE,        // Private access only
    DESIGNATED,     // Designated for motor vehicles
    DESTINATION,    // Only for destination/local traffic
    AGRICULTURAL,   // Only for agricultural vehicles
    FORESTRY,       // Only for forestry vehicles
    DELIVERY,       // Only for delivery
    PERMIT,         // Requires a permit
    CUSTOMERS;      // Only for customers

    public static final String KEY = "motor_vehicle";

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static MotorVehicle find(String name) {
        if (name == null)
            return MISSING;
        try {
            return MotorVehicle.valueOf(Helper.toUpperCase(name));
        } catch (IllegalArgumentException ex) {
            return MISSING;
        }
    }

    public static String key() {
        return "motor_vehicle";
    }

    public static EnumEncodedValue<MotorVehicle> create() {
        return new EnumEncodedValue<>(key(), MotorVehicle.class);
    }
}
