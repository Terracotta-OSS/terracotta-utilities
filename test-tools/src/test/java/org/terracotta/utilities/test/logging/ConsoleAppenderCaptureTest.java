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
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Pattern;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.stringContainsInOrder;

/**
 * Tests for {@link ConsoleAppenderCapture}.
 */
public class ConsoleAppenderCaptureTest {

  private static Appender<ILoggingEvent> appender;

  @Rule
  public final TestName testName = new TestName();

  /**
   * Configure the appenders needed for the tests in this
   */
  @BeforeClass
  public static void configureLogger() {
    ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();
    assertThat("iLoggerFactory is not a LoggerContext; examine 'stderr' for messages from SLF4J",
        iLoggerFactory, is(instanceOf(LoggerContext.class)));
    LoggerContext loggerContext = (LoggerContext)iLoggerFactory;

    /*
     * Determine if a ConsoleAppender fpr STDOUT is already present in the ROOT logger.  (Tests
     * in this class rely on non-hierarchical loggers so the ROOT logger is the only parent.)
     */
    ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    boolean hasConsoleAppender =
        stream(spliteratorUnknownSize(rootLogger.iteratorForAppenders(), ORDERED), false)
            .filter(a -> (a instanceof ConsoleAppender))
            .map(a -> (ConsoleAppender<?>)a)
            .anyMatch(a -> a.getTarget().equals(ConsoleAppenderCapture.Target.STDOUT.targetName()));

    /*
     * If the ROOT logger has no STDOUT ConsoleAppender, create one for use by the tests.
     */
    if (!hasConsoleAppender) {
      PatternLayoutEncoder encoder = new PatternLayoutEncoder();
      encoder.setContext(loggerContext);
      encoder.setCharset(StandardCharsets.UTF_8);
      encoder.setPattern("%-5level [%thread]: %logger{0} - %message%n");
      encoder.start();

      ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
      consoleAppender.setContext(loggerContext);
      consoleAppender.setTarget(ConsoleAppenderCapture.Target.STDOUT.targetName());
      consoleAppender.setEncoder(encoder);
      consoleAppender.setName(ConsoleAppenderCaptureTest.class.getSimpleName());
      consoleAppender.start();

      appender = consoleAppender;

    } else {
      System.out.format("%s logger already has ConsoleAppender instance; skipping addition%n", Logger.ROOT_LOGGER_NAME);
      appender = null;
    }

  }

  @AfterClass
  public static void deconfigureLogger() {
    if (appender != null) {
      appender.stop();
    }
  }


  @SuppressWarnings({ "try" })
  @Test
  public void testSingleThread() {
    String methodName = testName.getMethodName();
    Logger logger = LoggerFactory.getLogger(methodName);

    try (CloseableResource ignored2 = usingLevel(logger, Level.INFO)) {
      try (AppenderWrapper ignored1 = new AppenderWrapper(logger, appender)) {
        try (ConsoleAppenderCapture capture = ConsoleAppenderCapture.capture()) {
          logger.info("sample line");
          logger.trace("traced line");

          try (CloseableResource ignored = usingLevel(logger, Level.TRACE)) {
            logger.trace("another traced line");
          }

          List<String> logMessages = capture.getMessages(ConsoleAppenderCapture.Target.STDOUT);
          assertThat(logMessages, contains(
              containsString("sample line"),
              containsString("another traced line")));

          List<String> errMessages = capture.getMessages(ConsoleAppenderCapture.Target.STDERR);
          assertThat(errMessages, is(empty()));
        }
      }
    }
  }

