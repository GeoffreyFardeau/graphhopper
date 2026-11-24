package com.graphhopper.routing;

import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.shapes.GHPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure geometry tests of the skeleton generated for round trips with via points.
 */
public class RoundTripViaSkeletonTest {
    private static final DistanceCalcEarth DIST = DistanceCalcEarth.DIST_EARTH;
    private final GHPoint start = new GHPoint(48.85, 2.35);

    @Test
    public void twoClosePoints_oneRoundLoop() {
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 4000, 60);
        List<GHPoint> viaPoints = Arrays.asList(start, via);
        double desired = 30_000;
        for (int seed = 1; seed <= 5; seed++) {
            List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(viaPoints, desired, 2, Double.NaN, new Random(seed));
            assertEquals(2, skeleton.size());
            List<GHPoint> loop = closedPolyline(viaPoints, skeleton);
            assertTrue(loop.size() >= 5, "expected several generated points, got " + loop);
            assertEquals(desired, length(loop), desired * 0.03, "skeleton length must match the requested distance");
            assertNoSelfIntersection(loop);
            assertTrue(compactness(loop) > 0.6, "loop must be round, compactness was " + compactness(loop));
        }
    }

    @Test
    public void twoDistantPoints_lens() {
        // 10 km apart, 30 km loop: a circle of 30 km circumference cannot pass through both points,
        // the loop becomes a lens with the two points at its ends
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 10_000, 60);
        List<GHPoint> viaPoints = Arrays.asList(start, via);
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(viaPoints, 30_000, 2, Double.NaN, new Random(1));
        List<GHPoint> loop = closedPolyline(viaPoints, skeleton);
        assertEquals(30_000, length(loop), 30_000 * 0.03);
        assertNoSelfIntersection(loop);
        assertFalse(skeleton.get(0).isEmpty());
        assertFalse(skeleton.get(1).isEmpty());
        double sideOut = side(start, via, skeleton.get(0).get(0));
        double sideBack = side(start, via, skeleton.get(1).get(0));
        assertTrue(sideOut * sideBack < 0, "legs must bulge to opposite sides");
        assertTrue(compactness(loop) > 0.5, "compactness was " + compactness(loop));
    }

    @Test
    public void clusterOfThreePoints_oneRoundLoop() {
        GHPoint b = DIST.projectCoordinate(start.lat, start.lon, 1500, 90);
        GHPoint c = DIST.projectCoordinate(start.lat, start.lon, 1200, 200);
        List<GHPoint> viaPoints = Arrays.asList(start, b, c);
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, Double.NaN, new Random(3));
        List<GHPoint> loop = closedPolyline(viaPoints, skeleton);
        assertEquals(40_000, length(loop), 40_000 * 0.03);
        assertNoSelfIntersection(loop);
        assertTrue(compactness(loop) > 0.6, "compactness was " + compactness(loop));
    }

    @Test
    public void largeTriangle_bulgesOutwards_bothOrientations() {
        GHPoint b = DIST.projectCoordinate(start.lat, start.lon, 20_000, 90);
        GHPoint c = DIST.projectCoordinate(start.lat, start.lon, 20_000, 30);
        checkOutwards(Arrays.asList(start, b, c));  // counter clockwise
        checkOutwards(Arrays.asList(start, c, b));  // clockwise
    }

    private void checkOutwards(List<GHPoint> viaPoints) {
        double desired = 90_000;
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(viaPoints, desired, 4, Double.NaN, new Random(7));
        List<GHPoint> loop = closedPolyline(viaPoints, skeleton);
        assertEquals(desired, length(loop), desired * 0.03);
        assertNoSelfIntersection(loop);
        assertTrue(compactness(loop) > 0.6, "compactness was " + compactness(loop));

        double cLat = 0, cLon = 0;
        for (GHPoint p : viaPoints) {
            cLat += p.lat / viaPoints.size();
            cLon += p.lon / viaPoints.size();
        }
        for (int i = 0; i < viaPoints.size(); i++) {
            GHPoint from = viaPoints.get(i), to = viaPoints.get((i + 1) % viaPoints.size());
            assertFalse(skeleton.get(i).isEmpty(), "leg " + i + " must be inflated");
            double midDist = DIST.calcDist(cLat, cLon, (from.lat + to.lat) / 2, (from.lon + to.lon) / 2);
            for (GHPoint p : skeleton.get(i)) {
                double dist = DIST.calcDist(cLat, cLon, p.lat, p.lon);
                assertTrue(dist > midDist, "generated point of leg " + i + " must be outside the polygon");
            }
        }
    }

    @Test
    public void zigZagOrder_staysSimpleEnough() {
        // four points visited in a criss-cross order: the loop must still close with the right length
        GHPoint b = DIST.projectCoordinate(start.lat, start.lon, 3000, 90);
        GHPoint c = DIST.projectCoordinate(start.lat, start.lon, 3000, 180);
        GHPoint d = DIST.projectCoordinate(start.lat, start.lon, 3000, 0);
        List<GHPoint> viaPoints = Arrays.asList(start, b, d, c);
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(viaPoints, 50_000, 2, Double.NaN, new Random(1));
        List<GHPoint> loop = closedPolyline(viaPoints, skeleton);
        assertEquals(50_000, length(loop), 50_000 * 0.05);
    }

    @Test
    public void noDetoursIfDistanceCloseToPerimeter() {
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 10_000, 45);
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(Arrays.asList(start, via), 25_000, 2, Double.NaN, new Random(1));
        assertTrue(skeleton.get(0).isEmpty());
        assertTrue(skeleton.get(1).isEmpty());
    }

    @Test
    public void headingSelectsSideOfTheLoop() {
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 5000, 0); // due north
        List<GHPoint> viaPoints = Arrays.asList(start, via);
        List<GHPoint> east = closedPolyline(viaPoints, RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, 90, new Random(1)));
        List<GHPoint> west = closedPolyline(viaPoints, RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, 270, new Random(1)));
        assertTrue(east.get(1).lon > start.lon, "heading east: first point must be east of the start");
        assertTrue(west.get(1).lon < start.lon, "heading west: first point must be west of the start");
    }

    @Test
    public void differentSeedsGiveDifferentLoops() {
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 5000, 45);
        List<GHPoint> viaPoints = Arrays.asList(start, via);
        GHPoint p1 = closedPolyline(viaPoints, RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, Double.NaN, new Random(1))).get(1);
        GHPoint p2 = closedPolyline(viaPoints, RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, Double.NaN, new Random(2))).get(1);
        GHPoint p3 = closedPolyline(viaPoints, RoundTripRouting.createViaSkeleton(viaPoints, 40_000, 2, Double.NaN, new Random(3))).get(1);
        assertTrue(DIST.calcDist(p1.lat, p1.lon, p2.lat, p2.lon) > 100 || DIST.calcDist(p1.lat, p1.lon, p3.lat, p3.lon) > 100);
    }

    @Test
    public void longLoop_hasManyPoints() {
        GHPoint via = DIST.projectCoordinate(start.lat, start.lon, 30_000, 45);
        List<List<GHPoint>> skeleton = RoundTripRouting.createViaSkeleton(Arrays.asList(start, via), 150_000, 6, Double.NaN, new Random(1));
        List<GHPoint> loop = closedPolyline(Arrays.asList(start, via), skeleton);
        assertTrue(loop.size() >= 8, "got " + loop.size());
        assertEquals(150_000, length(loop), 150_000 * 0.03);
        assertNoSelfIntersection(loop);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static List<GHPoint> closedPolyline(List<GHPoint> via, List<List<GHPoint>> skeleton) {
        List<GHPoint> loop = new ArrayList<>();
        loop.add(via.get(0));
        for (int i = 0; i < via.size(); i++) {
            loop.addAll(skeleton.get(i));
            loop.add(via.get((i + 1) % via.size()));
        }
        return loop;
    }

    private static double length(List<GHPoint> line) {
        double d = 0;
        for (int i = 1; i < line.size(); i++)
            d += DIST.calcDist(line.get(i - 1).lat, line.get(i - 1).lon, line.get(i).lat, line.get(i).lon);
        return d;
    }

    private static double[] xy(GHPoint origin, GHPoint p) {
        double cos = Math.cos(Math.toRadians(origin.lat));
        return new double[]{(p.lon - origin.lon) * cos * 111_000, (p.lat - origin.lat) * 111_000};
    }

    /**
     * isoperimetric quotient of the closed polyline: 1 for a circle, 0 for a line
     */
    private static double compactness(List<GHPoint> loop) {
        double area = 0, per = 0;
        for (int i = 1; i < loop.size(); i++) {
            double[] a = xy(loop.get(0), loop.get(i - 1)), b = xy(loop.get(0), loop.get(i));
            area += a[0] * b[1] - b[0] * a[1];
            per += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return 4 * Math.PI * Math.abs(area) / 2 / (per * per);
    }

    /**
     * signed distance (roughly, in meter) of p from the line a-b, positive on the right of a->b
     */
    private static double side(GHPoint a, GHPoint b, GHPoint p) {
        double[] bb = xy(a, b), pp = xy(a, p);
        double cross = bb[0] * pp[1] - bb[1] * pp[0];
        return -cross / Math.hypot(bb[0], bb[1]);
    }

    private static void assertNoSelfIntersection(List<GHPoint> loop) {
        int n = loop.size() - 1; // number of segments
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue; // first and last segment share the start point
                assertFalse(intersects(loop.get(i), loop.get(i + 1), loop.get(j), loop.get(j + 1)),
                        "segments " + i + " and " + j + " intersect");
            }
        }
    }

    private static boolean intersects(GHPoint p1, GHPoint p2, GHPoint p3, GHPoint p4) {
        double d1 = orient(p3, p4, p1), d2 = orient(p3, p4, p2), d3 = orient(p1, p2, p3), d4 = orient(p1, p2, p4);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double orient(GHPoint a, GHPoint b, GHPoint c) {
        return (b.lon - a.lon) * (c.lat - a.lat) - (b.lat - a.lat) * (c.lon - a.lon);
    }
}
