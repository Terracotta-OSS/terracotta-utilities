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

import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Bridge to permit variable-level use of SLF4j.
 * <p>
 * This class maintains a static reference to the {@code LoggerBridge} instances created
 * by {@link #getInstance(Logger, Level)}.
 */
public final class LoggerBridge {
  private static final Map<Map.Entry<Logger, Level>, LoggerBridge> INSTANCES = new HashMap<>();

  private final Logger delegate;
  private final Level level;
  private final MethodHandle isLevelEnabled;
  private final MethodHandle log;
  private final MethodHandle logThrowable;

  /**
   * Creates or gets the {@code LoggerBridge} instance for the delegate {@link Logger} and {@link Level}.
   *
   * @param delegate the {@code Logger} to which logging calls are delegated
   * @param level    the {@code Level} at which the returned {@code LoggingBridge} logs
   * @return a {@code LoggingBridge} instance
   */
  public static LoggerBridge getInstance(Logger delegate, Level level) {
    return INSTANCES.computeIfAbsent(new AbstractMap.SimpleImmutableEntry<>(delegate, level),
        e -> new LoggerBridge(e.getKey(), e.getValue()));
  }

  /**
   * Creates a {@code LoggerBridge} instance sending logging calls to the
   * designated {@link Logger} at the specified level.
   *
   * @param delegate the delegate {@code Logger}
   * @param level    the level at which the {@link #log} method records
   */
  private LoggerBridge(Logger delegate, Level level) {
    this.delegate = requireNonNull(delegate, "delegate");
    this.level = requireNonNull(level, "level");

    String levelName = level.name().toLowerCase(Locale.ROOT);
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    MethodType type;

    /*
     * Find the boolean 'is<Level>Enabled'() method.
     */
    MethodHandle isLevelEnabled;
    type = MethodType.methodType(boolean.class);
    try {
      isLevelEnabled = lookup.findVirtual(Logger.class,
          "is" + levelName.substring(0, 1).toUpperCase(Locale.ROOT) + levelName.substring(1) + "Enabled", type);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      isLevelEnabled = null;
      delegate.error("Unable to resolve '{} {}({})' method on {}; will log at INFO level",
          type.returnType(), levelName, type.parameterList(), Logger.class, e);
    }
    this.isLevelEnabled = isLevelEnabled;

    /*
     * Find the void '<level>'(String, Object...) method.
     */
    MethodHandle log;
    type = MethodType.methodType(void.class, String.class, Object[].class);
    try {
      log = lookup.findVirtual(Logger.class, levelName, type);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log = null;
      delegate.error("Unable to resolve '{} {}({})' method on {}; will log at INFO level",
          type.returnType(), levelName, type.parameterList(), Logger.class, e);
    }
    this.log = log;

    /*
     * Find the void '<level>'(String, Throwable) method.
     */
    MethodHandle logThrowable;
    type = MethodType.methodType(void.class, String.class, Throwable.class);
    try {
      logThrowable = lookup.findVirtual(Logger.class, levelName, type);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      logThrowable = null;
      delegate.error("Unable to resolve '{} {}({})' method on {}; will log at INFO level",
          type.returnType(), levelName, type.parameterList(), Logger.class, e);
    }
    this.logThrowable = logThrowable;
  }

  /**
   * Checks if the delegate logger is active for the configured level.
   *
   * @return {@code true} if the delegate logger is configured to record events of the level
   * of this {@code LoggerBridge}
   */
  public boolean isLevelEnabled() {
    if (isLevelEnabled != null) {
      try {
        return (boolean)isLevelEnabled.invokeExact(delegate);
      } catch (Throwable throwable) {
        delegate.error("Failed to call {}; presuming {} is enabled", isLevelEnabled, level, throwable);
        return true;
      }
    } else {
      return delegate.isInfoEnabled();
    }
  }

  /**
   * Submits a log event to the delegate logger at the level of this {@code LoggerBridge}.
   * If the virtual call to the log method fails, the log event is recorded at the {@code INFO}
   * level.
   *
   * @param format    the log message format
   * @param arguments the arguments for the message
   */
  public void log(String format, Object... arguments) {
    if (log != null) {
      try {
        log.invokeExact(delegate, format, arguments);
      } catch (Throwable throwable) {
        delegate.error("Failed to call {}; logging at INFO level", log, throwable);
        delegate.info(format, arguments);
      }
    } else {
      delegate.info(format, arguments);
    }
  }

  /**
   * Submits a log event to the delegate logger at the level of this {@code LoggerBridge}.
   * If the virtual call to the log method fails, the log event is recorded at the {@code INFO}
   * level.
   *
   * @param message the log message format
   * @param t the {@code Throwable} to log with {@code message}
   */
  public void log(String message, Throwable t) {
    if (logThrowable != null) {
      try {
        logThrowable.invokeExact(delegate, message, t);
      } catch (Throwable throwable) {
        delegate.error("Failed to call {}; logging at INFO level", logThrowable, throwable);
        delegate.info(message, t);
      }
    } else {
      delegate.info(message, t);
    }
  }
}
