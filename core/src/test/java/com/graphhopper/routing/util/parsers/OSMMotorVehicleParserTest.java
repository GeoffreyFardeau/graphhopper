package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OSMMotorVehicleParserTest {
    private OSMMotorVehicleParser parser;
    private EnumEncodedValue<MotorVehicle> motorVehicleEnc;

    @BeforeEach
    public void setup() {
        motorVehicleEnc = new EnumEncodedValue<>(MotorVehicle.KEY, MotorVehicle.class);
        motorVehicleEnc.init(new EncodedValue.InitializerConfig());
        parser = new OSMMotorVehicleParser(motorVehicleEnc);
    }

    @Test
    public void testMotorVehicleYes() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("motor_vehicle", "yes");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(MotorVehicle.YES, motorVehicleEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testMotorVehicleNo() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("motor_vehicle", "no");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(MotorVehicle.NO, motorVehicleEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testMotorVehicleMissing() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        // No motor_vehicle tag
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(MotorVehicle.MISSING, motorVehicleEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testMotorVehicleUnknownValue() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("motor_vehicle", "unknown_value");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        // Should not set anything for unknown values
        assertEquals(MotorVehicle.MISSING, motorVehicleEnc.getEnum(false, edgeId, edgeIntAccess));
    }
}
