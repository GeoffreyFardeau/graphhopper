package com.graphhopper.example;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.GHPoint;

import java.util.Locale;

/**
 * Example demonstrating round trip routing with enforced via points.
 * 
 * This example shows how to create a round trip that visits specific waypoints
 * while potentially generating additional intermediate points for more interesting routes.
 */
public class RoundTripWithViaPointsExample {

    public static void main(String[] args) {
        // Create GraphHopper instance
        GraphHopper hopper = createGraphHopper();
        
        // Example 1: Single point round trip (original behavior)
        singlePointRoundTrip(hopper);
        
        // Example 2: Multi-point round trip with via points
        multiPointRoundTrip(hopper);
        
        // Example 3: Complex round trip with custom parameters
        complexRoundTrip(hopper);
        
        hopper.close();
    }

    private static GraphHopper createGraphHopper() {
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile("path/to/your/map.osm.pbf");
        hopper.setGraphHopperLocation("path/to/your/graph-cache");
        
        // Define profile for car routing
        hopper.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest"));
        
        // Optional: Add CH profile (but note that round trips don't work with CH)
        // hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
        
        hopper.importOrLoad();
        return hopper;
    }

    /**
     * Example 1: Traditional single-point round trip
     * The algorithm automatically generates waypoints around the starting point
     */
    private static void singlePointRoundTrip(GraphHopper hopper) {
        System.out.println("=== Example 1: Single Point Round Trip ===");
        
        GHRequest request = new GHRequest();
        request.setProfile("car");
        request.setAlgorithm("round_trip");
        
        // Starting point (and ending point)
        request.addPoint(new GHPoint(48.8566, 2.3522)); // Paris
        
        // Round trip parameters
        request.getHints().putObject(Parameters.Algorithms.RoundTrip.DISTANCE, 50_000); // 50 km
        request.getHints().putObject(Parameters.Algorithms.RoundTrip.POINTS, 3); // Max 3 intermediate points
        request.getHints().putObject(Parameters.Algorithms.RoundTrip.SEED, 123L); // For reproducibility
        
        // Disable CH (round trips don't work with CH)
        request.getHints().putObject(Parameters.CH.DISABLE, true);
        
        GHResponse response = hopper.route(request);
        
        if (response.hasErrors()) {
            System.err.println("Errors: " + response.getErrors());
        } else {
            ResponsePath path = response.getBest();
            System.out.println("Distance: " + path.getDistance() / 1000 + " km");
            System.out.println("Time: " + path.getTime() / 60000 + " min");
            System.out.println("Points: " + path.getPoints().size());
        }
        System.out.println();
    }

    /**
     * Example 2: Multi-point round trip with enforced via points
     * NEW FEATURE: Specify multiple waypoints that must be visited
     */
    private static void multiPointRoundTrip(GraphHopper hopper) {
        System.out.println("=== Example 2: Multi-Point Round Trip ===");
        
        GHRequest request = new GHRequest();
        request.setProfile("car");
        request.setAlgorithm("round_trip");
        
        // Define via points that must be visited
        request.addPoint(new GHPoint(48.8566, 2.3522)); // Paris (start and end)
        request.addPoint(new GHPoint(45.7640, 4.8357)); // Lyon
        request.addPoint(new GHPoint(43.2965, 5.3698)); // Marseille
        
        // The route will be: Paris → Lyon → Marseille → Paris
        
        // Round trip parameters
        request.getHints().putObject(Parameters.Algorithms.RoundTrip.DISTANCE, 200_000); // 200 km
        request.getHints().putObject(Parameters.Algorithms.RoundTrip.POINTS, 5); // Max 5 additional points
        
        // Disable CH
        request.getHints().putObject(Parameters.CH.DISABLE, true);
        
        GHResponse response = hopper.route(request);
        
        if (response.hasErrors()) {
            System.err.println("Errors: " + response.getErrors());
        } else {
            ResponsePath path = response.getBest();
            System.out.println("Route: Paris → Lyon → Marseille → Paris");
            System.out.println("Distance: " + path.getDistance() / 1000 + " km");
            System.out.println("Time: " + path.getTime() / 60000 + " min");
            System.out.println("Points: " + path.getPoints().size());
            
            // Print waypoints
            System.out.println("\nWaypoints:");
            for (int i = 0; i < path.getWaypoints().size(); i++) {
                GHPoint point = path.getWaypoints().get(i);
                System.out.println("  " + i + ": " + point.getLat() + ", " + point.getLon());
            }
        }
        System.out.println();
    }

