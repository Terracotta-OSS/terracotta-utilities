/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.utilities.test;

import org.junit.Rule;
import org.junit.experimental.results.PrintableResult;
import org.junit.rules.TestName;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.terracotta.org.junit.rules.TemporaryFolder;
import org.terracotta.utilities.io.CapturedPrintStream;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Foundation on which to build JUnit test class used to repeat another test.
 * <p>
 * To use, implement a concrete class, extending this class, in the same module as the test or
 * test suite to be repeated.  For example, the following concrete class will repeat the
 * <i>parameterized</i> test {@code ClassHoldingTestsToRepeat.testMethodName} for the
 * {@code parameterId} parameter set 5000 times or until it fails.
 * <pre><code>
 * public class FooTest extends AbstractRepeatingTest {
 *   &#x40;Test
 *   public void testFoo() throws Exception {
 *     int repeatCount = 5000;
 *     Request testMethod = Request.method(ClassHoldingTestsToRepeat.class, "testMethodName[parameterId]");
 *     repeatTestRequest(repeatCount, testMethod);
 *   }
 * }
 * </code></pre>
 *
 * The {@code Request} can be composed of any collection of tests supported by the methods in the {@code Request} class.
 *
 * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/Request.html"><code>org.junit.runner.Request</code></a>
 * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">org.junit.runner.notification.RunListener</a>
 */
public abstract class AbstractRepeatingTest {
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule
  public final TestName testName = new TestName();

  /**
   * Repeats the indicated test (or tests) the given number of times or until a test fails.
   * <p>
   * This method uses an internal {@link AbstractRepeatingRunListener} implementation to report all
   * <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">RunListener</a>
   * events to {@code System.out}.
   * <p>
   * <b>This method <i>substitutes</i> "capture" files for {@code System.out} and {@code System.err}.
   * Tests relying on output to {@code System.out} or {@code System.err} may be incompatible with this method.</b>
   * <p>
   * If a test fails,
   * <ol>
   *   <li>The JUnit failure summary is emitted to {@code System.out}.</li>
   *   <li>The captured {@code stdout} content for the failing test(s) is copied to {@code System.out};
   *      this output is wrapped in marker lines <pre>{@code
   *        >>> Begin failing stdout
   *        ...
   *        <<< End failing stdout
   *      }</pre></li>
   *   <li>The captured {@code stderr} content for the failing test(s) is copies to {@code System.err};
   *       this output is wrapped in marker lines <pre>{@code
   *         >>> Begin failing stderr
   *         ...
   *         <<< End failing stderr
   *       }</pre></li>
   *   <li>An {@code AssertionError} is thrown with the failure exception for each failing test present
   *      as a suppressed exception.</li>
   * </ol>
   * <p>
   * Each successful, skipped, or ignored test execution is recorded to {@code System.out}.
   *
   * @param repeatCount the maximum number of times to repeat the test(s)
   * @param testMethod  the JUnit {@code Request} identifying the test(s) to run
   * @throws IOException if an error is raised while accessing one of the output capture files
   * @throws AssertionError if a test fails; the {@code AssertionError} instance will contain, as
   *      suppressed exceptions, the failure(s) from the tests
   * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">org.junit.runner.notification.RunListener</a>
   */
  protected final void repeatTestRequest(int repeatCount, Request testMethod) throws IOException, AssertionError {
    repeatTestRequest(repeatCount, testMethod, new LocalListener(System.out, EnumSet.allOf(ListenerEvents.class)));
  }

  /**
   * Repeats the indicated test (or tests) the given number of times or until a test fails.
   * <p>
   * This method uses an internal {@link AbstractRepeatingRunListener} implementation to report
   * <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">RunListener</a>
   * events identified by {@code enabledEvents} to {@code System.out}.
   * <p>
   * <b>This method <i>substitutes</i> "capture" files for {@code System.out} and {@code System.err}.
   * Tests relying on output to {@code System.out} or {@code System.err} may be incompatible with this method.</b>
   * <p>
   * If a test fails,
   * <ol>
   *   <li>The JUnit failure summary is emitted to {@code System.out}.</li>
   *   <li>The captured {@code stdout} content for the failing test(s) is copied to {@code System.out};
   *      this output is wrapped in marker lines <pre>{@code
   *        >>> Begin failing stdout
   *        ...
   *        <<< End failing stdout
   *      }</pre></li>
   *   <li>The captured {@code stderr} content for the failing test(s) is copies to {@code System.err};
   *       this output is wrapped in marker lines <pre>{@code
   *         >>> Begin failing stderr
   *         ...
   *         <<< End failing stderr
   *       }</pre></li>
   *   <li>An {@code AssertionError} is thrown with the failure exception for each failing test present
   *      as a suppressed exception.</li>
   * </ol>
   * <p>
   * Each successful, skipped, or ignored test execution is recorded to {@code System.out}.
   *
   * @param repeatCount the maximum number of times to repeat the test(s)
   * @param testMethod  the JUnit {@code Request} identifying the test(s) to run
   * @param enabledEvents the set of {@link ListenerEvents} for which information is written to stdout;
   *                      an empty set suppresses output for all events
   * @throws IOException if an error is raised while accessing one of the output capture files
   * @throws AssertionError if a test fails; the {@code AssertionError} instance will contain, as
   *      suppressed exceptions, the failure(s) from the tests
   * @see ListenerEvents
   * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">org.junit.runner.notification.RunListener</a>
   */
  protected final void repeatTestRequest(int repeatCount, Request testMethod, Set<ListenerEvents> enabledEvents)
      throws IOException, AssertionError {
    repeatTestRequest(repeatCount, testMethod, new LocalListener(System.out, Objects.requireNonNull(enabledEvents)));
  }

