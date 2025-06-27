/*
 * Copyright IBM Corp. 2025
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A {@link ch.qos.logback.core.read.ListAppender} implementation that auto-connects
 * itself to a designated {@link Logger}.  The {@link #close()} or {@link #stop()} method
 * should be called after use of the appender is completed to have the appender removed
 * from the designated {@code Logger}.
 *
 * <h3>Usage</h3>
 * The following demonstrates a typical usage pattern:
 * <pre>{@code
 *     try (ConnectedListAppender appender = ConnectedListAppender.newInstance(LoggerFactory.getLogger(ObservedLoggerClass.class), "WARN")) {
 *       ...
 *       // activity logging against ObservedLoggerClass
 *       ...
 *       assertThat(appender.events(), hasItem(allOf(
 *           hasProperty("level", equalTo(Level.ERROR)),
 *           hasProperty("formattedMessage", is(stringContainsInOrder("first string ", "second string")))
 *       )));
 *     }}</pre>
 */
public class ConnectedListAppender extends ch.qos.logback.core.read.ListAppender<ILoggingEvent>
    implements AutoCloseable {
  private final ch.qos.logback.classic.Logger logbackLogger;

  /**
   * Create a new {@code ConnectedListAppender} connected to the specified {@code Logger}.
   * @param logger the {@code Logger} to which the new appender is added
   * @param minimumLevel the <i>minimum</i> level of logging events to capture;
   *                     "ERROR" &gt; "WARN" &gt; "INFO" &gt; "DEBUG" &gt; "TRACE" &gt; "ALL";
   *                     if the value supplied is not recognized by Logback, "DEBUG" is used
   * @return a new {@code ConnectedListAppender} instance
   */
  public static ConnectedListAppender newInstance(Logger logger, String minimumLevel) {
    ConnectedListAppender appender = new ConnectedListAppender(logger);
    appender.setContext((LoggerContext)LoggerFactory.getILoggerFactory());

    Level minLevel = Level.toLevel(requireNonNull(minimumLevel, "minimumLevel"), Level.DEBUG);
    ThresholdFilter filter = new ThresholdFilter();
    filter.setLevel(minLevel.levelStr);
    appender.addFilter(filter);

    appender.start();

    ((ch.qos.logback.classic.Logger)logger).addAppender(appender);

    return appender;
  }

  /**
   * Create a new {@code ConnectedListAppender}.
   * @param logger the {@code Logger} to which the new appender is added
   */
  private ConnectedListAppender(Logger logger) {
    super();
    this.logbackLogger = (ch.qos.logback.classic.Logger)requireNonNull(logger, "logger");
  }

  /**
   * Detaches this appender from the logger and stops this appender.
   */
  @Override
  public void stop() {
    logbackLogger.detachAppender(this);
    super.stop();
  }

  /**
   * Detaches this appender from the logger and stops this appender.
   * This method calls {@link #stop()}.
   */
  @Override
  public void close() {
    this.stop();
  }

  /**
   * Gets a reference to the {@code List} holding the recorded {@link ILoggingEvent} instances.
   * This list may be altered, for example, cleared.
   * <p>
   * The returned list is <i>live</i> -- it is the list to which logging events are appended.
   * Accesses to the returned list while this appender is "active" should synchronize against
   * this appender.
   *
   * @return the mutable list of recorded {@code ILoggingEvent} instances
   */
  public List<ILoggingEvent> events() {
    return this.list;
  }
}
