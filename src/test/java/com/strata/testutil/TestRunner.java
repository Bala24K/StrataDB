package com.strata.testutil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers every no-arg public method named {@code testXxx} on the given classes
 * (a stand-in for JUnit's {@code @Test} scanning) and runs them, reporting a
 * PASS/FAIL line per test plus a summary. Exits with status 1 if anything failed,
 * so this doubles as a CI-friendly gate.
 */
public final class TestRunner {

    private TestRunner() {}

    /**
     * Every test class in the suite, in run order. This single list is shared by
     * {@link #main} (the dependency-free runner used by {@code scripts/run-tests.*}) and by
     * {@code com.strata.LegacySuiteTest} (the JUnit 5 bridge that {@code mvn test} runs), so a
     * new test class only has to be registered once to be picked up by both.
     */
    public static final List<Class<?>> ALL_TEST_CLASSES = List.of(
        com.strata.storage.page.PageTest.class,
        com.strata.storage.page.DiskManagerTest.class,
        com.strata.storage.SlottedPageTest.class,
        com.strata.storage.HeapTableTest.class,
        com.strata.catalog.TableInfoTest.class,
        com.strata.catalog.CatalogTest.class,
        com.strata.lexer.TokenTest.class,
        com.strata.lexer.LexerTest.class,
        com.strata.parser.ParserTest.class,
        com.strata.executor.RowSerializerTest.class,
        com.strata.executor.ExecutorTest.class,
        com.strata.index.hash.HashIndexTest.class,
        com.strata.transaction.TransactionTest.class,
        com.strata.transaction.SessionTest.class,
        com.strata.lock.LockManagerTest.class,
        com.strata.transaction.SessionConcurrencyTest.class,
        com.strata.buffer.BufferPoolTest.class,
        com.strata.transaction.SessionBufferPoolTest.class,
        com.strata.optimizer.QueryPlannerTest.class,
        com.strata.recovery.WriteAheadLogTest.class,
        com.strata.network.DatabaseServerTest.class,
        com.strata.transaction.ConcurrencyRegressionTest.class,
        com.strata.transaction.TransactionLifecycleTest.class,
        com.strata.network.ServerRegressionTest.class,
        com.strata.recovery.RecoveryHardeningTest.class,
        com.strata.recovery.CrashInjectionTest.class,
        com.strata.recovery.ProcessKillTest.class,
        com.strata.engine.MetricsTest.class,
        com.strata.recovery.GroupCommitCheckpointTest.class
    );

    public static void main(String[] args) throws Exception {
        boolean allPassed = run(ALL_TEST_CLASSES.toArray(new Class<?>[0]));
        if (!allPassed) {
            System.exit(1);
        }
    }

    /** Test methods of {@code testClass} as this runner discovers them: public-or-not, no-arg, named {@code test*}, sorted by name. */
    public static List<Method> testMethodsOf(Class<?> testClass) {
        List<Method> testMethods = new ArrayList<>();
        for (Method m : testClass.getDeclaredMethods()) {
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) {
                testMethods.add(m);
            }
        }
        testMethods.sort(Comparator.comparing(Method::getName));
        return testMethods;
    }

    public static boolean run(Class<?>... testClasses) throws Exception {
        int totalPass = 0;
        int totalFail = 0;

        for (Class<?> testClass : testClasses) {
            Object instance = testClass.getDeclaredConstructor().newInstance();

            List<Method> testMethods = testMethodsOf(testClass);

            System.out.println("=== " + testClass.getSimpleName() + " ===");
            for (Method m : testMethods) {
                m.setAccessible(true);
                try {
                    m.invoke(instance);
                    System.out.println("  [PASS] " + m.getName());
                    totalPass++;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    System.out.println("  [FAIL] " + m.getName() + " -> " + cause);
                    totalFail++;
                }
            }
        }

        System.out.println();
        System.out.println("Total: " + (totalPass + totalFail) + ", Passed: " + totalPass + ", Failed: " + totalFail);
        return totalFail == 0;
    }
}
