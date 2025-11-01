package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.Access;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.storage.IntsRef;

public class OSMAccessParser implements TagParser {
    private final EnumEncodedValue<Access> accessEnc;

    public OSMAccessParser(EnumEncodedValue<Access> accessEnc) {
        this.accessEnc = accessEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        String accessValue = way.getTag("access");
        if (accessValue != null) {
            Access access = Access.find(accessValue);
            if (access != Access.MISSING) {
                accessEnc.setEnum(false, edgeId, edgeIntAccess, access);
            }
        }
    }
}
