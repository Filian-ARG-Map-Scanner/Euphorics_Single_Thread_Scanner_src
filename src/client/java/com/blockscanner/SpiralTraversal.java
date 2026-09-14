package com.blockscanner;

/**
 * Generates the clockwise square spiral used for scan waypoints.
 */
public final class SpiralTraversal {
	public static final double DEFAULT_SPEED = 8.0D;

	private SpiralTraversal() {
	}

	public record Waypoint(int x, int z) {
	}

	/**
	 * Returns the next waypoint after the supplied waypoint, including exact corners.
	 */
	public static Waypoint next(int x, int z) {
		return next(x, z, 1);
	}

	public static Waypoint next(int x, int z, int step) {
		if (step <= 0) {
			throw new IllegalArgumentException("step must be greater than zero");
		}
		if (x == 0 && z == 0) {
			return new Waypoint(step, 0);
		}

		int gridX = Math.floorDiv(x, step);
		int gridZ = Math.floorDiv(z, step);
		int ring = Math.max(Math.abs(gridX), Math.abs(gridZ));
		if (gridX == ring && gridZ > -ring && gridZ < ring) {
			return new Waypoint(x, z + step);
		}
		if (gridZ == ring && gridX > -ring) {
			return new Waypoint(x - step, z);
		}
		if (gridX == -ring && gridZ > -ring) {
			return new Waypoint(x, z - step);
		}
		if (gridZ == -ring && gridX < ring) {
			return new Waypoint(x + step, z);
		}

		return new Waypoint((ring + 1) * step, -ring * step);
	}

	public static Waypoint nearest(int x, int z, int step) {
		if (step <= 0) {
			throw new IllegalArgumentException("step must be greater than zero");
		}
		return new Waypoint(
			(int) Math.round((double) x / step) * step,
			(int) Math.round((double) z / step) * step
		);
	}
}
