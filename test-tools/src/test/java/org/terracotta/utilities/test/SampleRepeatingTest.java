/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.Request;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.terracotta.utilities.io.CapturedPrintStream;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Basic tests for {@link AbstractRepeatingTest}.
 * <p>
 * This test class includes a nested test class holding tests that should not be run
 * as a top-level test; the nested test class is run by the tests in this top-level class.
 */
public class SampleRepeatingTest extends AbstractRepeatingTest {

  private static final Map<String, TestParameters> TEST_PARAMETERS_MAP = TestParameters.toMap();

  @Test
  public void testNegativeIterationCount() {
    Request testMethod = Request.method(SampleParameterizedTests.class, "foo");
    assertThat(() -> repeatTestRequest(-1, testMethod), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> repeatTestRequest(-1, testMethod, EnumSet.noneOf(ListenerEvents.class)), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> repeatTestRequest(-1, testMethod, new AbstractRepeatingRunListener() {}), threw(instanceOf(IllegalArgumentException.class)));
  }

  @Test
  public void testNullRequest() {
    assertThat(() -> repeatTestRequest(1, null), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> repeatTestRequest(1, null, EnumSet.noneOf(ListenerEvents.class)), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> repeatTestRequest(1, null, new AbstractRepeatingRunListener() {}), threw(instanceOf(NullPointerException.class)));
  }

  @Test
  public void testNullListenerEvents() {
    Request testMethod = Request.method(SampleParameterizedTests.class, "foo");
    assertThat(() -> repeatTestRequest(1, testMethod, (Set<ListenerEvents>)null), threw(instanceOf(NullPointerException.class)));
  }

  @Test
  public void testNullListener() {
    Request testMethod = Request.method(SampleParameterizedTests.class, "foo");
    assertThat(() -> repeatTestRequest(1, testMethod, (AbstractRepeatingRunListener)null), threw(instanceOf(NullPointerException.class)));
  }

  @Test
  public void testRepeatedSuccessfulTest() throws Exception {
    TestParameters testParameters = TEST_PARAMETERS_MAP.get(testName.getMethodName());
    String testMethodName = testParameters.testName("testSomething");
    int repeatCount = testParameters.iterationsToFailure() - 1;
    Request testMethod = Request.method(SampleParameterizedTests.class, testMethodName);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (CapturedPrintStream testOut = CapturedPrintStream.getInstance();
         CapturedPrintStream testErr = CapturedPrintStream.getInstance()) {

      swapPrintStreams(testOut, testErr);
      try {
        repeatTestRequest(repeatCount, testMethod);
      } finally {
        swapPrintStreams(originalOut, originalErr);
      }

      List<String> stdout = getAll(testOut);
      stdout.forEach(System.out::println);
      assertThat(stdout, hasItem(
          both(containsString("Starting " + SampleParameterizedTests.class.getName())).and(containsString("iteration=" + repeatCount))));
      assertThat(stdout, hasItem(
          both(containsString("Starting " + testMethodName)).and(containsString("iteration=" + repeatCount))));
      assertThat(stdout, hasItem(
          both(containsString("Completed " + testMethodName)).and(containsString("iteration=" + repeatCount))));
      assertThat(stdout, hasItem(containsString("Ended  Runtime")));

      // Excludes the test output to stdout ...
      assertThat(stdout, not(
          hasItem(allOf(containsString(testMethodName), containsString(" Iteration=" + repeatCount),
              containsString("success")))));

      List<String> stderr = getAll(testErr);
      assertThat(stderr, is((empty())));
    }
  }

