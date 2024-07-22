/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test.matchers;

import org.hamcrest.Matcher;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.fail;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

public class ThrowsMatcherTest {

  @Test
  public void testPasses() {
    assertThat(() -> {
      throw new IllegalArgumentException();
    }, threw(instanceOf(IllegalArgumentException.class)));
  }

  @Test
  public void testFailureMessages() {
    try {
      assertThat(() -> {
        throw new IllegalStateException();
      }, threw(instanceOf(IllegalArgumentException.class)));
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertThat(e, hasMessage(stringContainsInOrder(
              "Expected:",  "task that throws an instance of java.lang.IllegalArgumentException",
              "but:", "the thrown <java.lang.IllegalStateException> is a java.lang.IllegalStateException")));
    }

    try {
      assertThat(() -> {}, threw(instanceOf(IllegalArgumentException.class)));
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertThat(e, hasMessage(stringContainsInOrder(
              "Expected:",  "task that throws an instance of java.lang.IllegalArgumentException",
              "but:", "the task completed normally")));
    }
  }

  @Test
  public void testNull() {
    try {
      assertThat(null, threw(instanceOf(IllegalArgumentException.class)));
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertThat(e, hasMessage(stringContainsInOrder(
              "Expected:",  "task that throws an instance of java.lang.IllegalArgumentException",
              "but:", "was null")));
    }
  }

  @Test @SuppressWarnings({"unchecked", "rawtypes"})
  public void testWrongType() {
    try {
      assertThat("Hello World", (Matcher) threw(instanceOf(IllegalArgumentException.class)));
      fail("Expected AssertionError");
    } catch (AssertionError e) {
      assertThat(e, hasMessage(stringContainsInOrder(
              "Expected:",  "task that throws an instance of java.lang.IllegalArgumentException",
              "but:", "was a java.lang.String (\"Hello World\")")));

    }
  }

  @Test
  public void testNonIdempotent() {
    AtomicInteger count = new AtomicInteger(0);
    try {
      assertThat(() -> {
        int i = count.incrementAndGet();
        if (i != 2) {
          throw new IllegalStateException("Unexpected count " + i);
        }
      }, threw(instanceOf(IllegalArgumentException.class)));
      fail("Expecting IllegalArgumentException");
    } catch (AssertionError e) {
      assertThat(e, hasMessage(stringContainsInOrder(
          "Expected:",
          "instance of java.lang.IllegalArgumentException",
          "Unexpected count",
          "1")));
    }
  }
}