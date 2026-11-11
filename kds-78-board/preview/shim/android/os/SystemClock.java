package android.os;
public final class SystemClock { public static long uptimeMillis() { return System.nanoTime() / 1000000L; } }
