package com.arnis.ingame;

public final class LondonProjection {
    public static final double ORIGIN_LATITUDE = 51.5074;
    public static final double ORIGIN_LONGITUDE = -0.1278;
    public static final double WORLD_BORDER = 29_999_984.0;
    public static final double MAX_LATITUDE = 85.05112878;

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double SCALE = Math.cos(Math.toRadians(ORIGIN_LATITUDE));
    private static final double ORIGIN_MERCATOR_Y = mercatorY(ORIGIN_LATITUDE);

    private LondonProjection() {}

    public static WorldPoint project(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180.");
        }

        double clampedLatitude = clampLatitude(latitude);
        double x = EARTH_RADIUS_METERS
            * Math.toRadians(longitude - ORIGIN_LONGITUDE)
            * SCALE;
        double z = -(mercatorY(clampedLatitude) - ORIGIN_MERCATOR_Y) * SCALE;
        return new WorldPoint(x, z);
    }

    public static GeoPoint unproject(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("World coordinates must be finite.");
        }

        double mercatorY = ORIGIN_MERCATOR_Y - z / SCALE;
        double latitude = Math.toDegrees(
            2.0 * (Math.atan(Math.exp(mercatorY / EARTH_RADIUS_METERS)) - Math.PI / 4.0)
        );
        double longitude = ORIGIN_LONGITUDE
            + Math.toDegrees(x / (EARTH_RADIUS_METERS * SCALE));
        return new GeoPoint(latitude, longitude);
    }

    public static GeoPoint chunkCenter(int chunkX, int chunkZ) {
        return unproject(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
    }

    public static boolean isSupportedLocation(double latitude, double longitude) {
        return Double.isFinite(latitude)
            && Double.isFinite(longitude)
            && Math.abs(latitude) <= MAX_LATITUDE
            && Math.abs(longitude) <= 180.0;
    }

    private static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    private static double mercatorY(double latitude) {
        double radians = Math.toRadians(clampLatitude(latitude));
        return EARTH_RADIUS_METERS * Math.log(Math.tan(Math.PI / 4.0 + radians / 2.0));
    }

    public record WorldPoint(double x, double z) {}

    public record GeoPoint(double latitude, double longitude) {}
}
