/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.carrotsearch.hppc.IntArrayList;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.SpeedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.*;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.details.PathDetailsFromEdges;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.graphhopper.search.KVStorage.KValue;
import static com.graphhopper.storage.AbstractGraphStorageTester.assertPList;
import static com.graphhopper.util.Parameters.Details.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Peter Karich
 */
public class PathTest {
    private final DecimalEncodedValue carAvSpeedEnc = new DecimalEncodedValueImpl("speed", 5, 5, true);
    private final EncodingManager carManager = EncodingManager.start().add(carAvSpeedEnc).
            add(VehicleAccess.create("car")).add(Roundabout.create()).add(RoadClass.create()).
            add(RoadEnvironment.create()).add(RoadClassLink.create()).add(MaxSpeed.create()).build();

    private final DecimalEncodedValue mixedCarSpeedEnc = new DecimalEncodedValueImpl("mixed_car_speed", 5, 5, true);
    private final BooleanEncodedValue mixedCarAccessEnc = VehicleAccess.create("car");
    private final DecimalEncodedValue mixedFootSpeedEnc = new DecimalEncodedValueImpl("mixed_foot_speed", 4, 1, true);
    private final EncodingManager mixedEncodingManager = EncodingManager.start().
            add(mixedCarAccessEnc).
            add(mixedCarSpeedEnc).add(mixedFootSpeedEnc).
            add(RoadClass.create()).
            add(RoadEnvironment.create()).
            add(RoadClassLink.create()).
            add(MaxSpeed.create()).
            add(Roundabout.create()).build();
    private final TranslationMap trMap = TranslationMapTest.SINGLETON;
    private final Translation tr = trMap.getWithFallBack(Locale.US);
    private final RoundaboutGraph roundaboutGraph = new RoundaboutGraph();
    private final Graph pathDetailGraph = generatePathDetailsGraph();

    @Test
    public void testFound() {
        BaseGraph g = new BaseGraph.Builder(carManager).create();
        Path p = new Path(g);
        assertFalse(p.isFound());
        assertEquals(0, p.getDistance(), 1e-7);
        assertEquals(0, p.calcNodes().size());
    }

    @Test
    public void testWayList() {
        BaseGraph g = new BaseGraph.Builder(carManager).create();
        NodeAccess na = g.getNodeAccess();
        na.setNode(0, 0.0, 0.1);
        na.setNode(1, 1.0, 0.1);
        na.setNode(2, 2.0, 0.1);

        EdgeIteratorState edge1 = g.edge(0, 1).setDistance(1000).set(carAvSpeedEnc, 10.0, 10.0);

        edge1.setWayGeometry(Helper.createPointList(8, 1, 9, 1));
        EdgeIteratorState edge2 = g.edge(2, 1).setDistance(2000).set(carAvSpeedEnc, 50.0, 50.0);
        edge2.setWayGeometry(Helper.createPointList(11, 1, 10, 1));

        SPTEntry e1 = new SPTEntry(edge2.getEdge(), 2, 1, new SPTEntry(edge1.getEdge(), 1, 1, new SPTEntry(0, 1)));
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path path = extractPath(g, weighting, e1);
        // 0-1-2
        assertPList(Helper.createPointList(0, 0.1, 8, 1, 9, 1, 1, 0.1, 10, 1, 11, 1, 2, 0.1), path.calcPoints());
        InstructionList instr = InstructionsFromEdges.calcInstructions(path, path.graph, weighting, carManager, tr);
        Instruction tmp = instr.get(0);
        assertEquals(3000.0, tmp.getDistance(), 0.0);
        assertEquals(140000L, tmp.getTime());
        assertEquals("continue", tmp.getTurnDescription(tr));
        assertEquals(6, tmp.getLength());

        tmp = instr.get(1);
        assertEquals(0.0, tmp.getDistance(), 0.0);
        assertEquals(0L, tmp.getTime());
        assertEquals("arrive at destination", tmp.getTurnDescription(tr));
        assertEquals(0, tmp.getLength());

        int acc = 0;
        for (Instruction instruction : instr) {
            acc += instruction.getLength();
        }
        assertEquals(path.calcPoints().size() - 1, acc);

        // force minor change for instructions
        edge2.setKeyValues(Map.of(STREET_NAME, new KValue("2")));
        na.setNode(3, 1.0, 1.0);
        g.edge(1, 3).setDistance(1000).set(carAvSpeedEnc, 10.0, 10.0);

        e1 = new SPTEntry(edge2.getEdge(), 2, 1,
                new SPTEntry(edge1.getEdge(), 1, 1,
                        new SPTEntry(0, 1)
                )
        );
        path = extractPath(g, weighting, e1);
        instr = InstructionsFromEdges.calcInstructions(path, path.graph, weighting, carManager, tr);

        tmp = instr.get(0);
        assertEquals(1000.0, tmp.getDistance(), 0);
        assertEquals(100000L, tmp.getTime());
        assertEquals("continue", tmp.getTurnDescription(tr));
        assertEquals(3, tmp.getLength());

        tmp = instr.get(1);
        assertEquals(2000.0, tmp.getDistance(), 0);
        assertEquals(40000L, tmp.getTime());
        assertEquals("turn sharp right onto 2", tmp.getTurnDescription(tr));
        assertEquals(3, tmp.getLength());
        acc = 0;
        for (Instruction instruction : instr) {
            acc += instruction.getLength();
        }
        assertEquals(path.calcPoints().size() - 1, acc);

        // now reverse order
        e1 = new SPTEntry(edge1.getEdge(), 0, 1, new SPTEntry(edge2.getEdge(), 1, 1, new SPTEntry(2, 1)));
        path = extractPath(g, weighting, e1);
        // 2-1-0
        assertPList(Helper.createPointList(2, 0.1, 11, 1, 10, 1, 1, 0.1, 9, 1, 8, 1, 0, 0.1), path.calcPoints());

        instr = InstructionsFromEdges.calcInstructions(path, path.graph, weighting, carManager, tr);
        tmp = instr.get(0);
        assertEquals(2000.0, tmp.getDistance(), 0);
        assertEquals(40000L, tmp.getTime());
        assertEquals("continue onto 2", tmp.getTurnDescription(tr));
        assertEquals(3, tmp.getLength());

        tmp = instr.get(1);
        assertEquals(1000.0, tmp.getDistance(), 0);
        assertEquals(100000L, tmp.getTime());
        assertEquals("turn sharp left", tmp.getTurnDescription(tr));
        assertEquals(3, tmp.getLength());
        acc = 0;
        for (Instruction instruction : instr) {
            acc += instruction.getLength();
        }
        assertEquals(path.calcPoints().size() - 1, acc);
    }

