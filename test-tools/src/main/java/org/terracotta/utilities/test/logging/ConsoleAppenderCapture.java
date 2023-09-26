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
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.filter.AbstractMatcherFilter;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Captures output associated with the current thread logged to each active {@link ConsoleAppender}
 * instance in the current logging environment.
 *
 * <h3>Implementation Details</h3>
 * This class relies on the {@link MDC} and an internally-generated {@code MDC key} to determine which
 * logging events to capture.  Due to changes in {@code MDC} inheritance (see
 * <a href="https://jira.qos.ch/browse/LOGBACK-422">LOGBACK-422 Automatic MDC inheritance with thread pools cases false data printed in the log</a>
 * and
 * <a href="https://jira.qos.ch/browse/LOGBACK-624">LOGBACK-624 MDC Adapter with configurable InheritableThreadLocal</a>),
 * only messages issued from the thread creating the {@code ConsoleAppenderCapture} instance is covered.
 * To extend coverage to other threads, use the {@link #addSupplementalThread(Thread)} or a method relying
 * on {@link MDC#getCopyOfContextMap()} and {@link MDC#setContextMap(Map)} as described in
 * <a href="https://stackoverflow.com/a/43442258/1814086">How to get back MDC "inheritance" with modern logback?</a>.
 *
 * @see <a href="https://logback.qos.ch/manual/mdc.html">Logback Documentation : Mapped Diagnostic Context</a>
 * @see MDC
 */
public class ConsoleAppenderCapture implements AutoCloseable {
  private static final String MDC_KEY_ROOT = ConsoleAppenderCapture.class.getSimpleName() + ".testId";

  private final String testId;
  private final String mdcKey = MDC_KEY_ROOT + '.' + UUID.randomUUID();
  private final Runnable removeAppenders;
  private final Map<String, ListAppender<ILoggingEvent>> appenderMap = new HashMap<>();
  /**
   * Holds the names of {@code Thread} instances to capture in addition to those carrying
   * the {@code MDC} key.
   */
  private final Set<String> supplementalThreads = new ConcurrentSkipListSet<>();

  /**
   * Creates a new {@code ConsoleAppenderCapture} instance for {@code testId}.
   * <p>
   * The returned {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed.
   * Use of a try-with-resource block is recommended: <pre>{@code
   *   try (ConsoleAppenderCapture appenderCapture = ConsoleAppenderCapture.capture("identifier")) {
   *     // invocation of code under test using ConsoleAppender
   *     List<String> logCapture = appenderCapture.getMessages(ConsoleAppenderCapture.Target.STDOUT);
   *     assertThat(logCapture, Matcher...);
   *   }
   * }</pre>
   *
   * @param testId the identifier for which events are captured
   * @return a new {@code ConsoleAppenderCapture} instance
   * @throws IllegalStateException if the current logging configuration does not append to {@link ConsoleAppender}
   * @see ConsoleAppenderCapture#ConsoleAppenderCapture(String)  ConsoleAppenderCapture
   */
  public static ConsoleAppenderCapture capture(String testId) {
    ConsoleAppenderCapture cac = new ConsoleAppenderCapture(testId);
    if (cac.appenderMap.isEmpty()) {
      cac.close();
      throw new IllegalStateException("Current logging configuration does not use " + ConsoleAppender.class.getName());
    }
    return cac;
  }

  /**
   * Creates a new {@code ConsoleAppenderCapture} instance.
   * <p>
   * The returned {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed.
   * Use of a try-with-resource block is recommended: <pre>{@code
   *   try (ConsoleAppenderCapture appenderCapture = ConsoleAppenderCapture.capture()) {
   *     // invocation of code under test using ConsoleAppender
   *     List<String> logCapture = appenderCapture.getMessages(ConsoleAppenderCapture.Target.STDOUT);
   *     assertThat(logCapture, Matcher...);
   *   }
   * }</pre>
   *
   * @return a new {@code ConsoleAppenderCapture} instance
   * @throws IllegalStateException if the current logging configuration does not append to {@link ConsoleAppender}
   * @see ConsoleAppenderCapture#ConsoleAppenderCapture(String)  ConsoleAppenderCapture
   */
  public static ConsoleAppenderCapture capture() {
    return ConsoleAppenderCapture.capture(null);
  }

  /**
   * Creates a {@code ConsoleAppenderCapture} instance which captures events sent to each {@code Logger}
   * in the current Logback configuration having a {@link ConsoleAppender}.  Only events logged by threads
   * for which the {@link MDC} contains a key generated by this constructor are captured.  If multiple
   * {@code ConsoleAppender} instances are used (uncommon), the captures from all instances having the
   * same target are aggregated.
   * <p>
   * If the current logging configuration does not append to {@code ConsoleAppender}, no capture is
   * performed and {@link #getLogs()} will return an empty map.
   * <p>
   * If a {@code Logger}, through inheritance, appends to multiple instances of {@code ConsoleAppender},
   * then multiple event captures may be observed.
   * <p>
   * The {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed -- use
   * try-with-resources.
   *
   * @param testId used as the value for the generated {@code MDC} key; if {@code null}, a value of
   *               {@code present} is used
   */
  public ConsoleAppenderCapture(String testId) {
    this.testId = (testId == null ? "present" : testId);

    MDC.put(mdcKey, this.testId);
    Runnable removeAppenders = () -> {
    };
    LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();

    /*
     * Scan for all loggers using the ConsoleAppender and add an appender to capture
     * the output for the designated ConsoleAppender target.
     */
    for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
      Iterator<Appender<ILoggingEvent>> appenderIterator = logger.iteratorForAppenders();
      while (appenderIterator.hasNext()) {
        Appender<ILoggingEvent> iLoggingEventAppender = appenderIterator.next();
        if (iLoggingEventAppender instanceof ConsoleAppender) {
          String target = ((ConsoleAppender<?>)iLoggingEventAppender).getTarget();
          ListAppender<ILoggingEvent> appender = appenderMap.computeIfAbsent(target, t -> {
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.setContext(context);

            listAppender.addFilter(new AbstractMatcherFilter<ILoggingEvent>() {
              @Override
              public FilterReply decide(ILoggingEvent iLoggingEvent) {
                if (iLoggingEvent.getMDCPropertyMap().getOrDefault(mdcKey, "").equals(ConsoleAppenderCapture.this.testId)) {
                  return FilterReply.ACCEPT;
                } else if (supplementalThreads.contains(iLoggingEvent.getThreadName())) {
                  return FilterReply.ACCEPT;
                } else {
                  return FilterReply.DENY;      // Filter only applies to 'this' Appender -- can stop processing here
                }
              }
            });

            listAppender.start();
            return listAppender;
          });
          logger.addAppender(appender);
          Runnable r = removeAppenders;
          removeAppenders = () -> {
            r.run();
            logger.detachAppender(appender);
            appender.stop();
          };
          break;
        }
      }
    }

    this.removeAppenders = removeAppenders;
  }

  /**
   * Adds the <i>name</i> of the specified {@link Thread} as a supplemental capture thread.
   * <p>
   * Only the thread name is tracked; if a non-unique thread name is provided, events from
   * all like-named threads are captured.
   * @param thread the {@code Thread} for which events are captured
   */
  public void addSupplementalThread(Thread thread) {
    this.supplementalThreads.add(requireNonNull(thread, "thread").getName());
  }

  /**
   * Removes the <i>name</i> of the specified {@link Thread} as a supplemental capture thread.
   * @param thread the {@code Thread} for which events were captured
   * @see #addSupplementalThread(Thread)
   */
  public void removeSupplementalThread(Thread thread) {
    this.supplementalThreads.remove(requireNonNull(thread, "thread").getName());
  }

  /**
   * Gets a reference to the {@code List}s into which log entries are captured.
   * <h3>Note</h3>
   * The returned {@code List} instances are <b>not</b> synchronized against concurrent access.
   *
   * @return the map of logging target to log entry list reference; the key of the
   *      map is the {@link ConsoleAppender#getTarget()} value for which
   *      {@link Target#targetName()} value may be used
   * @see Target
   */
  public Map<String, List<ILoggingEvent>> getLogs() {
    return appenderMap.entrySet().stream()
        .collect(toMap(Map.Entry::getKey, e -> e.getValue().list));
  }

  /**
   * Gets the "raw" events logged to the specified target.
   * <p>
   * This method is internally synchronized against the {@code Appender} used to capture the logging events.
   *
   * @param target the target for which log messages are to be returned
   * @return the list of events logged to {@code target}l may be empty
   */
  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  public List<ILoggingEvent> getEvents(Target target) {
    ListAppender<ILoggingEvent> appender = appenderMap.get(target.targetName());
    if (appender == null) {
      return emptyList();
    } else {
      // ListAppender.doAppend is synchronized against ListAppender instance
      synchronized (appender) {
        return new ArrayList<>(appender.list);
      }
    }
  }

  /**
   * Gets the events, in formatted string form, logged to the specified target.
   * <p>
   * This method is internally synchronized against the {@code Appender} used to capture the logging events.
   *
   * @param target the target for which log messages are to be returned
   * @return the list of messages logged to {@code target}; may be empty
   */
  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  public List<String> getMessages(Target target) {
    requireNonNull(target, "target");
    ListAppender<ILoggingEvent> appender = appenderMap.get(target.targetName());
    if (appender == null) {
      return emptyList();
    } else {
      // ListAppender.doAppend is synchronized against ListAppender instance
      synchronized (appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(toList());
      }
    }
  }

  /**
   * Gets the events, in formatted string form, as a single string with events
   * separated by {@link System#lineSeparator()}.
   * <p>
   * This method is internally synchronized against the {@code Appender} used to capture the logging events.
   *
   * @param target the target for which log messages are to be returned
   * @return a string containing all messages logged to {@code target}; may be empty
   */
  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  public String getMessagesAsString(Target target) {
    requireNonNull(target, "target");
    ListAppender<ILoggingEvent> appender = appenderMap.get(target.targetName());
    if (appender == null) {
      return "";
    } else {
      // ListAppender.doAppend is synchronized against ListAppender instance
      synchronized (appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(joining(System.lineSeparator()));
      }
    }
  }

  /**
   * Clears the captured logging events for the specified {@code Target}.
   * <p>
   * This method is internally synchronized against the {@code Appender} used to capture the logging events.
   *
   * @param target the target for which log messages are to be cleared
   */
  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  public void clear(Target target) {
    requireNonNull(target, "target");
    ListAppender<ILoggingEvent> appender = appenderMap.get(target.targetName());
    if (appender != null) {
      synchronized (appender) {
        appender.list.clear();
      }
    }
  }

  /**
   * Detaches and stops the capturing appenders.
   */
  @Override
  public void close() {
    removeAppenders.run();
    MDC.remove(mdcKey);
  }

  /**
   * Logging targets for {@link ConsoleAppender}.
   * @see ch.qos.logback.core.joran.spi.ConsoleTarget
   */
  public enum Target {
    /** {@code System.out} target. */
    STDOUT(ConsoleTarget.SystemOut),
    /** {@code System.err} target. */
    STDERR(ConsoleTarget.SystemErr);

    private final ConsoleTarget target;

    Target(ConsoleTarget target) {
      this.target = target;
    }

    public String targetName() {
      return this.target.getName();
    }
  }
}
