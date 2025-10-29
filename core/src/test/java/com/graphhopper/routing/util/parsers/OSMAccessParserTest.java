package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OSMAccessParserTest {
    private OSMAccessParser parser;
    private EnumEncodedValue<Access> accessEnc;

    @BeforeEach
    public void setup() {
        accessEnc = new EnumEncodedValue<>(Access.KEY, Access.class);
        accessEnc.init(new EncodedValue.InitializerConfig());
        parser = new OSMAccessParser(accessEnc);
    }

    @Test
    public void testAccessYes() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("access", "yes");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(Access.YES, accessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testAccessNo() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("access", "no");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(Access.NO, accessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testAccessMissing() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        // No access tag
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        assertEquals(Access.MISSING, accessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testAccessUnknownValue() {
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1);
        way.setTag("access", "unknown_value");
        
        parser.handleWayTags(edgeId, edgeIntAccess, way);
        // Should not set anything for unknown values
        assertEquals(Access.MISSING, accessEnc.getEnum(false, edgeId, edgeIntAccess));
    }
}