  /**
   * Repeats the indicated test (or tests) the given number of times or until a test fails.
   * <p>
   * <b>This method <i>substitutes</i> "capture" files for {@code System.out} and {@code System.err}.
   * Tests relying on output to {@code System.out} or {@code System.err} may be incompatible with this method.</b>
   * <p>
   * If a test fails,
   * <ol>
   *   <li>The JUnit failure summary is emitted to {@code System.out}.</li>
   *   <li>The captured {@code stdout} content for the failing test(s) is copied to {@code System.out};
   *      this output is wrapped in marker lines <pre>{@code
   *        >>> Begin failing stdout
   *        ...
   *        <<< End failing stdout
   *      }</pre></li>
   *   <li>The captured {@code stderr} content for the failing test(s) is copies to {@code System.err};
   *       this output is wrapped in marker lines <pre>{@code
   *         >>> Begin failing stderr
   *         ...
   *         <<< End failing stderr
   *       }</pre></li>
   *   <li>An {@code AssertionError} is thrown with the failure exception for each failing test present
   *      as a suppressed exception.</li>
   * </ol>
   * <p>
   * Each successful, skipped, or ignored test execution is recorded to {@code System.out}.
   *
   * @param repeatCount the maximum number of times to repeat the test(s)
   * @param testMethod  the JUnit {@code Request} identifying the test(s) to run
   * @param listener the non-{@code null} {@code AbstractRepeatingRunListener} to receive JUnit test events
   * @throws IOException if an error is raised while accessing one of the output capture files
   * @throws AssertionError if a test fails; the {@code AssertionError} instance will contain, as
   *      suppressed exceptions, the failure(s) from the tests
   *
   * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">org.junit.runner.notification.RunListener</a>
   */
  protected final void repeatTestRequest(int repeatCount, Request testMethod, AbstractRepeatingRunListener listener)
      throws IOException, AssertionError {
    if (repeatCount <= 0) {
      throw new IllegalArgumentException("repeatCount must be a positive integer");
    }
    Objects.requireNonNull(testMethod, "testMethod cannot be null" );
    Objects.requireNonNull(listener, "listener cannot be null" );

    JUnitCore core = new JUnitCore();
    core.addListener(listener);

    Path outPath = temporaryFolder.newFile(testName.getMethodName() + ".stdout").toPath();
    Path errPath = temporaryFolder.newFile(testName.getMethodName() + ".stderr").toPath();

    /*
     * While capturing stdout and stderr output, run the test for the specified repeat count or
     * until the test fails.  If the test fails, copy the captured stdout and stderr to the
     * test's original stdout and stderr and
     */
    try (CapturedPrintStream captureStdout = CapturedPrintStream.getInstance(outPath);
         CapturedPrintStream captureStderr = CapturedPrintStream.getInstance(errPath)) {

      for (int i = 0; i < repeatCount; i++) {
        int iteration = i + 1;
        listener.setIteration(iteration);

        PrintStream stdout = System.out;
        PrintStream stderr = System.err;

        System.setOut(captureStdout);
        System.setErr(captureStderr);

        long testStartTime = System.currentTimeMillis();
        captureStdout.format("%1$tT.%1tL [%02d] Begin STDOUT for iteration=%2$d%n", testStartTime, iteration);
        captureStderr.format("%1$tT.%1tL [%02d] Begin STDERR for iteration=%2$d%n", testStartTime, iteration);

        Result result;
        try {
          result = core.run(testMethod);
        } finally {
          System.setErr(stderr);
          System.setOut(stdout);

          long testEndTime = System.currentTimeMillis();
          captureStdout.format("%1$tT.%1tL [%02d] End STDOUT for iteration=%2$d%n", testEndTime, iteration);
          captureStderr.format("%1$tT.%1tL [%02d] End STDERR for iteration=%2$d%n", testEndTime, iteration);
          captureStdout.flush();
          captureStderr.flush();
        }

        if (result.wasSuccessful()) {
          // Output from a successful test is of no interest; reset the capture files
          captureStdout.reset();
          captureStderr.reset();

        } else {
          // Emit the details for a failed test and terminate the test cycling
          List<Failure> failures = result.getFailures();
          System.out.print(new PrintableResult(failures));

          System.out.println(">>> Begin failing stdout");
          captureStdout.getReader().lines().forEachOrdered(System.out::println);
          System.out.println("<<< End failing stdout");

          System.err.println(">>> Begin failing stderr");
          captureStderr.getReader().lines().forEachOrdered(System.err::println);
          System.err.println("<<< End failing stderr");

          AssertionError totalFailure = new AssertionError("Failed running iteration " + iteration);
          for (Failure failure : failures) {
            totalFailure.addSuppressed(failure.getException());
          }
          throw totalFailure;
        }
      }
    }
  }

