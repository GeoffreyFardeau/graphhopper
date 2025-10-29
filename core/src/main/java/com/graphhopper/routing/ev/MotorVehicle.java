package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;

public enum MotorVehicle {
    MISSING,
    YES,
    NO;

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
