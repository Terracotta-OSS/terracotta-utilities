/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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

import org.hamcrest.Matcher;
import org.hamcrest.collection.IsArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.terracotta.utilities.test.WaitForAssert.assertThatEventually;

/**
 * @author Clifford W. Johnson
 */
public class WaitForAssertTest {

  @Rule
  public final TestName testName = new TestName();

  @Test
  public void testSuccessfulAssertion() throws Exception {
    assertThatEventually(() -> true, is(true)).within(Duration.ZERO);
  }

  @Test
  public void testSuccessfulAssertionWithOnFailure() throws Exception {
    AtomicBoolean onFailureCalled = new AtomicBoolean(false);
    assertThatEventually(() -> true, is(true))
        .onFailure(t -> onFailureCalled.set(true))
        .within(Duration.ZERO);
    assertFalse(onFailureCalled.get());
  }

  @Test
  public void testUnsuccessfulAssertion() throws Exception {
    long startTime = System.nanoTime();
    long testDuration;
    try {
      assertThatEventually(() -> false, is(true)).within(Duration.ZERO);
      testDuration = System.nanoTime() - startTime;
      fail("TimeoutException expected");
    } catch (TimeoutException e) {
      testDuration = System.nanoTime() - startTime;
      assertThat(e.getSuppressed(), is(array(instanceOf(AssertionError.class))));
    }
    System.out.format("[%s] Test duration = %,dns%n", testName.getMethodName(), testDuration);
  }

  @Test
  public void testUnsuccessfulAssertionLongWait() throws Exception {
    long startTime = System.nanoTime();
    long testDuration;
    try {
      assertThatEventually(() -> false, is(true)).within(Duration.ofSeconds(5));
      testDuration = System.nanoTime() - startTime;
      fail("TimeoutException expected");
    } catch (TimeoutException e) {
      testDuration = System.nanoTime() - startTime;
      assertThat(e.getSuppressed(), is(array(instanceOf(AssertionError.class))));
    }
    System.out.format("[%s] Test duration = %,dns%n", testName.getMethodName(), testDuration);
  }

  @Test
  public void testUnsuccessfulAssertionWithOnFailure() throws Exception {
    AtomicReference<Throwable> observedFailure = new AtomicReference<>();
    long startTime = System.nanoTime();
    long testDuration;
    try {
      assertThatEventually(() -> false, is(true))
          .onFailure(observedFailure::set)
          .within(Duration.ZERO);
      testDuration = System.nanoTime() - startTime;
      fail("TimeoutException expected");
    } catch (TimeoutException e) {
      testDuration = System.nanoTime() - startTime;
      assertThat(e.getSuppressed(), is(array(instanceOf(AssertionError.class))));
    }

    assertNotNull(observedFailure.get());
    assertThat(observedFailure.get(), is(instanceOf(AssertionError.class)));

    System.out.format("[%s] Test duration = %,dns%n", testName.getMethodName(), testDuration);
  }

  @Test
  public void testUnsuccessfulAssertionWithMultipleOnFailure() throws Exception {
    AtomicReference<Throwable> observedFailure1 = new AtomicReference<>();
    AtomicReference<Throwable> observedFailure2 = new AtomicReference<>();
    AtomicReference<Throwable> observedFailure3 = new AtomicReference<>();
    long startTime = System.nanoTime();
    long testDuration;
    try {
      assertThatEventually(() -> false, is(true))
          .onFailure(newValue -> {
            observedFailure1.set(newValue);
            throw new RuntimeException("observedFailure1");
          })
          .onFailure(observedFailure2::set)
          .onFailure(newValue -> {
            observedFailure3.set(newValue);
            throw new RuntimeException("observedFailure3");
          })
          .within(Duration.ZERO);
      testDuration = System.nanoTime() - startTime;
      fail("TimeoutException expected");
    } catch (TimeoutException e) {
      testDuration = System.nanoTime() - startTime;
      Throwable[] suppressed = e.getSuppressed();
      assertThat(suppressed, is(array(instanceOf(AssertionError.class), instanceOf(RuntimeException.class))));
      assertThat(suppressed[1].getSuppressed(), is(array(instanceOf(RuntimeException.class))));
    }

    assertNotNull(observedFailure1.get());
    assertThat(observedFailure1.get(), is(instanceOf(AssertionError.class)));

    assertNotNull(observedFailure2.get());
    assertThat(observedFailure2.get(), is(instanceOf(AssertionError.class)));

    assertNotNull(observedFailure3.get());
    assertThat(observedFailure3.get(), is(instanceOf(AssertionError.class)));

    System.out.format("[%s] Test duration = %,dns%n", testName.getMethodName(), testDuration);
  }

  @SuppressWarnings("varargs")
  @SafeVarargs
  private final <T> IsArray<T> array(Matcher<? super T>... elementMatchers) {
    return IsArray.array(elementMatchers);    // varargs
  }
}