    @Test
    public void testFindInstruction() {
        BaseGraph g = new BaseGraph.Builder(carManager).create();
        NodeAccess na = g.getNodeAccess();
        na.setNode(0, 0.0, 0.0);
        na.setNode(1, 5.0, 0.0);
        na.setNode(2, 5.0, 0.5);
        na.setNode(3, 10.0, 0.5);
        na.setNode(4, 7.5, 0.25);
        na.setNode(5, 5.0, 1.0);

        EdgeIteratorState edge1 = g.edge(0, 1).setDistance(1000).set(carAvSpeedEnc, 50.0, 50.0);
        edge1.setWayGeometry(Helper.createPointList());
        edge1.setKeyValues(Map.of(STREET_NAME, new KValue("Street 1")));
        EdgeIteratorState edge2 = g.edge(1, 2).setDistance(1000).set(carAvSpeedEnc, 50.0, 50.0);
        edge2.setWayGeometry(Helper.createPointList());
        edge2.setKeyValues(Map.of(STREET_NAME, new KValue("Street 2")));
        EdgeIteratorState edge3 = g.edge(2, 3).setDistance(1000).set(carAvSpeedEnc, 50.0, 50.0);
        edge3.setWayGeometry(Helper.createPointList());
        edge3.setKeyValues(Map.of(STREET_NAME, new KValue("Street 3")));
        EdgeIteratorState edge4 = g.edge(3, 4).setDistance(500).set(carAvSpeedEnc, 50.0, 50.0);
        edge4.setWayGeometry(Helper.createPointList());
        edge4.setKeyValues(Map.of(STREET_NAME, new KValue("Street 4")));

        g.edge(1, 5).setDistance(10000).set(carAvSpeedEnc, 50.0, 50.0);
        g.edge(2, 5).setDistance(10000).set(carAvSpeedEnc, 50.0, 50.0);
        g.edge(3, 5).setDistance(100000).set(carAvSpeedEnc, 50.0, 50.0);

        SPTEntry e1 =
                new SPTEntry(edge4.getEdge(), 4, 1,
                        new SPTEntry(edge3.getEdge(), 3, 1,
                                new SPTEntry(edge2.getEdge(), 2, 1,
                                        new SPTEntry(edge1.getEdge(), 1, 1,
                                                new SPTEntry(0, 1)
                                        ))));
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path path = extractPath(g, weighting, e1);

        InstructionList il = InstructionsFromEdges.calcInstructions(path, path.graph, weighting, carManager, tr);
        assertEquals(5, il.size());
        assertEquals(Instruction.CONTINUE_ON_STREET, il.get(0).getSign());
        assertEquals(Instruction.TURN_RIGHT, il.get(1).getSign());
        assertEquals(Instruction.TURN_LEFT, il.get(2).getSign());
        assertEquals(Instruction.TURN_SHARP_LEFT, il.get(3).getSign());
        assertEquals(Instruction.FINISH, il.get(4).getSign());
    }

    /**
     * Test roundabout instructions for different profiles
     */
    @Test
    void testCalcInstructionsRoundabout() {
        calcInstructionsRoundabout(mixedCarSpeedEnc);
        calcInstructionsRoundabout(mixedFootSpeedEnc);
    }

    public void calcInstructionsRoundabout(DecimalEncodedValue speedEnc) {
        Weighting weighting = new SpeedWeighting(speedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 8);
        assertTrue(p.isFound());
        assertEquals("[1, 2, 3, 4, 5, 8]", p.calcNodes().toString());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        // Test instructions
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("continue onto MainStreet 1 2",
                        "At roundabout, take exit 3 onto 5-8",
                        "arrive at destination"),
                tmpList);
        // Test Radian
        double delta = roundaboutGraph.getAngle(1, 2, 5, 8);
        RoundaboutInstruction instr = (RoundaboutInstruction) wayList.get(1);
        assertEquals(delta, instr.getTurnAngle(), 0.01);

