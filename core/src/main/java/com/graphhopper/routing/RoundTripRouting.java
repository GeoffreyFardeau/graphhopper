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
        /**
         * Only used for round trips with via points: the geometric skeleton (straight lines between the
         * generated points) is built with length distance * factor. Roads are longer than the skeleton,
         * so a factor below 1 compensates the overshoot of the resulting route.
         */
        final double viaSkeletonFactor;
        /**
         * Weight factor applied to edges that were already used by a previous leg of the round trip. The
         * default of upstream GraphHopper is 5, which is too weak for custom models where the priority of
         * unwanted roads is 0.1 (reusing a track then still beats taking a road), and leads to loops that
         * come back on themselves.
         */
        final double avoidEdgesFactor;

        public Params() {
            this(new PMap(), 0, 3);
        }

        public Params(PMap hints, double initialHeading, int maxRetries) {
            distanceInMeter = hints.getDouble(RoundTrip.DISTANCE, 10_000);
            seed = hints.getLong(RoundTrip.SEED, 0L);
            roundTripPointCount = Math.min(20, hints.getInt(RoundTrip.POINTS, 2 + (int) (distanceInMeter / 50000)));
            this.initialHeading = initialHeading;
            this.maxRetries = maxRetries;
            viaSkeletonFactor = hints.getDouble(RoundTrip.VIA_SKELETON_FACTOR, 0.8);
            avoidEdgesFactor = hints.getDouble(RoundTrip.AVOID_EDGES_FACTOR, 100);
        }

        public double getAvoidEdgesFactor() {
            return avoidEdgesFactor;
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
     * Round trip with enforced via points. The tour starts and ends at the first point and visits the
     * other points in the given order: A -> B(via) -> C(via) -> ... -> A.
     * <p>
     * The straight line polygon A-B-C-...-A defines the base shape of the loop. If the requested
     * distance is significantly larger than its perimeter, every leg is "inflated" outwards with a smooth
     * bulge so that the resulting closed skeleton has approximately the requested length. All bulges point
     * away from the polygon interior, so the skeleton is a simple (non self-intersecting) closed curve.
     * For a degenerate polygon (two points, or collinear points) the outbound and return legs bulge to
     * opposite sides which yields a lens shaped loop through the via points.
     */
    private static List<Snap> lookupMultipleViaPoints(List<GHPoint> viaPoints, EdgeFilter edgeFilter, LocationIndex locationIndex, Params params) {
        if (viaPoints.size() < 2)
            throw new IllegalArgumentException("At least 2 via points are required for multi-point round trip");

        List<Snap> viaSnaps = new ArrayList<>(viaPoints.size());
        List<GHPoint> snapped = new ArrayList<>(viaPoints.size());
        for (int i = 0; i < viaPoints.size(); i++) {
            GHPoint point = viaPoints.get(i);
            Snap snap = locationIndex.findClosest(point.lat, point.lon, edgeFilter);
            if (!snap.isValid())
                throw new PointNotFoundException("Cannot find via point " + i + ": " + point, i);
            viaSnaps.add(snap);
            snapped.add(snap.getSnappedPoint());
        }

        Random random = new Random(params.seed);
        List<List<GHPoint>> skeleton = createViaSkeleton(snapped, params.distanceInMeter * params.viaSkeletonFactor,
                params.roundTripPointCount, params.initialHeading, random);

        List<Snap> snaps = new ArrayList<>();
        snaps.add(viaSnaps.get(0));
        for (int leg = 0; leg < viaPoints.size(); leg++) {
            GHPoint from = snapped.get(leg);
            GHPoint to = snapped.get((leg + 1) % viaPoints.size());
            for (GHPoint generated : skeleton.get(leg)) {
                Snap snap = snapGeneratedPoint(generated, from, to, edgeFilter, locationIndex, Math.max(3, params.maxRetries));
                if (snap != null)
                    snaps.add(snap);
            }
            // the via point (or the start point again for the last leg)
            snaps.add(viaSnaps.get((leg + 1) % viaPoints.size()));
        }
        return snaps;
    }

    /**
     * Computes the geometric skeleton of a round trip through the given via points (in order, closed
     * back to the first point). Pure geometry, no graph access.
     * <p>
     * Model: the ideal loop is a circle whose circumference is the desired distance and which passes
     * through the via points. A circle centre is fitted to the via points, every leg is then interpolated
     * in polar coordinates around that centre (an arc) and blended between the straight chord and the arc
     * so that the closed polyline has the desired length. This gives one round loop with the via points on
     * its rim when the via points are close together, a lens when two points are far apart, and a rounded
     * polygon when the via points already span a large area.
     *
     * @param via             the via points, the first one is start and end of the loop
     * @param desiredDistance the desired length of the closed skeleton polyline in meter
     * @param pointBudget     the approximate number of intermediate points to distribute over the legs
     * @param initialHeading  requested departure heading (degrees, clockwise from north) or NaN
     * @param random          source of randomness used to vary the shape between seeds
     * @return for every leg i (from via i to via i+1, the last leg closes the loop) the list of generated
     * intermediate points, possibly empty
     */
    static List<List<GHPoint>> createViaSkeleton(List<GHPoint> via, double desiredDistance, int pointBudget,
                                                 double initialHeading, Random random) {
        int n = via.size();
        LocalPlane plane = new LocalPlane(via);
        double[][] xy = new double[n][];
        for (int i = 0; i < n; i++)
            xy[i] = plane.toXY(via.get(i));

        List<List<GHPoint>> empty = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            empty.add(new ArrayList<>());

        double perimeter = 0, signedArea2 = 0;
        double[] centroid = new double[2];
        for (int i = 0; i < n; i++) {
            double[] a = xy[i], b = xy[(i + 1) % n];
            perimeter += Math.hypot(b[0] - a[0], b[1] - a[1]);
            signedArea2 += a[0] * b[1] - b[0] * a[1];
            centroid[0] += a[0] / n;
            centroid[1] += a[1] / n;
        }
        // if the requested distance is not much longer than the polygon, routing through the via points
        // directly is already long enough (roads are always longer than the straight line)
        if (perimeter <= 0 || desiredDistance <= perimeter * DIRECT_ROUTING_FACTOR)
            return empty;

        // radius of the ideal circle, slightly varied per seed so that the shape changes with the seed
        double radius = desiredDistance / (2 * Math.PI) * (0.9 + 0.2 * random.nextDouble());
        double maxDistFromCentroid = 0;
        for (double[] p : xy)
            maxDistFromCentroid = Math.max(maxDistFromCentroid, Math.hypot(p[0] - centroid[0], p[1] - centroid[1]));
        boolean thin = Math.abs(signedArea2) < 0.02 * perimeter * perimeter;
        boolean small = maxDistFromCentroid < 0.6 * radius;

        int budget = Math.max(6, pointBudget);
        List<double[][][]> candidates = new ArrayList<>();
        if (!thin && !small) {
            // the via points span a large area: the loop winds around their centroid in the order given
            double[] centre = fitCircleCentre(xy, centroid, radius);
            candidates.add(polarSkeleton(xy, centre, signedArea2 > 0 ? 1 : -1, desiredDistance, budget));
        } else {
            // the via points are (almost) on a line or close together compared to the loop size: the loop
            // is a circle through them, its centre lies on one side of their main axis. Both sides and
            // both directions are candidates.
            int[] axis = farthestPair(xy);
            double[] a = xy[axis[0]], b = xy[axis[1]];
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double ux, uy;
            if (len > 1) {
                ux = (b[0] - a[0]) / len;
                uy = (b[1] - a[1]) / len;
            } else {
                // all points at the same place: axis from the requested heading, or north
                double h = Double.isNaN(initialHeading) ? 0 : Math.toRadians(initialHeading);
                ux = Math.sin(h);
                uy = Math.cos(h);
            }
            double offset = Math.max(1, Math.sqrt(Math.max(0, radius * radius - len * len / 4)));
            for (int side = -1; side <= 1; side += 2) {
                double[] start = {(a[0] + b[0]) / 2 + side * uy * offset, (a[1] + b[1]) / 2 - side * ux * offset};
                double[] centre = fitCircleCentre(xy, start, radius);
                for (int dir = -1; dir <= 1; dir += 2)
                    candidates.add(polarSkeleton(xy, centre, dir, desiredDistance, budget));
            }
        }

        double[][][] best;
        if (candidates.size() == 1 || Double.isNaN(initialHeading)) {
            best = candidates.get(random.nextInt(candidates.size()));
        } else {
            best = null;
            double bestDiff = Double.MAX_VALUE;
            for (double[][][] cand : candidates) {
                double[] first = cand[0].length > 0 ? cand[0][0] : xy[1];
                double heading = Math.toDegrees(Math.atan2(first[0] - xy[0][0], first[1] - xy[0][1]));
                double diff = Math.abs(((heading - initialHeading) % 360 + 540) % 360 - 180);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = cand;
                }
            }
        }

        List<List<GHPoint>> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<GHPoint> leg = new ArrayList<>(best[i].length);
            for (double[] p : best[i])
                leg.add(plane.toPoint(p[0], p[1]));
            result.add(leg);
        }
        return result;
    }

    /**
     * If the requested distance is below perimeter * factor no intermediate points are generated.
     */
    private static final double DIRECT_ROUTING_FACTOR = 1.3;
    /**
     * Legs whose arc is shorter than chord * factor get no intermediate point (routed directly).
     */
    private static final double MIN_LEG_INFLATION = 1.15;
    private static final int MAX_POINTS_PER_LEG = 5;

    private static int[] farthestPair(double[][] xy) {
        int[] best = {0, 1 % xy.length};
        double bestDist = -1;
        for (int i = 0; i < xy.length; i++)
            for (int j = i + 1; j < xy.length; j++) {
                double d = Math.hypot(xy[j][0] - xy[i][0], xy[j][1] - xy[i][1]);
                if (d > bestDist) {
                    bestDist = d;
                    best = new int[]{i, j};
                }
            }
        return best;
    }

    /**
     * Least squares fit of the centre of a circle with fixed radius through the given points, starting
     * from the given initial centre (fixed point iteration of the normal equations).
     */
    static double[] fitCircleCentre(double[][] xy, double[] start, double radius) {
        double cx = start[0], cy = start[1];
        for (int iter = 0; iter < 30; iter++) {
            double sx = 0, sy = 0;
            for (double[] p : xy) {
                double dx = p[0] - cx, dy = p[1] - cy;
                double d = Math.hypot(dx, dy);
                if (d < 1e-9) {
                    sx += p[0];
                    sy += p[1];
                } else {
                    sx += p[0] - radius * dx / d;
                    sy += p[1] - radius * dy / d;
                }
            }
            double nx = sx / xy.length, ny = sy / xy.length;
            double move = Math.hypot(nx - cx, ny - cy);
            cx = nx;
            cy = ny;
            if (move < 0.5)
                break;
        }
        return new double[]{cx, cy};
    }

    /**
     * Builds the skeleton for a given circle centre and winding direction.
     *
     * @param dir +1 = counter clockwise (mathematical angle increases along the loop), -1 = clockwise
     * @return for every leg the generated intermediate points (planar coordinates)
     */
    static double[][][] polarSkeleton(double[][] xy, double[] centre, int dir, double desiredDistance, int budget) {
        int n = xy.length;
        double[] theta = new double[n], rho = new double[n], chord = new double[n];
        for (int i = 0; i < n; i++) {
            theta[i] = Math.atan2(xy[i][1] - centre[1], xy[i][0] - centre[0]);
            rho[i] = Math.hypot(xy[i][0] - centre[0], xy[i][1] - centre[1]);
        }
        // angular sweep of every leg, always forward in the winding direction
        double[] sweep = new double[n];
        double total = 0;
        int largest = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            chord[i] = Math.hypot(xy[j][0] - xy[i][0], xy[j][1] - xy[i][1]);
            double raw = dir * (theta[j] - theta[i]);
            sweep[i] = ((raw % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
            if (chord[i] < 1)
                sweep[i] = 0;
            total += sweep[i];
            if (sweep[i] > sweep[largest])
                largest = i;
        }
        // the via points are not visited in angular order (non convex polygon, or a zig-zag order):
        // keep the largest arc as the "big loop" and let the other backward legs go the short way
        if (total > 2 * Math.PI + 1e-6)
            for (int i = 0; i < n; i++)
                if (i != largest && sweep[i] > Math.PI)
                    sweep[i] -= 2 * Math.PI;

        // number of intermediate points per leg, proportional to the arc length of the leg
        double[] arcLen = new double[n];
        double arcTotal = 0;
        for (int i = 0; i < n; i++) {
            arcLen[i] = polylineLength(xy, centre, theta, rho, sweep, dir, i, 1.0, 16);
            arcTotal += arcLen[i];
        }
        int[] points = new int[n];
        for (int i = 0; i < n; i++) {
            points[i] = (int) Math.min(MAX_POINTS_PER_LEG, Math.round(budget * arcLen[i] / arcTotal));
            if (arcLen[i] > chord[i] * MIN_LEG_INFLATION && points[i] == 0)
                points[i] = 1;
        }

        // blend factor between chord (0) and arc (1), bisection on the length of the resulting polyline
        double lo = 0, hi = 8;
        for (int iter = 0; iter < 60 && hi - lo > 1e-4; iter++) {
            double mid = (lo + hi) / 2;
            double len = 0;
            for (int i = 0; i < n; i++)
                len += polylineLength(xy, centre, theta, rho, sweep, dir, i, mid, points[i]);
            if (len < desiredDistance)
                lo = mid;
            else
                hi = mid;
        }
        double blend = (lo + hi) / 2;

        double[][][] result = new double[n][][];
        for (int i = 0; i < n; i++) {
            result[i] = new double[points[i]][];
            for (int j = 1; j <= points[i]; j++)
                result[i][j - 1] = legPoint(xy, centre, theta, rho, sweep, dir, i, blend, (double) j / (points[i] + 1));
        }
        return result;
    }

    /**
     * Point of leg i at parameter t: the straight chord point blended with the polar arc point.
     */
    private static double[] legPoint(double[][] xy, double[] centre, double[] theta, double[] rho, double[] sweep,
                                     int dir, int i, double blend, double t) {
        int j = (i + 1) % xy.length;
        double cx = xy[i][0] + (xy[j][0] - xy[i][0]) * t;
        double cy = xy[i][1] + (xy[j][1] - xy[i][1]) * t;
        double ang = theta[i] + dir * sweep[i] * t;
        double r = rho[i] + (rho[j] - rho[i]) * t;
        double ax = centre[0] + r * Math.cos(ang);
        double ay = centre[1] + r * Math.sin(ang);
        return new double[]{cx + blend * (ax - cx), cy + blend * (ay - cy)};
    }

    /**
     * Length of the polyline of leg i through the given number of intermediate points.
     */
    private static double polylineLength(double[][] xy, double[] centre, double[] theta, double[] rho, double[] sweep,
                                         int dir, int i, double blend, int points) {
        double len = 0;
        double[] prev = xy[i];
        for (int j = 1; j <= points + 1; j++) {
            double[] p = j == points + 1 ? xy[(i + 1) % xy.length] : legPoint(xy, centre, theta, rho, sweep, dir, i, blend, (double) j / (points + 1));
            len += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
            prev = p;
        }
        return len;
    }

    /**
     * Snaps a generated point to the graph. If there is no road nearby (sea, border of the map, ...) the
     * point is moved step by step back towards the straight line between the leg ends. Returns null if no
     * valid snap was found.
     */
    private static Snap snapGeneratedPoint(GHPoint generated, GHPoint from, GHPoint to, EdgeFilter edgeFilter,
                                           LocationIndex locationIndex, int maxRetries) {
        double lat = generated.lat, lon = generated.lon;
        // projection of the generated point onto the chord (in degrees, good enough for a fallback)
        double dLat = to.lat - from.lat, dLon = to.lon - from.lon;
        double len2 = dLat * dLat + dLon * dLon;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((lat - from.lat) * dLat + (lon - from.lon) * dLon) / len2));
        double baseLat = from.lat + t * dLat, baseLon = from.lon + t * dLon;
        for (int tryCount = 0; tryCount < maxRetries; tryCount++) {
            Snap snap = locationIndex.findClosest(lat, lon, edgeFilter);
            if (snap.isValid())
                return snap;
            lat = baseLat + (lat - baseLat) * 0.6;
            lon = baseLon + (lon - baseLon) * 0.6;
        }
        return null;
    }

    /**
     * Equirectangular projection around the centroid of the given points, in meter. Accurate enough for
     * the extent of a round trip.
     */
    private static final class LocalPlane {
        private static final double R = DistanceCalcEarth.R;
        private final double lat0, lon0, cosLat0;

        LocalPlane(List<GHPoint> points) {
            double latSum = 0, lonSum = 0;
            for (GHPoint p : points) {
                latSum += p.lat;
                lonSum += p.lon;
            }
            lat0 = latSum / points.size();
            lon0 = lonSum / points.size();
            cosLat0 = Math.cos(Math.toRadians(lat0));
        }

        double[] toXY(GHPoint p) {
            return new double[]{
                    Math.toRadians(p.lon - lon0) * cosLat0 * R,
                    Math.toRadians(p.lat - lat0) * R};
        }

        GHPoint toPoint(double x, double y) {
            return new GHPoint(lat0 + Math.toDegrees(y / R), lon0 + Math.toDegrees(x / (R * cosLat0)));
        }
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
        return calcPaths(snaps, pathCalculator, 5);
    }

    public static Result calcPaths(List<Snap> snaps, FlexiblePathCalculator pathCalculator, double avoidEdgesFactor) {
        RoundTripCalculator roundTripCalculator = new RoundTripCalculator(pathCalculator, avoidEdgesFactor);
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

        RoundTripCalculator(FlexiblePathCalculator pathCalculator, double avoidEdgesFactor) {
            this.pathCalculator = pathCalculator;
            // we make the path calculator use our avoid edges weighting
            AvoidEdgesWeighting avoidPreviousPathsWeighting = new AvoidEdgesWeighting(pathCalculator.getWeighting())
                    .setEdgePenaltyFactor(avoidEdgesFactor);
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
