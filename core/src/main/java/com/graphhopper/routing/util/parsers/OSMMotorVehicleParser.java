package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.MotorVehicle;
import com.graphhopper.storage.IntsRef;

public class OSMMotorVehicleParser implements TagParser {
    private final EnumEncodedValue<MotorVehicle> motorVehicleEnc;

    public OSMMotorVehicleParser(EnumEncodedValue<MotorVehicle> motorVehicleEnc) {
        this.motorVehicleEnc = motorVehicleEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        String motorVehicleValue = way.getTag("motor_vehicle");
        if (motorVehicleValue != null) {
            MotorVehicle motorVehicle = MotorVehicle.find(motorVehicleValue);
            if (motorVehicle != MotorVehicle.MISSING) {
                motorVehicleEnc.setEnum(false, edgeId, edgeIntAccess, motorVehicle);
            }
        }
    }
}
