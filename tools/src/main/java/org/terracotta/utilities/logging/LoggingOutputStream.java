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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

/**
 * Implements an {@code OutputStream} that forwards lines written to it to
 * a {@link Logger} at a specified level.  Bytes are accumulated by this
 * {@code OutputStream} until a {@link System#lineSeparator()} value is found;
 * once the line ending is found, the line is flushed to the logger.
 * <p>
 * Bytes written to a {@code LoggingOutputStream} instance must have been
 * encoded using {@link StandardCharsets#UTF_8 UTF-8}.  This affects proper
 * recognition of line separators and re-assembly of logged strings.
 */
public class LoggingOutputStream extends OutputStream {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoggingOutputStream.class);

  public static final int DEFAULT_BUFFER_SIZE = 4096;

  private static final Map<Level, MethodHandle> LOG_METHODS = new ConcurrentHashMap<>();

  private final MethodHandle handle;
  private final boolean twoByteLineSeparator;
  private final byte eol;
  private final byte eolLeader;

  private volatile boolean closed = false;
  private byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
  private int bufferSize = DEFAULT_BUFFER_SIZE;
  private int byteCount = 0;
  private boolean haveLeader = false;

  /**
   * Creates a new {@code LoggingOutputStream} writing to the provided logger and the
   * specified level.
   * <p>
   * Bytes written to this {@code LoggingOutputStream} must be UTF-8 encoded characters.
   * @param logger the SLF4J {@code Logger} instance to which the stream should write
   * @param level the SLF4J level at which the stream is recorded to {@code logger}
   */
  public LoggingOutputStream(Logger logger, Level level) {
    try {
      this.handle = LOG_METHODS.computeIfAbsent(level, LoggingOutputStream::getHandle).bindTo(logger);
    } catch (AssertionError e) {
      LOGGER.error("Failed to create " + LoggingOutputStream.class.getSimpleName() + " instance for Logger \""
          + logger.getName() + "[" + level + "]\"", e);
      throw e;
    }

    String lineSeparator = System.lineSeparator();
    this.twoByteLineSeparator = lineSeparator.length() == 2;
    this.eol = (byte)lineSeparator.charAt(lineSeparator.length() - 1);
    this.eolLeader = (byte)(this.twoByteLineSeparator ? lineSeparator.charAt(0) : '\0');  }

  @Override
  public void write(int b) throws IOException {
    synchronized (this) {
      checkOpen();

      if (twoByteLineSeparator && (byte)b == eolLeader) {
        haveLeader = true;        // Remember the leader; processed along with the next byte
      } else {
        if ((byte)b == eol) {
          haveLeader = false;   // Awaited EOL received; leader consumed with EOL in flush
          flushInternal();
          return;
        }

        // Non-flushed EOL or a non-EOL byte, emit held leader
        if (haveLeader) {
          appendByte(eolLeader);
          haveLeader = false;
        }
        appendByte((byte)b);
      }
    }
  }

  @Override
  public void flush() throws IOException {
    synchronized (this) {
      checkOpen();
      flushInternal();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      flushInternal();
      closed = true;
    }
  }

  private void flushInternal() throws IOException {
    if (haveLeader) {
      // Flush called before EOL appended or leader has no EOL
      appendByte(eolLeader);
      haveLeader = false;
    }

    if (byteCount == 0) {
      return;
    }

    log(new String(buffer, 0, byteCount, StandardCharsets.UTF_8));
    byteCount = 0;
  }

  private void log(String line) throws IOException {
    try {
      handle.invokeExact(line);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable throwable) {
      throw new IOException(String.format("Unexpected error calling %s: %s", handle, throwable), throwable);
    }
  }

  private void appendByte(byte b) {
    if (byteCount == bufferSize) {
      int newBufferSize = bufferSize + DEFAULT_BUFFER_SIZE;
      byte[] newBuffer = new byte[newBufferSize];
      System.arraycopy(buffer, 0, newBuffer, 0, byteCount);
      buffer = newBuffer;
      bufferSize = newBufferSize;
    }

    buffer[byteCount++] = b;
  }

  private void checkOpen() throws IOException {
    if (closed) {
      throw new IOException("stream closed");
    }
  }

  private static MethodHandle getHandle(Level level) {
    String methodName = level.name().toLowerCase(Locale.ROOT);
    try {
      return publicLookup().findVirtual(Logger.class, methodName, methodType(void.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
}
