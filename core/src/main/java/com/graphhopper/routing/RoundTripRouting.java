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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.tour.MultiPointTour;
import com.graphhopper.routing.util.tour.TourStrategy;
import com.graphhopper.routing.weighting.AvoidEdgesWeighting;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.AngleCalc;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters.Algorithms.RoundTrip;
import com.graphhopper.util.PointList;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


/**
 * Implementation of calculating a route with one or more round trip (route with identical start and
 * end).
 *
 * @author Peter Karich
 */
public class RoundTripRouting {

    public static class Params {
        final double distanceInMeter;
        final long seed;
        final double initialHeading;
        final int roundTripPointCount;
        final int maxRetries;

        public Params() {
            this(new PMap(), 0, 3);
        }

        public Params(PMap hints, double initialHeading, int maxRetries) {
            distanceInMeter = hints.getDouble(RoundTrip.DISTANCE, 10_000);
            seed = hints.getLong(RoundTrip.SEED, 0L);
            roundTripPointCount = Math.min(20, hints.getInt(RoundTrip.POINTS, 2 + (int) (distanceInMeter / 50000)));
            this.initialHeading = initialHeading;
            this.maxRetries = maxRetries;
        }
    }

    public static List<Snap> lookup(List<GHPoint> points, EdgeFilter edgeFilter, LocationIndex locationIndex, Params params) {
        // todo: no snap preventions for round trip so far
        if (points.isEmpty())
            throw new IllegalArgumentException("At least one point is required for round trip calculation");

        // Single point: use the original algorithm with generated via points
        if (points.size() == 1) {
            return lookupSinglePoint(points.get(0), edgeFilter, locationIndex, params);
        }

        // Multiple points: use them as enforced via points and generate additional points between them
        return lookupMultipleViaPoints(points, edgeFilter, locationIndex, params);
    }

    /**
     * Original round trip algorithm with a single start point and automatically generated via points
     */
    private static List<Snap> lookupSinglePoint(GHPoint start, EdgeFilter edgeFilter, LocationIndex locationIndex, Params params) {
        TourStrategy strategy = new MultiPointTour(new Random(params.seed), params.distanceInMeter, params.roundTripPointCount, params.initialHeading);
        List<Snap> snaps = new ArrayList<>(2 + strategy.getNumberOfGeneratedPoints());
        Snap startSnap = locationIndex.findClosest(start.lat, start.lon, edgeFilter);
        if (!startSnap.isValid())
            throw new PointNotFoundException("Cannot find point 0: " + start, 0);

        snaps.add(startSnap);

        GHPoint last = start;
        for (int i = 0; i < strategy.getNumberOfGeneratedPoints(); i++) {
            double heading = strategy.getHeadingForIteration(i);
            Snap result = generateValidPoint(last, strategy.getDistanceForIteration(i), heading, edgeFilter, locationIndex, params.maxRetries);
            last = result.getSnappedPoint();
            snaps.add(result);
        }

        snaps.add(startSnap);
        return snaps;
    }