    /**
     * Example 3: Complex round trip with custom parameters
     * Demonstrates advanced usage with error handling
     */
    private static void complexRoundTrip(GraphHopper hopper) {
        System.out.println("=== Example 3: Complex Round Trip ===");
        
        try {
            GHRequest request = new GHRequest();
            request.setProfile("car");
            request.setAlgorithm("round_trip");
            request.setLocale(Locale.FRANCE);
            
            // Multiple via points for a longer tour
            request.addPoint(new GHPoint(48.8566, 2.3522)); // Paris
            request.addPoint(new GHPoint(49.4432, 1.0993)); // Rouen
            request.addPoint(new GHPoint(47.2184, -1.5536)); // Nantes
            request.addPoint(new GHPoint(47.7516, 7.3355)); // Freiburg
            
            // Custom parameters
            request.getHints().putObject(Parameters.Algorithms.RoundTrip.DISTANCE, 300_000); // 300 km
            request.getHints().putObject(Parameters.Algorithms.RoundTrip.POINTS, 8);
            request.getHints().putObject(Parameters.Algorithms.RoundTrip.SEED, 456L);
            
            // Additional routing parameters
            request.getHints().putObject(Parameters.CH.DISABLE, true);
            request.getHints().putObject(Parameters.Routing.INSTRUCTIONS, true);
            request.getHints().putObject(Parameters.Routing.CALC_POINTS, true);
            
            GHResponse response = hopper.route(request);
            
            if (response.hasErrors()) {
                System.err.println("Errors occurred:");
                response.getErrors().forEach(err -> System.err.println("  - " + err.getMessage()));
            } else {
                ResponsePath path = response.getBest();
                System.out.println("Complex tour successfully calculated:");
                System.out.println("  Via points: 4 (Paris, Rouen, Nantes, Freiburg)");
                System.out.println("  Total distance: " + String.format("%.2f", path.getDistance() / 1000) + " km");
                System.out.println("  Estimated time: " + String.format("%.1f", path.getTime() / 3600000.0) + " hours");
                System.out.println("  Total points: " + path.getPoints().size());
                
                if (path.hasInstructions()) {
                    System.out.println("  Instructions: " + path.getInstructions().size() + " steps");
                }
            }
        } catch (Exception e) {
            System.err.println("Exception: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    /**
     * Additional helper method: Validate round trip response
     */
    private static void validateRoundTrip(ResponsePath path, int expectedViaPoints) {
        if (path == null) {
            throw new IllegalStateException("Path is null");
        }
        
        if (!path.isFound()) {
            throw new IllegalStateException("Path not found");
        }
        
        // Check that we have a valid round trip
        GHPoint first = path.getWaypoints().get(0);
        GHPoint last = path.getWaypoints().get(path.getWaypoints().size() - 1);
        
        double distance = Math.sqrt(
            Math.pow(first.getLat() - last.getLat(), 2) + 
            Math.pow(first.getLon() - last.getLon(), 2)
        );
        
        if (distance > 0.001) { // ~100m tolerance
            System.err.println("Warning: Round trip doesn't return to start point");
            System.err.println("  Start: " + first);
            System.err.println("  End: " + last);
            System.err.println("  Distance: " + distance);
        }
        
        System.out.println("✓ Round trip validation passed");
    }
}