        // case of continuing a street through a roundabout
        p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED).
                calcPath(1, 7);
        wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("continue onto MainStreet 1 2",
                        "At roundabout, take exit 2 onto MainStreet 4 7",
                        "arrive at destination"),
                tmpList);
        // Test Radian
        delta = roundaboutGraph.getAngle(1, 2, 4, 7);
        instr = (RoundaboutInstruction) wayList.get(1);
        assertEquals(delta, instr.getTurnAngle(), 0.01);
    }

    @Test
    public void testCalcInstructionsRoundaboutBegin() {
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(2, 8);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("At roundabout, take exit 3 onto 5-8",
                        "arrive at destination"),
                tmpList);
    }

    @Test
    public void testCalcInstructionsRoundaboutDirectExit() {
        roundaboutGraph.inverse3to9();
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(6, 8);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("continue onto 3-6",
                        "At roundabout, take exit 3 onto 5-8",
                        "arrive at destination"),
                tmpList);
        roundaboutGraph.inverse3to9();
    }

    @Test
    public void testCalcAverageSpeedDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);

        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(AVERAGE_SPEED), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> averageSpeedDetails = details.get(AVERAGE_SPEED);
        assertEquals(4, averageSpeedDetails.size());
        assertEquals(162.2, (double) averageSpeedDetails.get(0).getValue(), 1.e-3);
        assertEquals(327.3, (double) averageSpeedDetails.get(1).getValue(), 1.e-3);
        assertEquals(36.0, (double) averageSpeedDetails.get(2).getValue(), 1.e-3);
        assertEquals(162.2, (double) averageSpeedDetails.get(3).getValue(), 1.e-3);

        assertEquals(0, averageSpeedDetails.get(0).getFirst());
        assertEquals(1, averageSpeedDetails.get(1).getFirst());
        assertEquals(2, averageSpeedDetails.get(2).getFirst());
        assertEquals(3, averageSpeedDetails.get(3).getFirst());
        assertEquals(4, averageSpeedDetails.get(3).getLast());
    }

    @Test
    public void testCalcAverageSpeedDetailsWithShortDistances_issue1848() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 6);
        assertTrue(p.isFound());
        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(AVERAGE_SPEED), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());
        List<PathDetail> averageSpeedDetails = details.get(AVERAGE_SPEED);
        assertEquals(4, averageSpeedDetails.size());

        // reverse path includes 'null' value as first
        p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(6, 1);
        assertTrue(p.isFound());
        details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(AVERAGE_SPEED), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());
        averageSpeedDetails = details.get(AVERAGE_SPEED);
        assertEquals(5, averageSpeedDetails.size());
        assertNull(averageSpeedDetails.get(0).getValue());
    }

    @Test
    public void testCalcStreetNameDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(STREET_NAME), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> streetNameDetails = details.get(STREET_NAME);
        assertEquals(1, details.size());

        assertEquals(4, streetNameDetails.size());
        assertEquals("1-2", streetNameDetails.get(0).getValue());
        assertEquals("2-3", streetNameDetails.get(1).getValue());
        assertEquals("3-4", streetNameDetails.get(2).getValue());
        assertEquals("4-5", streetNameDetails.get(3).getValue());

        assertEquals(0, streetNameDetails.get(0).getFirst());
        assertEquals(1, streetNameDetails.get(1).getFirst());
        assertEquals(2, streetNameDetails.get(2).getFirst());
        assertEquals(3, streetNameDetails.get(3).getFirst());
        assertEquals(4, streetNameDetails.get(3).getLast());
    }

    @Test
    public void testCalcEdgeIdDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(EDGE_ID), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> edgeIdDetails = details.get(EDGE_ID);
        assertEquals(4, edgeIdDetails.size());
        assertEquals(0, edgeIdDetails.get(0).getValue());
        // This is out of order because we don't create the edges in order
        assertEquals(2, edgeIdDetails.get(1).getValue());
        assertEquals(3, edgeIdDetails.get(2).getValue());
        assertEquals(1, edgeIdDetails.get(3).getValue());

        assertEquals(0, edgeIdDetails.get(0).getFirst());
        assertEquals(1, edgeIdDetails.get(1).getFirst());
        assertEquals(2, edgeIdDetails.get(2).getFirst());
        assertEquals(3, edgeIdDetails.get(3).getFirst());
        assertEquals(4, edgeIdDetails.get(3).getLast());
    }

    @Test
    public void testCalcEdgeKeyDetailsForward() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(EDGE_KEY), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        List<PathDetail> edgeKeyDetails = details.get(EDGE_KEY);

        assertEquals(4, edgeKeyDetails.size());
        assertEquals(0, edgeKeyDetails.get(0).getValue());
        assertEquals(4, edgeKeyDetails.get(1).getValue());
        assertEquals(6, edgeKeyDetails.get(2).getValue());
        assertEquals(2, edgeKeyDetails.get(3).getValue());
    }

    @Test
    public void testCalcEdgeKeyDetailsBackward() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(5, 1);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(EDGE_KEY), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        List<PathDetail> edgeKeyDetails = details.get(EDGE_KEY);

        assertEquals(4, edgeKeyDetails.size());
        assertEquals(3, edgeKeyDetails.get(0).getValue());
        assertEquals(7, edgeKeyDetails.get(1).getValue());
        assertEquals(5, edgeKeyDetails.get(2).getValue());
        assertEquals(1, edgeKeyDetails.get(3).getValue());
    }

    @Test
    public void testCalcTimeDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());
        assertEquals(IntArrayList.from(1, 2, 3, 4, 5), p.calcNodes());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(TIME), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> timeDetails = details.get(TIME);
        assertEquals(4, timeDetails.size());
        assertEquals(111L, timeDetails.get(0).getValue());
        assertEquals(55L, timeDetails.get(1).getValue());
        assertEquals(1000L, timeDetails.get(2).getValue());
        assertEquals(111L, timeDetails.get(3).getValue());

        assertEquals(0, timeDetails.get(0).getFirst());
        assertEquals(1, timeDetails.get(1).getFirst());
        assertEquals(2, timeDetails.get(2).getFirst());
        assertEquals(3, timeDetails.get(3).getFirst());
        assertEquals(4, timeDetails.get(3).getLast());
    }

    @Test
    public void testCalcDistanceDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(DISTANCE), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> distanceDetails = details.get(DISTANCE);
        assertEquals(5D, distanceDetails.get(0).getValue());
        assertEquals(5D, distanceDetails.get(1).getValue());
        assertEquals(10D, distanceDetails.get(2).getValue());
        assertEquals(5D, distanceDetails.get(3).getValue());
    }

    @Test
    public void testCalcIntersectionDetails() {
        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(pathDetailGraph, weighting, TraversalMode.NODE_BASED).calcPath(1, 5);
        assertTrue(p.isFound());

        Map<String, List<PathDetail>> details = PathDetailsFromEdges.calcDetails(p, carManager, weighting,
                List.of(INTERSECTION), new PathDetailsBuilderFactory(), 0, pathDetailGraph);
        assertEquals(1, details.size());

        List<PathDetail> intersectionDetails = details.get(INTERSECTION);
        assertEquals(4, intersectionDetails.size());

        Map<String, Object> intersectionMap = new HashMap<>();
        intersectionMap.put("out", 0);
        intersectionMap.put("entries", List.of(true));
        intersectionMap.put("bearings", List.of(90));

        assertEquals(intersectionMap, intersectionDetails.get(0).getValue());

        intersectionMap.clear();
        intersectionMap.put("out", 0);
        intersectionMap.put("in", 1);
        intersectionMap.put("entries", List.of(true, false));
        intersectionMap.put("bearings", List.of(90, 270));

        assertEquals(intersectionMap, intersectionDetails.get(1).getValue());
    }

    /**
     * case with one edge being not an exit
     */
    @Test
    public void testCalcInstructionsRoundabout2() {
        roundaboutGraph.inverse3to6();
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 8);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("continue onto MainStreet 1 2",
                        "At roundabout, take exit 2 onto 5-8",
                        "arrive at destination"),
                tmpList);
        // Test Radian
        double delta = roundaboutGraph.getAngle(1, 2, 5, 8);
        RoundaboutInstruction instr = (RoundaboutInstruction) wayList.get(1);
        assertEquals(delta, instr.getTurnAngle(), 0.01);
        roundaboutGraph.inverse3to6();
    }

    @Test
    public void testCalcInstructionsRoundaboutIssue353() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        //
        //          8
        //           \
        //            5
        //           /  \
        //  11- 1 - 2    4 - 7
        //      |     \  /
        //      10 -9 -3
        //       \    |
        //        --- 6
        na.setNode(1, 52.514, 13.348);
        na.setNode(2, 52.514, 13.349);
        na.setNode(3, 52.5135, 13.35);
        na.setNode(4, 52.514, 13.351);
        na.setNode(5, 52.5145, 13.351);
        na.setNode(6, 52.513, 13.35);
        na.setNode(7, 52.514, 13.352);
        na.setNode(8, 52.515, 13.351);

        na.setNode(9, 52.5135, 13.349);
        na.setNode(10, 52.5135, 13.348);
        na.setNode(11, 52.514, 13.347);

        graph.edge(2, 1).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("MainStreet 2 1")));
        graph.edge(1, 11).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("MainStreet 1 11")));

        // roundabout
        EdgeIteratorState tmpEdge;
        tmpEdge = graph.edge(3, 9).set(carAvSpeedEnc, 60, 0).setDistance(2).setKeyValues(Map.of(STREET_NAME, new KValue("3-9")));
        BooleanEncodedValue carManagerRoundabout = carManager.getBooleanEncodedValue(Roundabout.KEY);
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(9, 10).set(carAvSpeedEnc, 60, 0).setDistance(2).setKeyValues(Map.of(STREET_NAME, new KValue("9-10")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(6, 10).set(carAvSpeedEnc, 60, 0).setDistance(2).setKeyValues(Map.of(STREET_NAME, new KValue("6-10")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(10, 1).set(carAvSpeedEnc, 60, 0).setDistance(2).setKeyValues(Map.of(STREET_NAME, new KValue("10-1")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(3, 2).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("2-3")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(4, 3).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("3-4")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(5, 4).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("4-5")));
        tmpEdge.set(carManagerRoundabout, true);
        tmpEdge = graph.edge(2, 5).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("5-2")));
        tmpEdge.set(carManagerRoundabout, true);

        graph.edge(4, 7).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("MainStreet 4 7")));
        graph.edge(5, 8).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("5-8")));
        graph.edge(3, 6).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("3-6")));
        BooleanEncodedValue carAccessEncTmp = carManager.getBooleanEncodedValue(VehicleAccess.key("car"));
        AllEdgesIterator iter = graph.getAllEdges();
        while (iter.next()) {
            if (iter.get(carAvSpeedEnc) > 0) iter.set(carAccessEncTmp, true);
            if (iter.getReverse(carAvSpeedEnc) > 0) iter.setReverse(carAccessEncTmp, true);
        }

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED)
                .calcPath(6, 11);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("At roundabout, take exit 1 onto MainStreet 1 11",
                        "arrive at destination"),
                tmpList);
    }

    @Test
    public void testCalcInstructionsRoundaboutClockwise() {
        roundaboutGraph.setRoundabout(true);
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 8);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);
        List<String> tmpList = getTurnDescriptions(wayList);
        assertEquals(List.of("continue onto MainStreet 1 2",
                        "At roundabout, take exit 1 onto 5-8",
                        "arrive at destination"),
                tmpList);
        // Test Radian
        double delta = roundaboutGraph.getAngle(1, 2, 5, 8);
        RoundaboutInstruction instr = (RoundaboutInstruction) wayList.get(1);
        assertEquals(delta, instr.getTurnAngle(), 0.01);
    }

    @Test
    public void testCalcInstructionsIgnoreContinue() {
        // Follow a couple of straight edges, including a name change
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(4, 11);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);

        // Contain only start and finish instruction, no CONTINUE
        assertEquals(2, wayList.size());
    }

    @Test
    public void testCalcInstructionsIgnoreTurnIfNoAlternative() {
        // The street turns left, but there is not turn
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(10, 12);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);

        // Contain only start and finish instruction
        assertEquals(2, wayList.size());
    }

    @Test
    public void testCalcInstructionForForkWithSameName() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        // Actual example: point=48.982618%2C13.122021&point=48.982336%2C13.121002
        // 1-2 & 2-4 have the same Street name, but other from that, it would be hard to see the difference
        // We have to enforce a turn instruction here
        //      3
        //        \
        //          2   --  1
        //        /
        //      4
        na.setNode(1, 48.982618, 13.122021);
        na.setNode(2, 48.982565, 13.121597);
        na.setNode(3, 48.982611, 13.121012);
        na.setNode(4, 48.982336, 13.121002);

        graph.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Regener Weg")));
        graph.edge(2, 4).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Regener Weg")));
        graph.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5);

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(-7, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionForMotorwayFork() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        // Actual example: point=48.909071%2C8.647136&point=48.908789%2C8.649244
        // 1-2 & 2-4 is a motorway, 2-3 is a motorway_link
        // We should skip the instruction here
        //      1 ---- 2 ---- 4
        //              \
        //               3
        na.setNode(1, 48.909071, 8.647136);
        na.setNode(2, 48.908962, 8.647978);
        na.setNode(3, 48.908867, 8.648155);
        na.setNode(4, 48.908789, 8.649244);

        EnumEncodedValue<RoadClass> roadClassEnc = carManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLinkEnc = carManager.getBooleanEncodedValue(RoadClassLink.KEY);

        graph.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("A 8"))).set(roadClassEnc, RoadClass.MOTORWAY).set(roadClassLinkEnc, false);
        graph.edge(2, 4).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("A 8"))).set(roadClassEnc, RoadClass.MOTORWAY).set(roadClassLinkEnc, false);
        graph.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5).set(roadClassEnc, RoadClass.MOTORWAY).set(roadClassLinkEnc, true);

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(2, wayList.size());
    }

    @Test
    public void testFerry() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        //      1 ---- 2 ---- 3 ---- 4
        na.setNode(1, 48.909071, 8.647136);
        na.setNode(2, 48.909071, 8.647978);
        na.setNode(3, 48.909071, 8.648155);
        na.setNode(3, 48.909071, 8.648200);

        EnumEncodedValue<RoadEnvironment> roadEnvEnc = carManager.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);

        graph.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).set(roadEnvEnc, RoadEnvironment.ROAD).
                setKeyValues(Map.of(STREET_NAME, new KValue("A B")));
        graph.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5).set(roadEnvEnc, RoadEnvironment.FERRY).
                setKeyValues(Map.of(STREET_NAME, new KValue("B C")));
        graph.edge(3, 4).set(carAvSpeedEnc, 60, 60).setDistance(5).set(roadEnvEnc, RoadEnvironment.ROAD).
                setKeyValues(Map.of(STREET_NAME, new KValue("C D")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);
        assertEquals(4, wayList.size());
        assertEquals("continue onto A B", wayList.get(0).getTurnDescription(tr));
        assertEquals("Attention, take ferry (B C)", wayList.get(1).getTurnDescription(tr));
        assertEquals(Instruction.FERRY, wayList.get(1).getSign());
        assertEquals("leave ferry and turn right onto C D", wayList.get(2).getTurnDescription(tr));
        assertEquals(Instruction.TURN_RIGHT, wayList.get(2).getSign());
        assertEquals("arrive at destination", wayList.get(3).getTurnDescription(tr));
    }

    @Test
    public void testCalcInstructionsEnterMotorway() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        // Actual example: point=48.630533%2C9.459416&point=48.630544%2C9.459829
        // 1 -2 -3 is a motorway and tagged as oneway
        //   1 ->- 2 ->- 3
        //        /
        //      4
        na.setNode(1, 48.630647, 9.459041);
        na.setNode(2, 48.630586, 9.459604);
        na.setNode(3, 48.630558, 9.459851);
        na.setNode(4, 48.63054, 9.459406);

        graph.edge(1, 2).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));
        graph.edge(2, 3).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));
        graph.edge(4, 2).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED)
                .calcPath(4, 3);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        // no turn instruction for entering the highway
        assertEquals(2, wayList.size());
    }

    @Test
    public void testCalcInstructionsMotorwayJunction() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Actual example: point=48.70672%2C9.164266&point=48.706805%2C9.162995
        // A typical motorway junction, when following 1-2-3, there should be a keep right at 2
        //             -- 4
        //          /
        //   1 -- 2 -- 3
        na.setNode(1, 48.70672, 9.164266);
        na.setNode(2, 48.706741, 9.163719);
        na.setNode(3, 48.706805, 9.162995);
        na.setNode(4, 48.706705, 9.16329);

        g.edge(1, 2).setDistance(5).set(carAvSpeedEnc, 60, 0).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));
        g.edge(2, 3).setDistance(5).set(carAvSpeedEnc, 60, 0).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));
        g.edge(2, 4).setDistance(5).set(carAvSpeedEnc, 60, 0).setKeyValues(Map.of(STREET_NAME, new KValue("A 8")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 3);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        // TODO this should be a keep_right
        assertEquals(0, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionsOntoOneway() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Actual example: point=-33.824566%2C151.187834&point=-33.82441%2C151.188231
        // 1 -2 -3 is a oneway
        //   1 ->- 2 ->- 3
        //         |
        //         4
        na.setNode(1, -33.824245, 151.187866);
        na.setNode(2, -33.824335, 151.188017);
        na.setNode(3, -33.824415, 151.188177);
        na.setNode(4, -33.824437, 151.187925);

        g.edge(1, 2).setDistance(5).set(carAvSpeedEnc, 60, 0).setKeyValues(Map.of(STREET_NAME, new KValue("Pacific Highway")));
        g.edge(2, 3).setDistance(5).set(carAvSpeedEnc, 60, 0).setKeyValues(Map.of(STREET_NAME, new KValue("Pacific Highway")));
        g.edge(4, 2).setDistance(5).set(carAvSpeedEnc, 60, 60).setKeyValues(Map.of(STREET_NAME, new KValue("Greenwich Road")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(4, 3);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(2, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionIssue1047() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Actual example: point=51.367105%2C14.491246&point=51.369048%2C14.483092
        // 1-2 & 2-3 is a road that is turning right, 2-4 is a that is branching off.
        // When driving 1-2-4, we should create an instruction notifying the user to continue straight instead of turning and following the road
        // When driving 1-2-3, we should create an instruction as well
        //
        //      1 ---- 2 ---- 4
        //             |
        //             3
        na.setNode(1, 51.367544, 14.488209);
        na.setNode(2, 51.368046, 14.486525);
        na.setNode(3, 51.36875, 14.487019);
        na.setNode(4, 51.368428, 14.485173);

        EnumEncodedValue<RoadClass> roadClassEnc = carManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLinkEnc = carManager.getBooleanEncodedValue(RoadClassLink.KEY);

        g.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("B 156"))).set(roadClassEnc, RoadClass.PRIMARY).set(roadClassLinkEnc, false);
        g.edge(2, 4).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("S 108"))).set(roadClassEnc, RoadClass.SECONDARY).set(roadClassLinkEnc, false);
        g.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("B 156"))).set(roadClassEnc, RoadClass.PRIMARY).set(roadClassLinkEnc, false);

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());

        p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 3);
        assertTrue(p.isFound());
        wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
    }

    @Test
    public void testCalcInstructionContinueLeavingStreet() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // When leaving the current street via a Continue, we should show it
        //       3
        //        \
        //     4 - 2   --  1
        na.setNode(1, 48.982618, 13.122021);
        na.setNode(2, 48.982565, 13.121597);
        na.setNode(3, 48.982611, 13.121012);
        na.setNode(4, 48.982565, 13.121002);

        g.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Regener Weg")));
        g.edge(2, 4).set(carAvSpeedEnc, 60, 60).setDistance(5);
        g.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Regener Weg")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(-7, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionSlightTurn() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Real Situation: point=48.411927%2C15.599197&point=48.412094%2C15.598816
        // When reaching this Crossing, you cannot know if you should turn left or right
        // Google Maps and Bing show a turn, OSRM does not
        //  1 ---2--- 3
        //       \
        //        4
        na.setNode(1, 48.412094, 15.598816);
        na.setNode(2, 48.412055, 15.599068);
        na.setNode(3, 48.412034, 15.599411);
        na.setNode(4, 48.411927, 15.599197);

        g.edge(1, 2).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Stöhrgasse")));
        g.edge(2, 3).set(carAvSpeedEnc, 60, 60).setDistance(5);
        g.edge(2, 4).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Stöhrgasse")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(4, 1);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(-1, wayList.get(1).getSign());
    }

    @Test
    public void testUTurnLeft() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Real Situation: point=48.402116%2C9.994367&point=48.402198%2C9.99507
        //       7
        //       |
        //  4----5----6
        //       |
        //  1----2----3
        na.setNode(1, 48.402116, 9.994367);
        na.setNode(2, 48.402198, 9.99507);
        na.setNode(3, 48.402344, 9.996266);
        na.setNode(4, 48.402191, 9.994351);
        na.setNode(5, 48.402298, 9.995053);
        na.setNode(6, 48.402422, 9.996067);
        na.setNode(7, 48.402604, 9.994962);

        g.edge(1, 2).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Olgastraße")));
        g.edge(2, 3).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Olgastraße")));
        g.edge(6, 5).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Olgastraße")));
        g.edge(5, 4).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Olgastraße")));
        g.edge(2, 5).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Neithardtstraße")));
        g.edge(5, 7).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Neithardtstraße")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 4);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(Instruction.U_TURN_LEFT, wayList.get(1).getSign());
    }

    @Test
    public void testUTurnRight() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Real Situation: point=-33.885758,151.181472&point=-33.885692,151.181445
        //       7
        //       |
        //  4----5----6
        //       |
        //  3----2----1
        na.setNode(1, -33.885758, 151.181472);
        na.setNode(2, -33.885852, 151.180968);
        na.setNode(3, -33.885968, 151.180501);
        na.setNode(4, -33.885883, 151.180442);
        na.setNode(5, -33.885772, 151.180941);
        na.setNode(6, -33.885692, 151.181445);
        na.setNode(7, -33.885692, 151.181445);

        g.edge(1, 2).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Parramatta Road")));
        g.edge(2, 3).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Parramatta Road")));
        g.edge(4, 5).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Parramatta Road")));
        g.edge(5, 6).set(carAvSpeedEnc, 60, 0).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Parramatta Road")));
        g.edge(2, 5).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Larkin Street")));
        g.edge(5, 7).set(carAvSpeedEnc, 60, 60).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("Larkin Street")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 6);
        assertTrue(p.isFound());
        assertEquals(IntArrayList.from(1, 2, 5, 6), p.calcNodes());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(List.of("continue onto Parramatta Road", "make a U-turn onto Parramatta Road", "arrive at destination"),
                getTurnDescriptions(wayList));
        assertEquals(3, wayList.size());
        assertEquals(Instruction.U_TURN_RIGHT, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionsForTurn() {
        // The street turns left, but there is not turn
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(11, 13);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);

        // Contain start, turn, and finish instruction
        assertEquals(3, wayList.size());
        // Assert turn right
        assertEquals(2, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionsForSlightTurnWithOtherSlightTurn() {
        // Test for a fork with two slight turns. Since there are two slight turns, show the turn instruction
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(12, 16);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);

        // Contain start, turn, and finish instruction
        assertEquals(3, wayList.size());
        // Assert turn right
        assertEquals(7, wayList.get(1).getSign());
    }

    @Test
    public void testCalcInstructionsForSlightTurnOntoDifferentStreet() {
        final BaseGraph g = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = g.getNodeAccess();

        // Actual example: point=48.76445%2C8.679054&point=48.764152%2C8.678722
        //      1
        //     /
        // 2 - 3 - 4
        //
        na.setNode(1, 48.76423, 8.679103);
        na.setNode(2, 48.76417, 8.678647);
        na.setNode(3, 48.764149, 8.678926);
        na.setNode(4, 48.764085, 8.679183);

        g.edge(1, 3).setDistance(5).set(carAvSpeedEnc, 60, 60).setKeyValues(Map.of(STREET_NAME, new KValue("Talstraße, new KValue( K 4313")));
        g.edge(2, 3).setDistance(5).set(carAvSpeedEnc, 60, 60).setKeyValues(Map.of(STREET_NAME, new KValue("Calmbacher Straße, new KValue( K 4312")));
        g.edge(3, 4).setDistance(5).set(carAvSpeedEnc, 60, 60).setKeyValues(Map.of(STREET_NAME, new KValue("Calmbacher Straße, new KValue( K 4312")));

        Weighting weighting = new SpeedWeighting(carAvSpeedEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED)
                .calcPath(1, 2);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, carManager, tr);

        assertEquals(3, wayList.size());
        assertEquals(Instruction.TURN_SLIGHT_RIGHT, wayList.get(1).getSign());
    }

    @Test
    public void testIgnoreInstructionsForSlightTurnWithOtherTurn() {
        // Test for a fork with one slight turn and one actual turn. We are going along the slight turn. No turn instruction needed in this case
        Weighting weighting = new SpeedWeighting(mixedCarSpeedEnc);
        Path p = new Dijkstra(roundaboutGraph.g, weighting, TraversalMode.NODE_BASED)
                .calcPath(16, 19);
        assertTrue(p.isFound());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, p.graph, weighting, mixedEncodingManager, tr);

        // Contain start, and finish instruction
        assertEquals(2, wayList.size());
    }

    @Test
    public void testFootAndCar_issue3081() {
        BooleanEncodedValue carAccessEnc = VehicleAccess.create("car");
        BooleanEncodedValue footAccessEnc = VehicleAccess.create("foot");
        BooleanEncodedValue rdEnc = Roundabout.create();
        EncodingManager manager = EncodingManager.start().
                add(carAccessEnc).
                add(footAccessEnc).
                add(RoadClass.create()).
                add(RoadClassLink.create()).
                add(RoadEnvironment.create()).
                add(MaxSpeed.create()).
                add(rdEnc).build();

        final BaseGraph g = new BaseGraph.Builder(manager).create();
        final NodeAccess na = g.getNodeAccess();

        // Actual example is here 45.7742,4.868 (but a few roads left out)
        //      0 1
        //       \|
        //        2<-3<--4
        //      /     \
        //      |      5-->6
        //      \     /
        //    7--8-->9<--10

        na.setNode(0, 52.503809, 13.410198);
        na.setNode(1, 52.503871, 13.410249);
        na.setNode(2, 52.503751, 13.410377);
        na.setNode(3, 52.50387, 13.410807);
        na.setNode(4, 52.503989, 13.41094);
        na.setNode(5, 52.503794, 13.411024);
        na.setNode(6, 52.503925, 13.411034);
        na.setNode(7, 52.503277, 13.41041);
        na.setNode(8, 52.50344, 13.410545);
        na.setNode(9, 52.503536, 13.411099);
        na.setNode(10, 52.503515, 13.411178);

        g.edge(0, 2).setDistance(5).set(carAccessEnc, true, true).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Nordwest")));
        // edge 1-2 does not exist in real world, but we need it to test a few other situations
        g.edge(1, 2).setDistance(5).set(carAccessEnc, false, false).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Nordwest, foot-only")));
        g.edge(4, 3).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Nordeast in")));
        g.edge(5, 6).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Nordeast out")));
        g.edge(10, 9).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Southeast in")));
        g.edge(7, 8).setDistance(5).set(carAccessEnc, true, true).set(footAccessEnc, true, true).setKeyValues(Map.of(STREET_NAME, new KValue("Southwest")));

        g.edge(3, 2).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, false).set(rdEnc, true).setKeyValues(Map.of(STREET_NAME, new KValue("roundabout")));
        g.edge(5, 3).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, false).set(rdEnc, true).setKeyValues(Map.of(STREET_NAME, new KValue("roundabout")));
        g.edge(9, 5).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, false).set(rdEnc, true).setKeyValues(Map.of(STREET_NAME, new KValue("roundabout")));
        g.edge(8, 9).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, false).set(rdEnc, true).setKeyValues(Map.of(STREET_NAME, new KValue("roundabout")));
        g.edge(2, 8).setDistance(5).set(carAccessEnc, true, false).set(footAccessEnc, true, false).set(rdEnc, true).setKeyValues(Map.of(STREET_NAME, new KValue("roundabout")));

        Weighting weighting = new AccessWeighting(footAccessEnc);
        Path p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED).calcPath(7, 10);
        assertEquals("[7, 8, 9, 10]", p.calcNodes().toString());
        InstructionList wayList = InstructionsFromEdges.calcInstructions(p, g, weighting, manager, tr);
        assertEquals("At roundabout, take exit 1 onto Southeast in", wayList.get(1).getTurnDescription(tr));

        p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED).calcPath(10, 1);
        assertEquals("[10, 9, 5, 3, 2, 1]", p.calcNodes().toString());
        wayList = InstructionsFromEdges.calcInstructions(p, g, weighting, manager, tr);
        assertEquals("At roundabout, take exit 2 onto Nordwest, foot-only", wayList.get(1).getTurnDescription(tr));

        p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED).calcPath(10, 4);
        assertEquals("[10, 9, 5, 3, 4]", p.calcNodes().toString());
        wayList = InstructionsFromEdges.calcInstructions(p, g, weighting, manager, tr);
        assertEquals("At roundabout, take exit 1 onto Nordeast in", wayList.get(1).getTurnDescription(tr));

        p = new Dijkstra(g, weighting, TraversalMode.NODE_BASED).calcPath(10, 6);
        assertEquals("[10, 9, 5, 6]", p.calcNodes().toString());
        wayList = InstructionsFromEdges.calcInstructions(p, g, weighting, manager, tr);
        assertEquals("At roundabout, take exit 1 onto Nordeast out", wayList.get(1).getTurnDescription(tr));
    }

    static class AccessWeighting implements Weighting {
        private final BooleanEncodedValue accessEnc;

        public AccessWeighting(BooleanEncodedValue accessEnc) {
            this.accessEnc = accessEnc;
        }

        @Override
        public double calcMinWeightPerDistance() {
            throw new IllegalStateException();
        }

        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            return (reverse && edgeState.getReverse(accessEnc) || edgeState.get(accessEnc)) ? 1 : Double.POSITIVE_INFINITY;
        }

        @Override
        public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
            return (reverse && edgeState.getReverse(accessEnc) || edgeState.get(accessEnc)) ? 1000 : 0;
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return 0;
        }

        @Override
        public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
            return 0;
        }

        @Override
        public boolean hasTurnCosts() {
            return false;
        }

        @Override
        public String getName() {
            return "access";
        }
    }

    List<String> getTurnDescriptions(InstructionList instructionJson) {
        List<String> list = new ArrayList<>();
        for (Instruction instruction : instructionJson) {
            list.add(instruction.getTurnDescription(tr));
        }
        return list;
    }

    private Graph generatePathDetailsGraph() {
        final BaseGraph graph = new BaseGraph.Builder(carManager).create();
        final NodeAccess na = graph.getNodeAccess();

        na.setNode(1, 52.514, 13.348);
        na.setNode(2, 52.514, 13.349);
        na.setNode(3, 52.514, 13.350);
        na.setNode(4, 52.515, 13.349);
        na.setNode(5, 52.516, 13.3452);
        na.setNode(6, 52.516, 13.344);

        graph.edge(1, 2).set(carAvSpeedEnc, 45, 45).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("1-2")));
        graph.edge(4, 5).set(carAvSpeedEnc, 45, 45).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("4-5")));
        graph.edge(2, 3).set(carAvSpeedEnc, 90, 90).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("2-3")));
        graph.edge(3, 4).set(carAvSpeedEnc, 9, 9).setDistance(10).setKeyValues(Map.of(STREET_NAME, new KValue("3-4")));
        graph.edge(5, 6).set(carAvSpeedEnc, 9, 9).setDistance(0.001).setKeyValues(Map.of(STREET_NAME, new KValue("3-4")));
        return graph;
    }

    private class RoundaboutGraph {
        final BaseGraph g;
        final NodeAccess na;
        final EdgeIteratorState edge3to6, edge3to9;
        boolean clockwise = false;
        List<EdgeIteratorState> roundaboutEdges = new LinkedList<>();

        private RoundaboutGraph() {
            g = new BaseGraph.Builder(mixedEncodingManager).create();
            na = g.getNodeAccess();
            //                                       18
            //      8                 14              |
            //       \                 |      / 16 - 17
            //         5              12 - 13          \-- 19
            //       /  \              |      \ 15
            //  1 - 2    4 - 7 - 10 - 11
            //       \  /
            //        3
            //        | \
            //        6 [ 9 ] edge 9 is turned off in default mode

            na.setNode(1, 52.514, 13.348);
            na.setNode(2, 52.514, 13.349);
            na.setNode(3, 52.5135, 13.35);
            na.setNode(4, 52.514, 13.351);
            na.setNode(5, 52.5145, 13.351);
            na.setNode(6, 52.513, 13.35);
            na.setNode(7, 52.514, 13.352);
            na.setNode(8, 52.515, 13.351);
            na.setNode(9, 52.513, 13.351);
            na.setNode(10, 52.514, 13.353);
            na.setNode(11, 52.514, 13.354);
            na.setNode(12, 52.515, 13.354);
            na.setNode(13, 52.515, 13.355);
            na.setNode(14, 52.516, 13.354);
            na.setNode(15, 52.516, 13.360);
            na.setNode(16, 52.514, 13.360);
            na.setNode(17, 52.514, 13.361);
            na.setNode(18, 52.513, 13.361);
            na.setNode(19, 52.515, 13.368);

            // roundabout
            roundaboutEdges.add(g.edge(3, 2).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("2-3"))));
            roundaboutEdges.add(g.edge(4, 3).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("3-4"))));
            roundaboutEdges.add(g.edge(5, 4).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("4-5"))));
            roundaboutEdges.add(g.edge(2, 5).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("5-2"))));

            List<EdgeIteratorState> bothDir = new ArrayList<>();
            List<EdgeIteratorState> oneDir = new ArrayList<>(roundaboutEdges);

            bothDir.add(g.edge(1, 2).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("MainStreet 1 2"))));
            bothDir.add(g.edge(4, 7).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("MainStreet 4 7"))));
            bothDir.add(g.edge(5, 8).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("5-8"))));

            bothDir.add(edge3to6 = g.edge(3, 6).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("3-6"))));
            oneDir.add(edge3to9 = g.edge(3, 9).setDistance(5).setKeyValues(Map.of(STREET_NAME, new KValue("3-9"))));

            bothDir.add(g.edge(7, 10).setDistance(5));
            bothDir.add(g.edge(10, 11).setDistance(5));
            bothDir.add(g.edge(11, 12).setDistance(5));
            bothDir.add(g.edge(12, 13).setDistance(5));
            bothDir.add(g.edge(12, 14).setDistance(5));
            bothDir.add(g.edge(13, 15).setDistance(5));
            bothDir.add(g.edge(13, 16).setDistance(5));
            bothDir.add(g.edge(16, 17).setDistance(5));
            bothDir.add(g.edge(17, 18).setDistance(5));
            bothDir.add(g.edge(17, 19).setDistance(5));

            for (EdgeIteratorState edge : bothDir) {
                edge.set(mixedCarAccessEnc, true, true);
                edge.set(mixedCarSpeedEnc, 70, 70);
                edge.set(mixedFootSpeedEnc, 7, 7);
            }
            for (EdgeIteratorState edge : oneDir) {
                edge.set(mixedCarAccessEnc, true);
                edge.set(mixedCarSpeedEnc, 70, 0);
                edge.set(mixedFootSpeedEnc, 7, 0);
            }
            setRoundabout(clockwise);
            inverse3to9();
        }

        public void setRoundabout(boolean clockwise) {
            BooleanEncodedValue mixedRoundabout = mixedEncodingManager.getBooleanEncodedValue(Roundabout.KEY);
            for (EdgeIteratorState edge : roundaboutEdges) {
                edge.set(mixedCarSpeedEnc, clockwise ? 70 : 0, clockwise ? 0 : 70);
                edge.set(mixedFootSpeedEnc, clockwise ? 7 : 0, clockwise ? 0 : 7);
                edge.set(mixedCarAccessEnc, clockwise, !clockwise);
                edge.set(mixedRoundabout, true);
            }
            this.clockwise = clockwise;
        }

        public void inverse3to9() {
            edge3to9.set(mixedCarAccessEnc, !edge3to9.get(mixedCarAccessEnc), false);
            edge3to9.set(mixedCarSpeedEnc, edge3to9.get(mixedCarSpeedEnc) > 0 ? 0 : 70, 0);
            edge3to9.set(mixedFootSpeedEnc, edge3to9.get(mixedFootSpeedEnc) > 0 ? 0 : 7, 0);
        }

        public void inverse3to6() {
            edge3to6.set(mixedCarAccessEnc, !edge3to6.get(mixedCarAccessEnc), true);
            edge3to6.set(mixedCarSpeedEnc, edge3to6.get(mixedCarSpeedEnc) > 0 ? 0 : 70, 70);
            edge3to6.set(mixedFootSpeedEnc, edge3to6.get(mixedFootSpeedEnc) > 0 ? 0 : 7, 7);
        }

        private double getAngle(int n1, int n2, int n3, int n4) {
            double inOrientation = AngleCalc.ANGLE_CALC.calcOrientation(na.getLat(n1), na.getLon(n1), na.getLat(n2), na.getLon(n2));
            double outOrientation = AngleCalc.ANGLE_CALC.calcOrientation(na.getLat(n3), na.getLon(n3), na.getLat(n4), na.getLon(n4));
            outOrientation = AngleCalc.ANGLE_CALC.alignOrientation(inOrientation, outOrientation);
            double delta = (inOrientation - outOrientation);
            delta = clockwise ? (Math.PI + delta) : -1 * (Math.PI - delta);
            return delta;
        }
    }

    private static Path extractPath(Graph graph, Weighting weighting, SPTEntry sptEntry) {
        return PathExtractor.extractPath(graph, weighting, sptEntry);
    }
}