  @SuppressWarnings({ "try" })
  @Test
  public void testSingleThreadRaw() {
    String methodName = testName.getMethodName();
    Logger logger = LoggerFactory.getLogger(methodName);

    try (CloseableResource ignored2 = usingLevel(logger, Level.INFO)) {
      try (AppenderWrapper ignored1 = new AppenderWrapper(logger, appender)) {
        try (ConsoleAppenderCapture capture = ConsoleAppenderCapture.capture()) {
          logger.info("sample line");
          logger.trace("traced line");

          try (CloseableResource ignored = usingLevel(logger, Level.TRACE)) {
            logger.trace("another traced line");
          }

          List<ILoggingEvent> logMessages = capture.getEvents(ConsoleAppenderCapture.Target.STDOUT);
          assertThat(logMessages, contains(
              allOf(hasProperty("formattedMessage", containsString("sample line")),
                  hasProperty("level", is(LogLevel.INFO.logbackLevel))),
              allOf(hasProperty("formattedMessage", containsString("another traced line")),
                  hasProperty("level", is(LogLevel.TRACE.logbackLevel)))));

          List<String> errMessages = capture.getMessages(ConsoleAppenderCapture.Target.STDERR);
          assertThat(errMessages, is(empty()));
        }
      }
    }
  }

  @SuppressWarnings({ "try" })
  @Test
  public void testClear() {
    String methodName = testName.getMethodName();
    Logger logger = LoggerFactory.getLogger(methodName);

    try (CloseableResource ignored2 = usingLevel(logger, Level.INFO)) {
      try (AppenderWrapper ignored1 = new AppenderWrapper(logger, appender)) {
        try (ConsoleAppenderCapture capture = ConsoleAppenderCapture.capture()) {
          logger.info("sample line");
          logger.trace("traced line");

          try (CloseableResource ignored = usingLevel(logger, Level.TRACE)) {
            logger.trace("another traced line");
          }

          List<ILoggingEvent> logMessages = capture.getEvents(ConsoleAppenderCapture.Target.STDOUT);
          assertThat(logMessages, contains(
              allOf(hasProperty("formattedMessage", containsString("sample line")),
                  hasProperty("level", is(LogLevel.INFO.logbackLevel))),
              allOf(hasProperty("formattedMessage", containsString("another traced line")),
                  hasProperty("level", is(LogLevel.TRACE.logbackLevel)))));

          List<String> errMessages = capture.getMessages(ConsoleAppenderCapture.Target.STDERR);
          assertThat(errMessages, is(empty()));

          capture.clear(ConsoleAppenderCapture.Target.STDOUT);
          assertThat(capture.getEvents(ConsoleAppenderCapture.Target.STDOUT), is(empty()));

          logger.info("one more line");
          assertThat(capture.getEvents(ConsoleAppenderCapture.Target.STDOUT),
              contains(hasProperty("formattedMessage", is("one more line"))));
        }
      }
    }
  }

  @SuppressWarnings({ "try" })
  @Test
  public void testGetMessageAsString() {
    String methodName = testName.getMethodName();
    Logger logger = LoggerFactory.getLogger(methodName);

    try (CloseableResource ignored2 = usingLevel(logger, Level.INFO)) {
      try (AppenderWrapper ignored1 = new AppenderWrapper(logger, appender)) {
        try (ConsoleAppenderCapture capture = ConsoleAppenderCapture.capture()) {
          logger.info("sample line");
          logger.trace("traced line");

          try (CloseableResource ignored = usingLevel(logger, Level.TRACE)) {
            logger.trace("another traced line");
          }

          String logMessages = capture.getMessagesAsString(ConsoleAppenderCapture.Target.STDOUT);
          assertThat(logMessages, stringContainsInOrder("sample line", "another traced line"));
          String[] lines = logMessages.split(Pattern.quote(System.lineSeparator()));
          assertThat(lines, arrayContaining(
              containsString("sample line"),
              containsString("another traced line")));

          String errMessages = capture.getMessagesAsString(ConsoleAppenderCapture.Target.STDERR);
          assertThat(errMessages, is(emptyString()));
        }
      }
    }
  }