  @Test
  public void testRepeatedFailedTest() {
    TestParameters testParameters = TEST_PARAMETERS_MAP.get(testName.getMethodName());
    String testMethodName = testParameters.testName("testSomething");
    int iterationsToFailure = testParameters.iterationsToFailure();
    int repeatCount = iterationsToFailure + 1;
    Request testMethod = Request.method(SampleParameterizedTests.class, testMethodName);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (CapturedPrintStream testOut = CapturedPrintStream.getInstance();
         CapturedPrintStream testErr = CapturedPrintStream.getInstance()) {

      swapPrintStreams(testOut, testErr);
      try {
        assertThat(() -> repeatTestRequest(repeatCount, testMethod), threw(instanceOf(AssertionError.class)));
      } finally {
        swapPrintStreams(originalOut, originalErr);
      }

      List<String> stdout = getAll(testOut);
      assertThat(stdout, hasItem(
          both(containsString("Failed " + testMethodName)).and(containsString("iteration=" + iterationsToFailure))));
      assertThat(stdout, hasItem(startsWith("Failed tests")));
      assertThat(stdout, hasItem(containsString("There was 1 failure:")));
      assertThat(stdout, hasItem(containsString("1) " + testMethodName)));
      assertThat(stdout, hasItem(containsString(testParameters.fault().getName() + ": " + testMethodName + " iteration=" + iterationsToFailure)));
      assertThat(stdout, hasItem(containsString("Begin STDOUT for iteration=" + iterationsToFailure)));
      assertThat(stdout, hasItem(containsString("End STDOUT for iteration=" + iterationsToFailure)));
      assertThat(stdout, not(hasItem(containsString("Begin STDOUT for iteration=" + (iterationsToFailure - 1)))));
      assertThat(stdout, not(hasItem(containsString("End STDOUT for iteration=" + (iterationsToFailure - 1)))));

      List<String> stderr = getAll(testErr);
      assertThat(stderr, hasItem(containsString("Begin STDERR for iteration=" + iterationsToFailure)));
      assertThat(stderr, hasItem(
          allOf(containsString(testMethodName), containsString(" Iteration=" + iterationsToFailure),
              containsString("failing with " + testParameters.fault().getName() + ": " + testMethodName + " iteration=" + iterationsToFailure))));
      assertThat(stderr, hasItem(containsString("End STDERR for iteration=" + iterationsToFailure)));
    }
  }

  @Test
  public void testSuppressedStdoutTest() throws Exception {
    TestParameters testParameters = TEST_PARAMETERS_MAP.get(testName.getMethodName());
    String testMethodName = testParameters.testName("testSomething");
    int repeatCount = testParameters.iterationsToFailure() - 1;
    Request testMethod = Request.method(SampleParameterizedTests.class, testMethodName);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (CapturedPrintStream testOut = CapturedPrintStream.getInstance();
         CapturedPrintStream testErr = CapturedPrintStream.getInstance()) {

      swapPrintStreams(testOut, testErr);
      try {
        repeatTestRequest(repeatCount, testMethod, new AbstractRepeatingRunListener() {
          // Test progress output suppressed
        });
      } finally {
        swapPrintStreams(originalOut, originalErr);
      }

      List<String> stdout = getAll(testOut);
      assertThat(stdout, is(empty()));

      List<String> stderr = getAll(testErr);
      assertThat(stderr, is(empty()));
    }
  }

