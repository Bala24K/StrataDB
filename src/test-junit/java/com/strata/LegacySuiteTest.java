package com.strata;

import com.strata.testutil.TestRunner;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;

/**
 * Bridges StrataDB's dependency-free test classes (plain classes with {@code test*} methods and
 * {@code com.strata.testutil.Assert}) into JUnit 5, so {@code mvn test} and CI run the exact same
 * suite as {@code scripts/run-tests.*}.
 *
 * <p>Lives in its own test source root ({@code src/test-junit/java}, added by the
 * build-helper plugin in {@code pom.xml}) so the sandbox build scripts, which compile
 * {@code src/test/java} without JUnit on the classpath, keep working unchanged.
 *
 * <p>Each test method gets a fresh instance of its class (JUnit semantics), so one test can't
 * leak state into the next through instance fields.
 */
class LegacySuiteTest {

    @TestFactory
    Stream<DynamicNode> suite() {
        return TestRunner.ALL_TEST_CLASSES.stream().map(LegacySuiteTest::containerFor);
    }

    private static DynamicNode containerFor(Class<?> testClass) {
        return DynamicContainer.dynamicContainer(
            testClass.getSimpleName(),
            TestRunner.testMethodsOf(testClass).stream().map(m -> testFor(testClass, m)));
    }

    private static DynamicNode testFor(Class<?> testClass, Method method) {
        return DynamicTest.dynamicTest(method.getName(), () -> {
            Object instance = testClass.getDeclaredConstructor().newInstance();
            method.setAccessible(true);
            try {
                method.invoke(instance);
            } catch (InvocationTargetException e) {
                throw e.getCause(); // surface the real assertion failure, not the reflection wrapper
            }
        });
    }
}
