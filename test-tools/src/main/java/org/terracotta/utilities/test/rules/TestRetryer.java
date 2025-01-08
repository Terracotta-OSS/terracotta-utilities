/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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
package org.terracotta.utilities.test.rules;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.max;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertTrue;

public class TestRetryer<T, R> implements TestRule, Supplier<R> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestRetryer.class);

  private final RetryMode retryMode;
  private final Function<T, R> mapper;
  private final Set<OutputIs> outputIs;
  private final Supplier<Stream<? extends T>> inputs;

  private final AtomicReference<T> inputRef = new AtomicReference<>();
  private final AtomicReference<R> outputRef = new AtomicReference<>();

  private volatile boolean classRuleApplied = false;
  private volatile boolean ruleApplied = false;

  private volatile boolean terminalAttempt = false;
  private volatile Map<Description, Object> attemptResults = Collections.emptyMap();

  private final Map<Description, Object> accumulatedResults = new ConcurrentHashMap<>();

  @SafeVarargs @SuppressWarnings("varargs") // Creating a stream from an array is safe
  public static <T> TestRetryer<T, T> tryValues(T... values) {
    return tryValues(asList(values));
  }

  public static <T> TestRetryer<T, T> tryValues(Collection<? extends T> values) {
    requireNonNull(values).forEach(Objects::requireNonNull);
    return new TestRetryer<>(values::stream, Function.identity(), EnumSet.noneOf(OutputIs.class), RetryMode.FAILED);
  }

  private TestRetryer(Supplier<Stream<? extends T>> values, Function<T, R> mapper, Set<OutputIs> outputIs, RetryMode retryMode) {
    this.inputs = () -> values.get().map(Objects::requireNonNull);
    this.mapper = requireNonNull(mapper);
    this.outputIs = requireNonNull(outputIs);
    this.retryMode = retryMode;
  }

  public <O> TestRetryer<T, O> map(Function<? super R, ? extends O> mapper) {
    return new TestRetryer<>(inputs, this.mapper.andThen(mapper), outputIs, retryMode);
  }

  public TestRetryer<T, R> retry(RetryMode retryMode) {
    return new TestRetryer<>(inputs, mapper, outputIs, retryMode);
  }

  public TestRetryer<T, R> outputIs(OutputIs a, OutputIs ... and) {
    Set<OutputIs> newOut = EnumSet.copyOf(this.outputIs);
    newOut.add(a);
    newOut.addAll(asList(and));
    return new TestRetryer<>(inputs, this.mapper, newOut, retryMode);
  }

  @Override
  public Statement apply(Statement base, Description description) {
    if (description.isTest()) {
      return applyRule(base, description);
    } else {
      return applyClassRule(base, description);
    }
  }

  private Statement applyClassRule(Statement base, Description description) {
    classRuleApplied = true;
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        Iterator<? extends T> iterator = inputs.get().iterator();
        while (iterator.hasNext()) {
          T input = iterator.next();
          terminalAttempt = !iterator.hasNext();
          attemptResults = new ConcurrentHashMap<>();
          LOGGER.debug("{}: attempting with input value {}", description, input);
          assertTrue(inputRef.compareAndSet(null, input));
          try {
            R output = mapper.apply(input);
            LOGGER.debug("{}: input {} maps to {}", description, input, output);
            assertTrue(outputRef.compareAndSet(null, output));
            try {
              if (output instanceof TestRule && outputIs.contains(OutputIs.CLASS_RULE)) {
                ((TestRule) output).apply(base, description).evaluate();
              } else {
                base.evaluate();
              }
            } catch (Throwable t) {
              throw handleTestFailure(description, t);
            } finally {
              assertTrue(outputRef.compareAndSet(output, null));
            }
          } finally {
            assertTrue(inputRef.compareAndSet(input, null));
          }
          if (!ruleApplied) {
            throw new AssertionError(TestRetryer.this.getClass().getSimpleName() + " must be annotated with both @ClassRule and @Rule");
          } else if (attemptResults.values().stream().noneMatch(Throwable.class::isInstance)) {
            LOGGER.debug("{}: successful with input value {}", description, input);
            return;
          } else {
            LOGGER.info("{}: failed with input value {}\n{}", description, input,
                    attemptResults.entrySet().stream().map(e -> {
                      String testMethodHeader = e.getKey().getMethodName() + ": ";
                      return indent(testMethodHeader + e.getValue().toString(), 4, 4 + testMethodHeader.length());
                    }).collect(joining("\n"))
            );
          }
        }
      }
    };
  }

  private Statement applyRule(Statement base, Description description) {
    ruleApplied = true;
    if (!classRuleApplied) {
      throw new AssertionError(getClass().getSimpleName() + " must be annotated with both @ClassRule and @Rule");
    }
    Statement target;
    R output = get();
    if (output instanceof TestRule && outputIs.contains(OutputIs.RULE)) {
      target = ((TestRule) output).apply(base, description);
    } else {
      target = base;
    }

    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          retryMode.evaluate(accumulatedResults.get(description), target);
          handleTestPass(description);
        } catch (AssumptionViolatedException e) {
          throw e;
        } catch (Throwable t) {
          throw handleTestFailure(description, t);
        }
      }
    };
  }

  private void handleTestPass(Description description)  {
    attemptResults.merge(description, "PASSED", TestRetryer::mergeResults);
    accumulatedResults.merge(description, "PASSED", TestRetryer::mergeResults);
  }

  private Throwable handleTestFailure(Description description, Throwable t)  {
    Throwable failure = (Throwable) attemptResults.merge(description, t, TestRetryer::mergeResults);
    Throwable merged = (Throwable) accumulatedResults.merge(description, t, TestRetryer::mergeResults);
    if (isTerminalAttempt()) {
      return merged;
    } else {
      return new AssumptionViolatedException("Failure for input parameter: " + input(), failure);
    }
  }

  static Object mergeResults(Object previous, Object current) {
    if (current instanceof Throwable && previous instanceof Throwable) {
      ((Throwable) current).addSuppressed((Throwable) previous);
    }
    return current;
  }

  public T input() {
    return requireNonNull(inputRef.get());
  }

  public R get() {
    return requireNonNull(outputRef.get());
  }

  private boolean isTerminalAttempt() {
    return terminalAttempt;
  }

  public enum OutputIs {
    RULE, CLASS_RULE
  }

  public enum RetryMode {
    ALL {
      @Override
      public void evaluate(Object result, Statement target) throws Throwable {
        target.evaluate();
      }
    },
    FAILED {
      @Override
      public void evaluate(Object result, Statement target) throws Throwable {
        if (result == null || result instanceof Throwable) {
          target.evaluate();
        } else {
          throw new AssumptionViolatedException("Test already passed");
        }
      }
    };

    public abstract void evaluate(Object result, Statement target) throws Throwable;
  }

  private static CharSequence indent(String string, Integer ... indent) {
    char[] chars = new char[max(asList(indent))];
    Arrays.fill(chars, ' ');
    String indentStrings = new String(chars);
    String[] strings = string.split("(?m)^");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < strings.length; i++) {
      if (i < indent.length) {
        sb.append(indentStrings, 0, indent[i]);
      } else {
        sb.append(indentStrings, 0, indent[indent.length - 1]);
      }
      sb.append(strings[i]);
    }
    return sb;
  }
}