  @Test
  public void testPresentStderrTest() {
    TestParameters testParameters = TEST_PARAMETERS_MAP.get(testName.getMethodName());
    String testMethodName = testParameters.testName("testSomething");
    int iterationsToFailure = testParameters.iterationsToFailure();
    int repeatCount = iterationsToFailure + 1;
    Request testMethod = Request.method(SampleParameterizedTests.class, testMethodName);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (CapturedPrintStream testOut = CapturedPrintStream.getInstance();
         CapturedPrintStream testErr = CapturedPrintStream.getInstance()) {

      swapPrintStreams(testOut, testErr);
      try {
        assertThat(() -> repeatTestRequest(repeatCount, testMethod, new AbstractRepeatingRunListener() {
          // Test progress output suppressed
        }), threw(instanceOf(AssertionError.class)));
      } finally {
        swapPrintStreams(originalOut, originalErr);
      }

      List<String> stdout = getAll(testOut);
      assertThat(stdout, hasItem(containsString("There was 1 failure:")));
      assertThat(stdout, hasItem(containsString("1) " + testMethodName)));
      assertThat(stdout, hasItem(containsString(testParameters.fault().getName() + ": " + testMethodName + " iteration=" + iterationsToFailure)));
      assertThat(stdout, hasItem(containsString("Begin STDOUT for iteration=" + iterationsToFailure)));
      assertThat(stdout, hasItem(containsString("End STDOUT for iteration=" + iterationsToFailure)));
      assertThat(stdout, not(hasItem(containsString("Begin STDOUT for iteration=" + (iterationsToFailure - 1)))));
      assertThat(stdout, not(hasItem(containsString("End STDOUT for iteration=" + (iterationsToFailure - 1)))));

      List<String> stderr = getAll(testErr);
      assertThat(stderr, hasItem(containsString("Begin STDERR for iteration=" + iterationsToFailure)));
      assertThat(stderr, hasItem(
          allOf(containsString(testMethodName), containsString(" Iteration=" + iterationsToFailure),
              containsString("failing with " + testParameters.fault().getName() + ": " + testMethodName + " iteration=" + iterationsToFailure))));
      assertThat(stderr, hasItem(containsString("End STDERR for iteration=" + iterationsToFailure)));
    }
  }

  @Test
  public void testRepeatedSuccessfulTestSuppressedListener() throws Exception {
    TestParameters testParameters = TEST_PARAMETERS_MAP.get(testName.getMethodName());
    String testMethodName = testParameters.testName("testSomething");
    int repeatCount = testParameters.iterationsToFailure() - 1;
    Request testMethod = Request.method(SampleParameterizedTests.class, testMethodName);

    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (CapturedPrintStream testOut = CapturedPrintStream.getInstance();
         CapturedPrintStream testErr = CapturedPrintStream.getInstance()) {

      swapPrintStreams(testOut, testErr);
      try {
        repeatTestRequest(repeatCount, testMethod,
            EnumSet.complementOf(EnumSet.of(ListenerEvents.TEST_FINISHED, ListenerEvents.TEST_STARTED)));
      } finally {
        swapPrintStreams(originalOut, originalErr);
      }

      List<String> stdout = getAll(testOut);
      assertThat(stdout, hasItem(
          both(containsString("Starting " + SampleParameterizedTests.class.getName())).and(containsString("iteration=" + repeatCount))));
      assertThat(stdout, not(hasItem(   // testStarted
          both(containsString("Starting " + testMethodName)).and(containsString("iteration=" + repeatCount)))));
      assertThat(stdout, not(hasItem(   // testFinished
          both(containsString("Completed " + testMethodName)).and(containsString("iteration=" + repeatCount)))));
      assertThat(stdout, hasItem(containsString("Ended  Runtime")));

      // Excludes the test output to stdout ...
      assertThat(stdout, not(
          hasItem(allOf(containsString(testMethodName), containsString(" Iteration=" + repeatCount),
              containsString("success")))));

      List<String> stderr = getAll(testErr);
      assertThat(stderr, is((empty())));
    }
  }

  private static void swapPrintStreams(PrintStream stdout, PrintStream stderr) {
    stdout.flush();
    stderr.flush();
    System.setOut(stdout);
    System.setErr(stderr);
  }

  private static List<String> getAll(CapturedPrintStream printStream) {
    try (Stream<String> stream = printStream.getReader().lines()) {
      return stream.collect(toList());
    }
  }

  /**
   * Parameterized tests executed from {@link SampleRepeatingTest}.
   * Why parameterized?  This serves as an example of how to refer to
   * parameterized tests when constructing the {@code Request} passed
   * to {@link AbstractRepeatingTest#repeatTestRequest}.
   */
  @SuppressWarnings("JUnitMalformedDeclaration")
  @RunWith(Parameterized.class)
  public static class SampleParameterizedTests {