    /**
     * Enhanced round trip algorithm with enforced via points.
     * Generates additional points between the via points to create a more interesting route.
     * The tour starts and ends at the first point: A -> B(via) -> C(via) -> ... -> A
     */
    private static List<Snap> lookupMultipleViaPoints(List<GHPoint> viaPoints, EdgeFilter edgeFilter, LocationIndex locationIndex, Params params) {
        if (viaPoints.size() < 2)
            throw new IllegalArgumentException("At least 2 via points are required for multi-point round trip");

        final GHPoint start = viaPoints.get(0);
        List<Snap> snaps = new ArrayList<>();
        
        // Snap all via points first and validate them
        List<Snap> viaSnaps = new ArrayList<>(viaPoints.size());
        for (int i = 0; i < viaPoints.size(); i++) {
            GHPoint point = viaPoints.get(i);
            Snap snap = locationIndex.findClosest(point.lat, point.lon, edgeFilter);
            if (!snap.isValid())
                throw new PointNotFoundException("Cannot find via point " + i + ": " + point, i);
            viaSnaps.add(snap);
        }

        // Calculate total distance between all via points to determine how many intermediate points to generate
        double totalViaDistance = 0;
        for (int i = 0; i < viaSnaps.size() - 1; i++) {
            GHPoint p1 = viaSnaps.get(i).getSnappedPoint();
            GHPoint p2 = viaSnaps.get(i + 1).getSnappedPoint();
            totalViaDistance += DistanceCalcEarth.DIST_EARTH.calcDist(p1.getLat(), p1.getLon(), p2.getLat(), p2.getLon());
        }
        // Distance from last via point back to start
        GHPoint lastPoint = viaSnaps.get(viaSnaps.size() - 1).getSnappedPoint();
        totalViaDistance += DistanceCalcEarth.DIST_EARTH.calcDist(lastPoint.getLat(), lastPoint.getLon(), start.getLat(), start.getLon());

        // Simple approach: if distance is too short, just go directly through waypoints without detours
        // This avoids creating messy overlapping routes
        double desiredDistance = params.distanceInMeter;
        boolean addDetours = desiredDistance > totalViaDistance * 1.3; // Only add detours if we need 30% more distance
        
        Random random = new Random(params.seed);
        
        // Add start point
        snaps.add(viaSnaps.get(0));
        
        if (!addDetours) {
            // Simple mode: just connect waypoints directly
            for (int i = 1; i < viaSnaps.size(); i++) {
                snaps.add(viaSnaps.get(i));
            }
        } else {
            // Advanced mode: add intermediate points using circular distribution like MultiPointTour
            // Calculate how many total points we need (including via points)
            int totalPoints = params.roundTripPointCount + viaPoints.size();
            double distancePerPoint = desiredDistance / (totalPoints + 1);
            
            // For each segment, add intermediate points with circular angle distribution
            int globalPointIndex = 0;
            for (int segmentIdx = 0; segmentIdx < viaPoints.size(); segmentIdx++) {
                GHPoint fromPoint = viaSnaps.get(segmentIdx).getSnappedPoint();
                GHPoint toPoint = segmentIdx < viaPoints.size() - 1 
                    ? viaSnaps.get(segmentIdx + 1).getSnappedPoint() 
                    : viaSnaps.get(0).getSnappedPoint(); // Last segment returns to start
                
                // Calculate base heading and distance for this segment
                double baseHeading = AngleCalc.ANGLE_CALC.calcAzimuth(
                    fromPoint.getLat(), fromPoint.getLon(),
                    toPoint.getLat(), toPoint.getLon()
                );
                double segmentDistance = DistanceCalcEarth.DIST_EARTH.calcDist(
                    fromPoint.getLat(), fromPoint.getLon(),
                    toPoint.getLat(), toPoint.getLon()
                );
                
                // Determine how many intermediate points for this segment (proportional to segment length)
                int pointsForSegment = Math.max(1, (int) ((segmentDistance / totalViaDistance) * (totalPoints - viaPoints.size())));
                
                // Generate intermediate points with alternating side deviations to create a smooth loop
                GHPoint lastGenerated = fromPoint;
                for (int i = 0; i < pointsForSegment; i++) {
                    // Alternate between left and right side of the direct path (like a sine wave)
                    // This creates a smoother, more predictable loop
                    double progressRatio = (i + 1.0) / (pointsForSegment + 1.0);
                    double deviationAngle = Math.sin(progressRatio * Math.PI) * 45 * (globalPointIndex % 2 == 0 ? 1 : -1);
                    double intermediateHeading = baseHeading + deviationAngle;
                    
                    // Distance slightly randomized for natural variation
                    double pointDistance = distancePerPoint * (0.8 + random.nextDouble() * 0.4);
                    
                    try {
                        Snap intermediateSnap = generateValidPoint(
                            lastGenerated, 
                            pointDistance, 
                            intermediateHeading, 
                            edgeFilter, 
                            locationIndex, 
                            params.maxRetries
                        );
                        snaps.add(intermediateSnap);
                        lastGenerated = intermediateSnap.getSnappedPoint();
                        globalPointIndex++;
                    } catch (IllegalArgumentException e) {
                        // If we can't find a valid intermediate point, skip it
                    }
                }
                
                // Add the next via point
                if (segmentIdx < viaPoints.size() - 1) {
                    snaps.add(viaSnaps.get(segmentIdx + 1));
                }
            }
        }
        
        // Add start point again to complete the round trip
        snaps.add(viaSnaps.get(0));
        
        return snaps;
    }

