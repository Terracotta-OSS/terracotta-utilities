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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/**
 * Bridge to permit variable-level use of SLF4j.
 * <p>
 * This class maintains a static reference to the {@code LoggerBridge} instances created
 * by {@link #getInstance(Logger, Level)}.
 */
public final class LoggerBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoggerBridge.class);

  private static final Map<Logger, Map<Level, LoggerBridge>> INSTANCES = new ConcurrentHashMap<>();
  private static final Map<Level, MethodHandle> IS_LEVEL_ENABLED = new ConcurrentHashMap<>();
  private static final Map<Level, MethodHandle> LOG = new ConcurrentHashMap<>();
  private static final Map<Level, MethodHandle> LOG_THROWABLE = new ConcurrentHashMap<>();

  private final MethodHandle isLevelEnabled;
  private final MethodHandle log;
  private final MethodHandle logThrowable;

  /**
   * Creates or gets the {@code LoggerBridge} instance for the delegate {@link Logger} and {@link Level}.
   *
   * @param delegate the {@code Logger} to which logging calls are delegated
   * @param level    the {@code Level} at which the returned {@code LoggingBridge} logs
   * @return a {@code LoggingBridge} instance
   * @throws AssertionError if there is an error instantiating the required {@code LoggerBridge} instance
   */
  public static LoggerBridge getInstance(Logger delegate, Level level) {
    try {
      return INSTANCES.computeIfAbsent(delegate, logger -> new ConcurrentHashMap<>())
              .computeIfAbsent(level, lvl -> new LoggerBridge(delegate, lvl));
    } catch (AssertionError e) {
      LOGGER.error("Failed to obtain " + LoggerBridge.class.getSimpleName() + " instance for Logger \""
          + delegate.getName() + "[" + level + "]\"", e);
      throw e;
    }
  }

  /**
   * Creates a {@code LoggerBridge} instance sending logging calls to the
   * designated {@link Logger} at the specified level.
   *
   * @param delegate the delegate {@code Logger}
   * @param level    the level at which the {@link #log} method records
   * @throws AssertionError if there is an error instantiating the required {@code LoggerBridge} instance
   */
  private LoggerBridge(Logger delegate, Level level) {
    requireNonNull(delegate, "delegate");
    requireNonNull(level, "level");
    this.isLevelEnabled = IS_LEVEL_ENABLED.computeIfAbsent(level, LoggerBridge::getIsLevelEnabledMethodHandle).bindTo(delegate);
    this.log = LOG.computeIfAbsent(level, LoggerBridge::getLogMethodHandle).bindTo(delegate);
    this.logThrowable = LOG_THROWABLE.computeIfAbsent(level, LoggerBridge::getLogThrowableMethodHandle).bindTo(delegate);
  }

  /*
   * Find the boolean 'is<Level>Enabled'() method.
   * @throws AssertionError constructing the require {@code MethodHandle}
   */
  private static MethodHandle getIsLevelEnabledMethodHandle(Level level) {
    String levelName = level.name();
    try {
      return publicLookup().findVirtual(Logger.class,
              "is" + levelName.substring(0, 1).toUpperCase(Locale.ROOT)
                      + levelName.substring(1).toLowerCase(Locale.ROOT) + "Enabled", methodType(Boolean.TYPE));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /*
   * Find the void '<level>'(String, Object...) method.
   * @throws AssertionError constructing the require {@code MethodHandle}
   */
  private static MethodHandle getLogMethodHandle(Level level) {
    String methodName = level.name().toLowerCase(Locale.ROOT);
    try {
      return publicLookup().findVirtual(Logger.class, methodName, methodType(void.class, String.class, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /*
   * Find the void '<level>'(String, Throwable) method.
   * @throws AssertionError constructing the require {@code MethodHandle}
   */
  private static MethodHandle getLogThrowableMethodHandle(Level level) {
    String methodName = level.name().toLowerCase(Locale.ROOT);
    try {
      return publicLookup().findVirtual(Logger.class, methodName, methodType(void.class, String.class, Throwable.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Checks if the delegate logger is active for the configured level.
   *
   * @return {@code true} if the delegate logger is configured to record events of the level
   * of this {@code LoggerBridge}
   */
  public boolean isLevelEnabled() {
    try {
      return (boolean)isLevelEnabled.invokeExact();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  /**
   * Submits a log event to the delegate logger at the level of this {@code LoggerBridge}.
   *
   * @param format    the log message format
   * @param arguments the arguments for the message
   */
  public void log(String format, Object... arguments) {
    try {
      log.invokeExact(format, arguments);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Submits a log event to the delegate logger at the level of this {@code LoggerBridge}.
   *
   * @param message the log message format
   * @param t the {@code Throwable} to log with {@code message}
   */
  public void log(String message, Throwable t) {
    try {
      logThrowable.invokeExact(message, t);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
  }
}