  /**
   * Tests {@link ConsoleAppenderCapture} with two threads capturing console events.
   * The test assures that each thread captures only its own events.
   */
  @SuppressWarnings("try")
  @Test
  public void testTwoThreadsIndependent() throws Exception {
    int threadCount = 2;
    String methodName = testName.getMethodName();
    Logger logger = LoggerFactory.getLogger(methodName);
    ((ch.qos.logback.classic.Logger)logger).setLevel(LogLevel.INFO.logbackLevel);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try (AppenderWrapper ignored = new AppenderWrapper(logger, appender)) {

      List<Future<?>> trials = new ArrayList<>();
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      for (int i = 0; i < threadCount; i++) {
        String testId = methodName + i;
        trials.add(executor.submit(() -> {
          String threadId = Thread.currentThread().getName();

          List<ILoggingEvent> logMessages;
          List<ILoggingEvent> errMessages;
          try (ConsoleAppenderCapture capture = ConsoleAppenderCapture.capture(testId)) {
            barrier.await();

            logger.info("sample line");
            logger.trace("traced line");
            logger.info("another info line");

            logMessages = capture.getLogs().get(ConsoleAppenderCapture.Target.STDOUT.targetName());
            errMessages = capture.getLogs().get(ConsoleAppenderCapture.Target.STDERR.targetName());

          } catch (BrokenBarrierException | InterruptedException e) {
            throw new AssertionError(e);
          }

          assertThat(logMessages, contains(
              allOf(hasProperty("threadName", is(threadId)),
                  hasProperty("formattedMessage", containsString("sample line"))),
              allOf(hasProperty("threadName", is(threadId)),
                  hasProperty("formattedMessage", containsString("another info line")))
          ));
          assertThat(errMessages, is(nullValue()));
        }));
      }

      for ( Future<?> trial : trials ) {
        try {
          trial.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof Error) {
            throw ((Error)cause);
          } else if (cause instanceof RuntimeException) {
            throw  ((RuntimeException)cause);
          } else {
            throw new AssertionError(cause);
          }
        }
      }

    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Tests {@link ConsoleAppenderCapture} with multiple threads all captured by the same
   * {@link ConsoleAppenderCapture} instance relying on use of
   * {@link ConsoleAppenderCapture#addSupplementalThread(Thread)}.
   */
  @SuppressWarnings("try")
  @Test
  public void testTwoThreadsNonInherited() throws Exception {
    int threadCount = 2;
    String methodName = testName.getMethodName();
    String mainThreadName = Thread.currentThread().getName();
    Logger logger = LoggerFactory.getLogger(methodName);
    ((ch.qos.logback.classic.Logger)logger).setLevel(LogLevel.INFO.logbackLevel);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
    try (ConsoleAppenderCapture capture = new ConsoleAppenderCapture(methodName)) {
      try (AppenderWrapper ignored = new AppenderWrapper(logger, appender)) {

        List<Future<?>> trials = new ArrayList<>();
        Map<String, String> taskToThreadNameMap = new ConcurrentHashMap<>();

        // Add threads for which capture is performed
        for (int i = 0; i < threadCount; i++) {
          String taskId = methodName + i;
          logger.info("Creating worker {}", taskId);
          trials.add(executor.submit(() -> {
            Thread currentThread = Thread.currentThread();
            taskToThreadNameMap.put(taskId, currentThread.getName());
            capture.addSupplementalThread(currentThread);
            try {
              logger.info("Task {} info message", taskId);
              logger.debug("Task {} debug message", taskId);
              logger.info("Task {} another info message", taskId);
            } finally {
              capture.removeSupplementalThread(currentThread);
              logger.info("Task {} message following removal", taskId);
            }
          }));
        }

        // Add a thread for which capture is *NOT* performed
        {
          String taskId = methodName + threadCount;
          logger.info("Creating worker {}", taskId);
          trials.add(executor.submit(() -> {
            taskToThreadNameMap.put(taskId, Thread.currentThread().getName());
            logger.info("Task {} info message", taskId);
            logger.debug("Task {} debug message", taskId);
            logger.info("Task {} another info message", taskId);
          }));
        }
        logger.info("Finished worker creation");

        for (Future<?> trial : trials) {
          try {
            trial.get();
          } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) {
              throw ((Error)cause);
            } else if (cause instanceof RuntimeException) {
              throw  ((RuntimeException)cause);
            } else {
              throw new AssertionError(cause);
            }
          }
        }

        List<ILoggingEvent> loggingEvents = capture.getEvents(ConsoleAppenderCapture.Target.STDOUT);
        String taskName0 = methodName + 0;
        String taskName1 = methodName + 1;
        String taskNameN = methodName + threadCount;
        assertThat(loggingEvents, allOf(
            // Messages emitted by main thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Creating worker " + taskName0))),
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Creating worker " + taskName1))),
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Creating worker " + taskNameN))),
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Finished worker creation")))),
            // Messages emitted by first task thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName0))),
                    hasProperty("formattedMessage", is("Task " + taskName0 + " info message"))),
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName0))),
                    hasProperty("formattedMessage", is("Task " + taskName0 + " another info message")))),
            // Messages emitted by second task thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName1))),
                    hasProperty("formattedMessage", is("Task " + taskName1 + " info message"))),
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName1))),
                    hasProperty("formattedMessage", is("Task " + taskName1 + " another info message"))))
            )
        );
        // No messages captured by third task thread
        assertThat(loggingEvents, everyItem(hasProperty("threadName",
            is(not(taskToThreadNameMap.get(taskNameN))))));
        // No messages _after_ removal of supplementary thread
        assertThat(loggingEvents, everyItem(hasProperty("formattedMessage",
            not(containsString("message following removal")))));

      }
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Tests {@link ConsoleAppenderCapture} with multiple threads all captured by the same
   * {@link ConsoleAppenderCapture} instance relying on MDC inheritance through
   * {@link MDC#getCopyOfContextMap()} and {@link MDC#setContextMap(Map)}.
   */
  @SuppressWarnings("try")
  @Test
  public void testTwoThreadsInherited() throws Exception {
    int threadCount = 2;
    String methodName = testName.getMethodName();
    String mainThreadName = Thread.currentThread().getName();
    Logger logger = LoggerFactory.getLogger(methodName);
    ((ch.qos.logback.classic.Logger)logger).setLevel(LogLevel.INFO.logbackLevel);

    try (ConsoleAppenderCapture capture = new ConsoleAppenderCapture(methodName)) {
      try (AppenderWrapper ignored = new AppenderWrapper(logger, appender)) {

        Map<String, String> mdc = MDC.getCopyOfContextMap();

        ThreadGroup group = new ThreadGroup(methodName);

        List<Future<?>> trials = new ArrayList<>();
        Map<String, String> taskToThreadNameMap = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
          String taskId = methodName + i;
          logger.info("Creating worker {}", taskId);
          FutureTask<Void> task = new FutureTask<>(() -> {
            MDC.setContextMap(mdc);
            taskToThreadNameMap.put(taskId, Thread.currentThread().getName());
            logger.info("Task {} info message", taskId);
            logger.debug("Task {} debug message", taskId);
            logger.info("Task {} another info message", taskId);
          }, null);
          Thread thread = new Thread(group, task, taskId);
          thread.setDaemon(true);
          thread.start();
          trials.add(task);
        }
        logger.info("Finished worker creation");

        for (Future<?> trial : trials) {
          try {
            trial.get();
          } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) {
              throw ((Error)cause);
            } else if (cause instanceof RuntimeException) {
              throw  ((RuntimeException)cause);
            } else {
              throw new AssertionError(cause);
            }
          }
        }

        List<ILoggingEvent> loggingEvents = capture.getEvents(ConsoleAppenderCapture.Target.STDOUT);
        String taskName0 = methodName + 0;
        String taskName1 = methodName + 1;
        assertThat(loggingEvents, allOf(
            // Messages emitted by main thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Creating worker " + taskName0))),
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Creating worker " + taskName1))),
                allOf(hasProperty("threadName", is(mainThreadName)),
                    hasProperty("formattedMessage", is("Finished worker creation")))),
            // Messages emitted by first task thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName0))),
                    hasProperty("formattedMessage", is("Task " + taskName0 + " info message"))),
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName0))),
                    hasProperty("formattedMessage", is("Task " + taskName0 + " another info message")))),
            // Messages emitted by second task thread
            containsInRelativeOrder(
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName1))),
                    hasProperty("formattedMessage", is("Task " + taskName1 + " info message"))),
                allOf(hasProperty("threadName", is(taskToThreadNameMap.get(taskName1))),
                    hasProperty("formattedMessage", is("Task " + taskName1 + " another info message"))))
            )
        );

      }
    }
  }

  private static CloseableResource usingLevel(Logger logger, @SuppressWarnings("SameParameterValue") Level level) {
    ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger)logger;
    ch.qos.logback.classic.Level oldLevel = logbackLogger.getLevel();
    logbackLogger.setLevel(LogLevel.toLogback(level));
    return () -> logbackLogger.setLevel(oldLevel);
  }

  @FunctionalInterface
  private interface CloseableResource extends AutoCloseable {
    @Override
    void close();
  }

  private static class AppenderWrapper implements AutoCloseable {
    private final ch.qos.logback.classic.Logger logger;
    private final Appender<ILoggingEvent> appender;
    private final boolean isAdditive;

    public AppenderWrapper(Logger logger, Appender<ILoggingEvent> appender) {
      this.logger = (ch.qos.logback.classic.Logger)logger;
      this.appender = appender;
      this.isAdditive = this.logger.isAdditive();

      if (this.appender != null) {
        this.logger.setAdditive(true);        // Permit accumulation if adding local ConsoleAppender
        this.logger.addAppender(appender);
      }
    }

    @Override
    public void close() {
      if (this.appender != null) {
        logger.detachAppender(appender);
        logger.setAdditive(isAdditive);
      }
    }
  }

  /**
   * Maps between {@link Level org.slf4j.event.Level} and {@link ch.qos.logback.classic.Level}.
   */
  @SuppressWarnings("unused")
  private enum LogLevel {
    ERROR(Level.ERROR, ch.qos.logback.classic.Level.ERROR),
    WARN(Level.WARN, ch.qos.logback.classic.Level.WARN),
    INFO(Level.INFO, ch.qos.logback.classic.Level.INFO),
    DEBUG(Level.DEBUG, ch.qos.logback.classic.Level.DEBUG),
    TRACE(Level.TRACE, ch.qos.logback.classic.Level.TRACE)
    // SLF4J does not have a mapping for ch.qos.logback.classic.Level.OFF
    // SLF4J does not have a mapping for ch.qos.logback.classic.Level.ALL
    ;

    private final Level slf4jLevel;
    private final ch.qos.logback.classic.Level logbackLevel;

    LogLevel(Level slf4jLevel, ch.qos.logback.classic.Level logbackLevel) {
      this.slf4jLevel = slf4jLevel;
      this.logbackLevel = logbackLevel;
    }

    /**
     * Converts an {@link Level org.slf4j.event.Level} to a {@link ch.qos.logback.classic.Level}.
     * @param slf4jLevel the SLF4J level to convert
     * @return the corresponding Logback level; if {@code slf4jLevel} is not found,
     *      {@link ch.qos.logback.classic.Level#DEBUG} is returned
     */
    public static ch.qos.logback.classic.Level toLogback(Level slf4jLevel) {
      for (LogLevel level : values()) {
        if (level.slf4jLevel == slf4jLevel) {
          return level.logbackLevel;
        }
      }
      return ch.qos.logback.classic.Level.DEBUG;
    }

    /**
     * Converts a {@link ch.qos.logback.classic.Level} to an {@link Level org.slf4j.event.Level}.
     * @param logbackLevel the Logback level to convert
     * @return the corresponding SLF4J level; if the {@code logbackLevel} is not found,
     *      {@link Level#DEBUG org.slf4j.event.Level.DEBUG} is returned
     */
    public static Level toSlf4J(ch.qos.logback.classic.Level logbackLevel) {
      for (LogLevel level : values()) {
        if (level.logbackLevel.toInt() == logbackLevel.toInt()) {
          return level.slf4jLevel;
        }
      }
      return Level.DEBUG;
    }
  }
}