    private static Snap generateValidPoint(GHPoint lastPoint, double distanceInMeters, double heading, EdgeFilter edgeFilter, LocationIndex locationIndex, int maxRetries) {
        int tryCount = 0;
        while (true) {
            GHPoint generatedPoint = DistanceCalcEarth.DIST_EARTH.projectCoordinate(lastPoint.getLat(), lastPoint.getLon(), distanceInMeters, heading);
            Snap snap = locationIndex.findClosest(generatedPoint.getLat(), generatedPoint.getLon(), edgeFilter);
            if (snap.isValid())
                return snap;

            tryCount++;
            distanceInMeters *= 0.95;

            if (tryCount >= maxRetries)
                throw new IllegalArgumentException("Could not find a valid point after " + maxRetries + " tries, for the point:" + lastPoint);
        }
    }

    public static Result calcPaths(List<Snap> snaps, FlexiblePathCalculator pathCalculator) {
        RoundTripCalculator roundTripCalculator = new RoundTripCalculator(pathCalculator);
        Result result = new Result(snaps.size() - 1);
        Snap start = snaps.get(0);
        for (int snapIndex = 1; snapIndex < snaps.size(); snapIndex++) {
            // instead getClosestNode (which might be a virtual one and introducing unnecessary tails of the route)
            // use next tower node -> getBaseNode or getAdjNode
            // Later: remove potential route tail, maybe we can just enforce the heading at the start and when coming
            // back, and for tower nodes it does not matter anyway
            Snap startSnap = snaps.get(snapIndex - 1);
            int startNode = (startSnap == start) ? startSnap.getClosestNode() : startSnap.getClosestEdge().getBaseNode();
            Snap endSnap = snaps.get(snapIndex);
            int endNode = (endSnap == start) ? endSnap.getClosestNode() : endSnap.getClosestEdge().getBaseNode();

            Path path = roundTripCalculator.calcPath(startNode, endNode);
            if (snapIndex == 1) {
                result.wayPoints = new PointList(snaps.size(), path.graph.getNodeAccess().is3D());
                result.wayPoints.add(path.graph.getNodeAccess(), startNode);
            }
            result.wayPoints.add(path.graph.getNodeAccess(), endNode);
            result.visitedNodes += pathCalculator.getVisitedNodes();
            result.paths.add(path);
        }

        return result;
    }

    public static class Result {
        public List<Path> paths;
        public PointList wayPoints;
        public long visitedNodes;

        Result(int legs) {
            paths = new ArrayList<>(legs);
        }
    }

    /**
     * Calculates paths and avoids edges of previous path calculations
     */
    private static class RoundTripCalculator {
        private final FlexiblePathCalculator pathCalculator;
        private final IntSet previousEdges = new IntHashSet();

        RoundTripCalculator(FlexiblePathCalculator pathCalculator) {
            this.pathCalculator = pathCalculator;
            // we make the path calculator use our avoid edges weighting
            AvoidEdgesWeighting avoidPreviousPathsWeighting = new AvoidEdgesWeighting(pathCalculator.getWeighting())
                    .setEdgePenaltyFactor(5);
            avoidPreviousPathsWeighting.setAvoidedEdges(previousEdges);
            pathCalculator.setWeighting(avoidPreviousPathsWeighting);
        }

        Path calcPath(int from, int to) {
            Path path = pathCalculator.calcPaths(from, to, new EdgeRestrictions()).get(0);
            // add the edges of this path to the set of previous edges so they will be avoided from now, otherwise
            // we do not get a nice 'round trip'. note that for this reason we cannot use CH for round-trips currently
            for (IntCursor c : path.getEdges()) {
                previousEdges.add(c.value);
            }
            return path;
        }

    }
}