    @Parameterized.Parameters(name = "{0}:{1}")
    public static List<Object[]> data() {
      return Arrays.asList(new Object[][] {
              { "testRepeatedSuccessfulTest", 10, (Function<String, Throwable>)AssertionError::new },
              { "testRepeatedFailedTest", 5, (Function<String, Throwable>)AssertionError::new },
              { "testSuppressedStdoutTest", 15, (Function<String, Throwable>)AssertionError::new },
              { "testPresentStderrTest", 5, (Function<String, Throwable>)AssertionError::new },
              { "testRepeatedSuccessfulTestSuppressedListener", 5, (Function<String, Throwable>)AssertionError::new },
          }
      );
    }

    @Parameterized.Parameter
    public String caseName;

    @Parameterized.Parameter(1)
    public int iterationsToFailure;

    @Parameterized.Parameter(2)
    public Function<String, Throwable> failure;

    @Rule
    public final TestName testName = new TestName();

    private int iterationCount;

    private static final Map<String, Integer> TEST_ITERATION_COUNT = new ConcurrentHashMap<>();

    @Before
    public void countIterations() {
      this.iterationCount = TEST_ITERATION_COUNT.compute(testName.getMethodName(), (k, v) -> {
        if (v == null) {
          return 1;
        } else {
          return v + 1;
        }
      });
    }

    @Test
    public void testSomething() throws Throwable {
      if (iterationCount >= iterationsToFailure) {
        Throwable throwable = failure.apply(String.format("%s iteration=%d", testName.getMethodName(), iterationCount));
        System.err.printf("[%s] Iteration=%d; failing with %s%n", testName.getMethodName(), iterationCount, throwable);
        throw throwable;
      }
      System.out.printf("[%s] Iteration=%d; success", testName.getMethodName(), iterationCount);
    }
  }

  private static class TestParameters {
    private static final String PARAMETERIZED_TEST_ID;
    static {
      String testIdPattern;
      try {
        testIdPattern = SampleParameterizedTests.class.getDeclaredMethod("data")
            .getDeclaredAnnotation(Parameterized.Parameters.class).name();
      } catch (NoSuchMethodException e) {
        testIdPattern = "{index}";
      }
      PARAMETERIZED_TEST_ID = testIdPattern;
    }

    private final int index;
    private final String caseName;
    private final int iterationsToFailure;
    private final Class<? extends Throwable> fault;
    private final String testId;

    public static Map<String, TestParameters> toMap() {
      List<Object[]> data = SampleParameterizedTests.data();
      return IntStream.range(0, data.size())
          .mapToObj(i -> TestParameters.instance(i, data.get(i)))
          .collect(Collectors.toMap(TestParameters::caseName, Function.identity()));
    }

    public static TestParameters instance(int index, Object[] parameters) {
      String caseName = (String)parameters[0];
      int iterationsToFailure = (int)parameters[1];
      @SuppressWarnings("unchecked") Class<? extends Throwable> fault =
          ((Function<String, Throwable>)parameters[2]).apply("").getClass();  // unchecked
      return new TestParameters(index, caseName, iterationsToFailure, fault);
    }

    private TestParameters(int index, String caseName, int iterationsToFailure, Class<? extends Throwable> fault) {
      this.index = index;
      this.caseName = caseName;
      this.iterationsToFailure = iterationsToFailure;
      this.fault = fault;
      this.testId = formKey();
    }

    public String caseName() {
      return caseName;
    }

    public int iterationsToFailure() {
      return iterationsToFailure;
    }

    public Class<? extends Throwable> fault() {
      return fault;
    }

    public String testName(String testMethod) {
      return String.format("%s[%s]", testMethod, testId);
    }

    private String formKey() {
      String format = PARAMETERIZED_TEST_ID.replaceAll(Pattern.quote("{index}"), Integer.toString(index));
      return MessageFormat.format(format, caseName, iterationsToFailure, fault.getName());
    }
  }
}