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
package org.terracotta.utilities.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.PrintStream;
import java.util.Arrays;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertThat;

/**
 * Basic tests for {@link LoggingOutputStream}.
 */
public class LoggingOutputStreamTest {

  @Test
  public void testLogback() {
    LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();

    Logger logger = LoggerFactory.getLogger("testLogger");
    {
      ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger)logger;
      logbackLogger.addAppender(appender);
      logbackLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      logbackLogger.setAdditive(false);
    }

    LoggingOutputStream loggingOutputStream = new LoggingOutputStream(logger, Level.INFO);
    try (PrintStream out = new PrintStream(loggingOutputStream)) {
      out.format("Recording to %s via %s%n", appender, logger);
    }
    assertThat(appender.list, hasSize(1));
    assertThat(appender.list.get(0).toString(),
        stringContainsInOrder(Arrays.asList("Recording to", appender.toString(), "via", logger.toString())));

    appender.list.clear();

    loggingOutputStream = new LoggingOutputStream(logger, Level.TRACE);
    try (PrintStream out = new PrintStream(loggingOutputStream)) {
      out.format("Recording to %s via %s%n", appender, logger);
    }
    assertThat(appender.list, is(empty()));
  }
}