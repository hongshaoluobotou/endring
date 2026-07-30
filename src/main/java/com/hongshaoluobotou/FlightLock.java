package com.hongshaoluobotou;

public final class FlightLock {
	private static final ThreadLocal<Boolean> LOCKED = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private FlightLock() {
	}

	public static boolean isLocked() {
		return LOCKED.get();
	}

	public static void setLocked(boolean value) {
		LOCKED.set(value);
	}
}
