/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeFalse;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Tests {@link ConsoleAppenderCapture} in the absence of Logback.
 * <p>
 * This class is expected to be run in an isolated JUnit/Surefire environment to
 * avoid impacting other test suites.
 */
public class ConsoleAppenderCaptureNoConsoleTest {

  /**
   * All tests in this class require the absence of a {@link ConsoleAppender} in the current
   * Logback configuration.
   */
  @BeforeClass
  public static void preCondition() {
    boolean hasConsoleAppender = ((LoggerContext)LoggerFactory.getILoggerFactory()).getLoggerList().stream()
        .flatMap(l -> stream(spliteratorUnknownSize(l.iteratorForAppenders(), ORDERED), false))
        .anyMatch(a -> (a instanceof ConsoleAppender));
    assumeFalse("Suppressed -- ConsoleAppender present", hasConsoleAppender);
  }

  @Test
  public void testCaptureNoLogback() {
    assertThat(ConsoleAppenderCapture::capture, threw(instanceOf(IllegalStateException.class)));
  }

  @SuppressWarnings({ "try" })
  @Test
  public void testCtorNoLogback() {
    try (ConsoleAppenderCapture appenderCapture = new ConsoleAppenderCapture(null)) {
      assertThat(appenderCapture.getLogs(), is(anEmptyMap()));
    }
  }
}
