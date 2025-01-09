/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Basic tests for {@link LoggerBridge}.
 * This class relies on Logback ...
 */
public class LoggerBridgeTest {

  @Test
  public void testInstanceTest() {
    Logger testLogger = LoggerFactory.getLogger("testLogger");
    Logger alternateLogger = LoggerFactory.getLogger("alternateLogger");

    LoggerBridge infoTestBridge = LoggerBridge.getInstance(testLogger, Level.INFO);
    assertThat(LoggerBridge.getInstance(testLogger, Level.INFO), is(sameInstance(infoTestBridge)));
    assertThat(LoggerBridge.getInstance(testLogger, Level.WARN), is(not(sameInstance(infoTestBridge))));

    assertThat(LoggerBridge.getInstance(alternateLogger, Level.INFO), is(not(sameInstance(infoTestBridge))));
  }

  @Test
  public void testLogging() {
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

    LoggerBridge infoBridge = LoggerBridge.getInstance(logger, Level.INFO);
    assertThat(infoBridge.isLevelEnabled(), is(true));
    infoBridge.log("Message with {} substitution", "a");
    assertThat(appender.list, hasSize(1));
    ILoggingEvent loggingEvent = appender.list.get(0);
    assertThat(loggingEvent.toString(), containsString("Message with a substitution"));
    assertThat(loggingEvent.getLevel(), is(ch.qos.logback.classic.Level.INFO));
    assertThat(loggingEvent.getThrowableProxy(), is(nullValue()));

    appender.list.clear();

    LoggerBridge traceBridge = LoggerBridge.getInstance(logger, Level.TRACE);
    assertThat(traceBridge.isLevelEnabled(), is(false));
    traceBridge.log("Message with {} substitution", "a");
    assertThat(appender.list, is(empty()));

    appender.list.clear();

    infoBridge.log("Message with exception", new IllegalArgumentException());
    assertThat(appender.list, hasSize(1));
    loggingEvent = appender.list.get(0);
    assertThat(loggingEvent.toString(), containsString("Message with exception"));
    assertThat(loggingEvent.getThrowableProxy(), is(notNullValue()));
    assertThat(loggingEvent.getThrowableProxy().getClassName(), is(IllegalArgumentException.class.getName()));
  }
}