  /*
   * JUnit {@code RunListener} to emit test tracking messages.
   */
  private static class LocalListener extends AbstractRepeatingRunListener {
    private final PrintStream out;
    private final Set<ListenerEvents> activeEvents;

    LocalListener(PrintStream out, Set<ListenerEvents> activeEvents) {
      this.out = out;
      this.activeEvents = activeEvents;
    }

    @Override
    public void testRunStarted(Description description) {
      if (activeEvents.contains(ListenerEvents.RUN_STARTED)) {
        out.format("%n%1$tT.%1tL [%02d] Starting %s; iteration=%2$d%n",
            System.currentTimeMillis(), iteration(), description);
      }
    }

    @Override
    public void testStarted(Description description) {
      if (activeEvents.contains(ListenerEvents.TEST_STARTED)) {
        out.format("%1$tT.%1tL [%02d] Starting %s; iteration=%2$d%n",
            System.currentTimeMillis(), iteration(), description);
      }
    }

    @Override
    public void testFinished(Description description) {
      if (activeEvents.contains(ListenerEvents.TEST_FINISHED)) {
        out.format("%1$tT.%1tL [%02d] Completed %s; iteration=%2$d%n",
            System.currentTimeMillis(), iteration(), description);
      }
    }

    @Override
    public void testFailure(Failure failure) {
      if (activeEvents.contains(ListenerEvents.TEST_FAILED)) {
        out.format("%n%1$tT.%1tL [%02d] Failed %s; iteration=%2$d%n    %s%n",
            System.currentTimeMillis(), iteration(), failure.getDescription(), failure.getException());
      }
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
      if (activeEvents.contains(ListenerEvents.TEST_SKIPPED)) {
        out.format("%1$tT.%1tL [%02d] Skipped %s; iteration=%2$d%n    %s%n",
            System.currentTimeMillis(), iteration(), failure.getDescription(), failure.getException());
      }
    }

    @Override
    public void testIgnored(Description description) {
      if (activeEvents.contains(ListenerEvents.TEST_IGNORED)) {
        out.format("%1$tT.%1tL [%02d] Ignored %s; iteration=%2$d%n",
            System.currentTimeMillis(), iteration(), description);
      }
    }

    @Override
    public void testRunFinished(Result result) {
      if (activeEvents.contains(ListenerEvents.RUN_FINISHED)) {
        out.format("%1$tT.%1tL [%02d] Ended  Runtime=%.3f s%n",
            System.currentTimeMillis(), iteration(), result.getRunTime() / 1000f);
        if (result.getFailureCount() != 0) {
          out.println("Failed tests:");
          result.getFailures().forEach(failure -> out.format("    %s%n", failure));
        }
        out.format("Tests run=%d, ignored=%d, failed=%d%n",
            result.getRunCount(), result.getIgnoreCount(), result.getFailureCount());
      }
    }
  }

  /**
   * Identifies {@code RunListener} events of the internal {@code RunListener} used by
   * {@link AbstractRepeatingTest#repeatTestRequest(int, Request, Set)} to activate for output.
   */
  public enum ListenerEvents {
    /** Activate calls to {@code RunListener.testRunStarted}. */
    RUN_STARTED,
    /** Activate calls to {@code RunListener.testRunFinished}. */
    RUN_FINISHED,
    /** Activate calls to {@code RunListener.testStarted}. */
    TEST_STARTED,
    /** Activate calls to {@code RunListener.testFinished}. */
    TEST_FINISHED,
    /** Activate calls to {@code RunListener.testFailure}. */
    TEST_FAILED,
    /** Activate calls to {@code RunListener.testIgnored}. */
    TEST_IGNORED,
    /** Activate calls to {@code RunListener.testAssumptionFailure}. */
    TEST_SKIPPED
  }
}
