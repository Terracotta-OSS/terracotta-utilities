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
package org.terracotta.utilities.test.matchers;

import org.hamcrest.Matcher;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.terracotta.utilities.test.matchers.Eventually.within;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

public class EventuallyTest {

  @Test
  public void testImmediateTrue() {
    assertThat(() -> true, within(Duration.ZERO).is(true));
  }

  @Test
  public void testImmediateFalse() {
    assertThat(() -> assertThat(() -> false, within(Duration.ZERO).is(true)),
      threw(both(any(AssertionError.class)).and(hasMessage(stringContainsInOrder(
              "Expected:",  "<true> (within <PT0S>)", "but:", "was <false>")))));
  }

  @Test
  public void testConditionGetsMet() {
    long soon = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10);
    assertThat(System::nanoTime, within(Duration.ofSeconds(10)).matches(greaterThan(soon)));
  }

  @Test
  public void testMatcherReuse() {
    Matcher<Supplier<? super Integer>> matches = within(Duration.ZERO).is(1);

    assertThat(() -> 1, matches);

    assertThat(() -> assertThat(() -> 2, matches),
            threw(both(any(AssertionError.class)).and(hasMessage(containsString("was <2>")))));
    assertThat(() -> assertThat(() -> 3, matches),
            threw(both(any(AssertionError.class)).and(hasMessage(containsString("was <3>")))));
  }

  @Test @SuppressWarnings({"unchecked", "rawtypes"})
  public void testTimeoutReuse() {
    Eventually.Timeout immediately = within(Duration.ZERO);

    assertThat(() -> 1, immediately.is(1));
    assertThat(() -> 2, immediately.is(2));
    assertThat(() -> assertThat(() -> 4, immediately.is(3)), threw(both(any(AssertionError.class)).and(hasMessage(stringContainsInOrder(
            "Expected:",  "<3> (within <PT0S>)",
            "but:", "was <4>")))));
    assertThat(() -> assertThat("banana", (Matcher) immediately.is(3)), threw(both(any(AssertionError.class)).and(hasMessage(stringContainsInOrder(
            "Expected:",  "<3> (within <PT0S>)",
            "but:", "was \"banana\"")))));
  }

  @Test
  public void testCleanlyForMultipleAssertionsPassing() {
    Eventually.Timeout withinTenMillis = within(Duration.ofMillis(10));

    long soon = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10);

    withinTenMillis.runsCleanly(() -> {
      assertThat(System.nanoTime(), greaterThan(soon));
      assertThat(1, is(1));
    });
  }

  @Test
  public void testCleanlyForMultipleAssertionsFailing() {
    Eventually.Timeout withinTenMillis = within(Duration.ofMillis(20));

    long soon = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10);

    assertThat(() -> withinTenMillis.runsCleanly(() -> {
      assertThat(System.nanoTime(), greaterThan(soon));
      assertThat(1, is(2));
    }), threw(both(any(AssertionError.class)).and(hasMessage(stringContainsInOrder(
            "Expected:",  "is <2>", "but:", "was <1>")))));
  }


}