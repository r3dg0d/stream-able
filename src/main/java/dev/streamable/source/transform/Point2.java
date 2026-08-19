package dev.streamable.source.transform;

/** An immutable 2D point in a well-defined coordinate space (canvas or source-local). */
public record Point2(double x, double y) {

    public static final Point2 ZERO = new Point2(0, 0);

    public Point2 plus(Point2 other) {
        return new Point2(x + other.x, y + other.y);
    }

    public Point2 minus(Point2 other) {
        return new Point2(x - other.x, y - other.y);
    }

    public double distanceTo(Point2 other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    /** Rotates this point about the origin by {@code degrees} (clockwise in screen space). */
    public Point2 rotate(double degrees) {
        double r = Math.toRadians(degrees);
        double c = Math.cos(r);
        double s = Math.sin(r);
        return new Point2(x * c - y * s, x * s + y * c);
    }
}
