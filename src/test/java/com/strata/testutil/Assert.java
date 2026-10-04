package com.strata.testutil;

import java.util.Arrays;
import java.util.Objects;

/**
 * A minimal, dependency-free stand-in for org.junit.jupiter.api.Assertions.
 *
 * <p>This sandbox environment has no internet access, so we can't {@code mvn install}
 * the real JUnit 5 jar. This class mimics JUnit 5's assertion API closely enough that
 * every test written against it below is a near copy-paste away from real JUnit once
 * you build this project on your own machine with the pom.xml provided (which already
 * declares the real junit-jupiter dependency).
 */
public final class Assert {

    private Assert() {}

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(format(message, "expected <" + expected + "> but was <" + actual + ">"));
        }
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, null);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(format(message, "expected condition to be true"));
        }
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, null);
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(format(message, "expected condition to be false"));
        }
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertArrayEquals(expected, actual, null);
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(format(message,
                "expected <" + Arrays.toString(expected) + "> but was <" + Arrays.toString(actual) + ">"));
        }
    }

    public static void assertThrows(Class<? extends Throwable> expectedType, ThrowingRunnable action) {
        assertThrows(expectedType, action, null);
    }

    public static void assertThrows(Class<? extends Throwable> expectedType, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return;
            }
            throw new AssertionError(format(message,
                "expected " + expectedType.getSimpleName() + " but caught " + t.getClass().getSimpleName()), t);
        }
        throw new AssertionError(format(message, "expected " + expectedType.getSimpleName() + " but nothing was thrown"));
    }

    private static String format(String message, String detail) {
        return message == null ? detail : message + " (" + detail + ")";
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
