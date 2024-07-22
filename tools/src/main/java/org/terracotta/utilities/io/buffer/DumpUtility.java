/*
 * Copyright 2020-2023 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.io.buffer;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.IdentityHashMap;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Provides methods to dump {@link java.nio.Buffer} instances.
 * <p>
 * Each instance of this class tracks the buffers it dumps and does not dump the
 * same buffer more than once.  Because of this, an instance of this class should
 * not be retained beyond actual need to avoid interfering with garbage collection
 * of the dumped buffers.
 *
 * <h3>Use of Reflection</h3>
 * This class uses reflection to access fields internal to {@code Buffer} implementations.
 * Operation of the following methods <i>requires</i> this access:
 * <ul>
 *   <li>{@link #dumpBuffer(IntBuffer)}</li>
 *   <li>{@link #dumpBuffer(IntBuffer, PrintStream)}</li>
 *   <li>{@link #dumpBuffer(LongBuffer)}</li>
 *   <li>{@link #dumpBuffer(LongBuffer, PrintStream)}</li>
 * </ul>
 * Under Java 17+, the {@code --add-opens java.base/java.nio=ALL-UNNAMED} option <b>must</b> be added to
 * the JVM options in order to dump {@code LongBuffer} and {@code IntBuffer} instances.
 *
 * <h3>Examples</h3>
 * To dump to an Slf4J log, {@code DumpUtility} can be combined with
 * {@link org.terracotta.utilities.logging.LoggingOutputStream LoggingOutputStream} as in the following:
 * <pre>{@code
 *     try (LoggingOutputStream loggingStream = new LoggingOutputStream(LoggerFactory.getLogger("dump"), Level.INFO);
 *          PrintStream printStream = new PrintStream(loggingStream, false, StandardCharsets.UTF_8.name())) {
 *       DumpUtility.dumpBuffer(buffer, printStream);
 *     }}</pre>
 */
@SuppressWarnings("UnusedDeclaration")
public final class DumpUtility {
  /**
   * The {@code PrintStream} to which the dump is written.
   */
  private final PrintStream printStream;

  /**
   * The prefix to prepend to each emitted line.
   */
  private final CharSequence linePrefix;

  /**
   * Tracks objects already dumped.
   */
  private final Set<Object> dumpedSet = Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * Constructs a new {@code DumpUtility} using the {@code PrintStream} provided.
   * This constructor is equivalent to
   * {@link #DumpUtility(PrintStream, CharSequence) new DumpUtility(printStream, "")}.
   *
   * @param printStream the {@code PrintStream} to which the dump is written
   */
  public DumpUtility(PrintStream printStream) {
    this(printStream, "");
  }

  /**
   * Constructs a new {@code DumpUtility} using the {@code PrintStream} and line prefix provided.
   * @param printStream the {@code PrintStream} to which the dump is written
   * @param linePrefix the value to prepend to each dump line written; no space is added between
   *                   {@code linePrefix} and the dump line
   */
  public DumpUtility(PrintStream printStream, CharSequence linePrefix) {
    this.printStream = requireNonNull(printStream, "printStream");
    this.linePrefix = requireNonNull(linePrefix, "linePrefix");
  }

  /**
   * Tracks objects dumped by this {@code DumpUtility} instance to permit avoidance of
   * duplicate dumps of data structures.  This method write a line to {@code printStream}:
   * if the object not been dumped, the line is suitable to identify the object being
   * dumped; if the object has been dumped, the line is a reference to the identity
   * previously displayed.
   *
   * @param o the object to track
   *
   * @return {@code true} if {@code o} was previously dumped
   */
  private boolean wasDumped(Object o) {
    if (this.dumpedSet.contains(o)) {
      printStream.format("%s    ==> %s%n", linePrefix, getObjectId(o));
      return true;
    }
    this.dumpedSet.add(o);
    printStream.format("%s[%s]%n", linePrefix, getObjectId(o));
    return false;
  }

  /**
   * A convenience method that allocates a {@code DumpUtility} instance, dumps a buffer,
   * and discards the {@code DumpUtility} instance.
   * @param buffer the {@code IntBuffer} to dump
   * @param printStream the {@code PrintStream} to which the dump is written
   *
   * @throws UnsupportedOperationException if the {@code buffer} is not a supported type or
   *      reflective access to a required field fails
   */
  public static void dumpBuffer(IntBuffer buffer, PrintStream printStream) {
    new DumpUtility(printStream).dumpBuffer(buffer);
  }

  /**
   * A convenience method that allocates a {@code DumpUtility} instance, dumps a buffer,
   * and discards the {@code DumpUtility} instance.
   * @param buffer the {@code LongBuffer} to dump
   * @param printStream the {@code PrintStream} to which the dump is written
   *
   * @throws UnsupportedOperationException if the {@code buffer} is not a supported type or
   *      reflective access to a required field fails
   */
  public static void dumpBuffer(LongBuffer buffer, PrintStream printStream) {
    new DumpUtility(printStream).dumpBuffer(buffer);
  }

  /**
   * A convenience method that allocates a {@code DumpUtility} instance, dumps a buffer,
   * and discards the {@code DumpUtility} instance.
   * @param buffer the {@code ByteBuffer} to dump
   * @param printStream the {@code PrintStream} to which the dump is written
   */
  public static void dumpBuffer(ByteBuffer buffer, PrintStream printStream) {
    new DumpUtility(printStream).dumpBuffer(buffer);
  }


  /**
   * Attempts to dump a {@link IntBuffer IntBuffer}.  The present implementation of this
   * method depends the use of {@link ByteBuffer#asIntBuffer() ByteBuffer.asIntBuffer} in
   * creation of {@code buffer} <b>and</b> on the internal implementation of {@code ByteBuffer}.
   *
   * @param buffer the {@code IntBuffer} instance to dump
   *
   * @throws UnsupportedOperationException if the {@code buffer} is not a supported type or
   *      reflective access to a required field fails
   */
  public void dumpBuffer(IntBuffer buffer) {
    requireNonNull(buffer, "buffer");

    if (wasDumped(buffer)) {
      return;
    }

    String bufferClassName = buffer.getClass().getName();
    String byteBufferFieldName;
    if (bufferClassName.equals("java.nio.ByteBufferAsIntBufferB")
        || bufferClassName.equals("java.nio.ByteBufferAsIntBufferL")
        || bufferClassName.equals("java.nio.ByteBufferAsIntBufferRB")
        || bufferClassName.equals("java.nio.ByteBufferAsIntBufferRL")) {
      byteBufferFieldName = "bb";

    } else if (bufferClassName.equals("java.nio.DirectIntBufferU")
        || bufferClassName.equals("java.nio.DirectIntBufferS")
        || bufferClassName.equals("java.nio.DirectIntBufferRU")
        || bufferClassName.equals("java.nio.DirectIntBufferRS")) {
      byteBufferFieldName = "att";

    } else {
      throw new UnsupportedOperationException(String.format("IntBuffer type not supported: %s", bufferClassName));
    }

    describeBuffer(buffer);
    dumpBuffer(getFieldValue(ByteBuffer.class, buffer, byteBufferFieldName));
  }

  /**
   * Attempts to dump a {@link LongBuffer LongBuffer}.  The present implementation of this
   * method depends the use of {@link ByteBuffer#asLongBuffer() ByteBuffer.asLongBuffer} in
   * creation of {@code buffer} <b>and</b> on the internal implementation of {@code ByteBuffer}.
   *
   * @param buffer the {@code LongBuffer} instance to dump
   *
   * @throws UnsupportedOperationException if the {@code buffer} is not a supported type or
   *      reflective access to a required field fails
   */
  public void dumpBuffer(LongBuffer buffer) {
    requireNonNull(buffer, "buffer");

    if (wasDumped(buffer)) {
      return;
    }

    String bufferClassName = buffer.getClass().getName();
    String byteBufferFieldName;
    if (bufferClassName.equals("java.nio.ByteBufferAsLongBufferB")
        || bufferClassName.equals("java.nio.ByteBufferAsLongBufferL")
        || bufferClassName.equals("java.nio.ByteBufferAsLongBufferRB")
        || bufferClassName.equals("java.nio.ByteBufferAsLongBufferRL")) {
      byteBufferFieldName = "bb";

    } else if (bufferClassName.equals("java.nio.DirectLongBufferU")
        || bufferClassName.equals("java.nio.DirectLongBufferS")
        || bufferClassName.equals("java.nio.DirectLongBufferRU")
        || bufferClassName.equals("java.nio.DirectLongBufferRS")) {
      byteBufferFieldName = "att";

    } else {
      throw new UnsupportedOperationException(String.format("LongBuffer type not supported: %s", bufferClassName));
    }

    describeBuffer(buffer);
    dumpBuffer(getFieldValue(ByteBuffer.class, buffer, byteBufferFieldName));
  }

  /**
   * Writes the contents of {@link ByteBuffer ByteBuffer} to the {@link PrintStream PrintStream}
   * provided.  The dump consists of a sequence of lines where each line is the dump of a 32-byte segment from
   * the buffer and includes data in hexadecimal and in printable ASCII.  A sample dump is shown: <pre>{@code
   * 00000000  00000000 00000000 00000000 00000023  A5000000 00000000 00000000 00000000  *...............# ................*
   * 00000020  00000000 00000020 00000000 00000023  A5000000 00000000 00000000 00000000  *....... .......# ................*
   * 00000040-00000C7F  duplicates above                                                 *                                 *
   * 00000C80  00000000 00000020 00000000 00000351  00000000 00000000 00000000 00000000  *....... .......Q ................*
   * 00000CA0  00000000 00000000 00000000 00000000  00000000 00000000 00000000 00000000  *................ ................*
   * 00000CC0-00FFFFDF  duplicates above                                                 *                                 *
   * 00FFFFE0  00000000 00000000 00000000 00000000  00000000 00000000 00000000 00000000  *................ ................*
   * }</pre>
   * <p>
   * For a <i>direct</i> {@code ByteBuffer}, this method attempts to access the value of an
   * internal field.  Failure to access this field does not prevent this method from dumping
   * the {@code ByteBuffer}.
   *
   * @param buffer the {@code ByteBuffer} to dump; the buffer is printed from position zero (0) through
   *               the buffer limit using a <i>view</i> over the buffer
   */
  public void dumpBuffer(ByteBuffer buffer) {
    requireNonNull(buffer, "buffer");

    if (wasDumped(buffer)) {
      return;
    }

    describeBuffer(buffer);

    ByteBuffer view = buffer.asReadOnlyBuffer();
    view.clear();

    dumpBufferInternal(view);
  }

  /**
   * Writes the contents of {@code byte[]} to the {@link PrintStream PrintStream}
   * provided.  The dump consists of a sequence of lines where each line is the dump of a 32-byte segment from
   * the array and includes data in hexadecimal and in printable ASCII.  A sample dump is shown: <pre>{@code
   * 00000000  00000000 00000000 00000000 00000023  A5000000 00000000 00000000 00000000  *...............# ................*
   * 00000020  00000000 00000020 00000000 00000023  A5000000 00000000 00000000 00000000  *....... .......# ................*
   * 00000040-00000C7F  duplicates above                                                 *                                 *
   * 00000C80  00000000 00000020 00000000 00000351  00000000 00000000 00000000 00000000  *....... .......Q ................*
   * 00000CA0  00000000 00000000 00000000 00000000  00000000 00000000 00000000 00000000  *................ ................*
   * 00000CC0-00FFFFDF  duplicates above                                                 *                                 *
   * 00FFFFE0  00000000 00000000 00000000 00000000  00000000 00000000 00000000 00000000  *................ ................*
   * }</pre>
   *
   * @param bytes the {@code byte} array to dump
   */
  public void dump(byte[] bytes) {
    requireNonNull(bytes, "bytes");
    dumpBufferInternal(ByteBuffer.wrap(bytes));
  }

  /**
   * Writes a description of the {@code Buffer} supplied to the print stream for this {@code DumpUtility} instance.
   * @param buffer the buffer to describe
   */
  private void describeBuffer(Buffer buffer) {
    ByteOrder order;
    if (buffer instanceof ByteBuffer) {
      order = ((ByteBuffer)buffer).order();
    } else if (buffer instanceof LongBuffer) {
      order = ((LongBuffer)buffer).order();
    } else if (buffer instanceof IntBuffer) {
      order = ((IntBuffer)buffer).order();
    } else {
      order = null;
    }
    printStream.format("%s    ByteOrder=%s; capacity=%d (0x%<X); limit=%d (0x%<X); position=%d (0x%<X)",
        linePrefix, order, buffer.capacity(), buffer.limit(), buffer.position());

    if (buffer.isDirect()) {
      try {
        long address = getFieldValue(Long.class, buffer, "address", true);
        printStream.format("; address=0x%X", address);
      } catch (FieldInaccessibleException ignored) {
      }
    }

    printStream.println();
  }

  private void dumpBufferInternal(ByteBuffer view) {
    int segmentSize = 32;
    int dumpFormatMax = 8 + 2 + 8 * (segmentSize/4) + (segmentSize/4 - 1) + (segmentSize/16 - 1);
    int charFormatMax = segmentSize + (segmentSize/16 - 1);

    StringBuilder dumpBuilder = new StringBuilder(128);
    Formatter dumpFormatter = new Formatter(dumpBuilder);
    StringBuilder charBuilder = new StringBuilder(128);

    byte[][] segments = new byte[2][segmentSize];
    int activeIndex = 0;
    boolean previousSegmentSame = false;
    while (view.hasRemaining()) {

      if (!previousSegmentSame) {
        flushDumpLine(printStream, dumpBuilder, charBuilder);
      }

      int offset = view.position();

      byte[] activeSegment = segments[activeIndex];
      int segmentLength = Math.min(activeSegment.length, view.remaining());
      view.get(activeSegment, 0, segmentLength);

      /*
       * Except the first segment, perform duplicate segment handling. Duplicate segment data
       * is not dumped; the offset of the first duplicate is retained and printed along with
       * a terminating offset either once a non-duplicate segment or the end of the buffer is
       * reached.
       */
      if (offset != 0) {
        if (view.remaining() != 0 && Arrays.equals(activeSegment, segments[1 - activeIndex])) {
          /* Suppress printing of a segment equal to the previous segment. */
          if (!previousSegmentSame) {
            dumpFormatter.format("%08X", offset);
            previousSegmentSame = true;
          }
          continue;   /* Emit nothing for the 2nd through Nth segments having duplicate data. */

        } else if (previousSegmentSame) {
          /* No longer duplicated; complete duplication marker line and flush. */
          dumpFormatter.format("-%08X  duplicates above", offset - 1);
          dumpBuilder.append(new String(new char[dumpFormatMax - dumpBuilder.length()]).replace('\0', ' '));
          charBuilder.append(new String(new char[charFormatMax]).replace('\0', ' '));
          flushDumpLine(printStream, dumpBuilder, charBuilder);
          previousSegmentSame = false;
        }
      }

      dumpFormatter.format("%08X  ", offset);

      /*
       * Format the segment (or final fragment) data.  Include the character form of each
       * byte if, and only if, it is both (7-bit) ASCII and not a control character.
       */
      for (int i = 0; i < segmentLength; i++) {
        if (i != 0) {
          addGroupSpace(i, dumpBuilder, charBuilder);
        }

        byte b = activeSegment[i];
        dumpFormatter.format("%02X", b & 0xFF);
        charBuilder.append(ASCII_ENCODER.canEncode((char) b) && !Character.isISOControl(b) ? (char) b : '.');
      }

      activeIndex = 1 - activeIndex;
    }

    /*
     * If the last segment was not a full segment, complete formatting the dump line
     * using filler so separators and such are aligned.
     */
    int segmentOffset = view.position() % segmentSize;
    if (segmentOffset != 0) {
      /* Fill the remaining output buffer */
      for (int i = segmentOffset; i < segmentSize; i++) {
        addGroupSpace(i, dumpBuilder, charBuilder);
        dumpBuilder.append("  ");     /* Empty data */
        charBuilder.append(' ');
      }
    }

    flushDumpLine(printStream, dumpBuilder, charBuilder);
  }

  /**
   * {@link CharsetEncoder CharsetEncoder} used to test bytes for printability.
   */
  private static final CharsetEncoder ASCII_ENCODER = StandardCharsets.US_ASCII.newEncoder();

  private static void addGroupSpace(int i, StringBuilder dumpBuilder, StringBuilder charBuilder) {
    if (i % 4 == 0) {
      dumpBuilder.append(' ');
    }
    if (i % 16 == 0) {
      dumpBuilder.append(' ');
      charBuilder.append(' ');
    }
  }

  /**
   * Emit a dump line and prepare the buffers for the next.
   *
   * @param out the {@code PrintStream} to which the dump line is written
   * @param dumpBuilder the {@code StringBuilder} into which the hex portion of the dump line, including
   *                    the displacement, are recorded
   * @param charBuilder the {@code StringBuilder} into which the ASCII portion of the dump line is recorded
   */
  private void flushDumpLine(PrintStream out, StringBuilder dumpBuilder, StringBuilder charBuilder) {
    if (dumpBuilder.length() != 0) {
      out.append(linePrefix).append(dumpBuilder).append("  ").append('*').append(charBuilder).append('*');
      out.println();
      dumpBuilder.setLength(0);
      charBuilder.setLength(0);
    }
  }

  /**
   * Gets the value of the designated field from the object instance provided.  This method expects
   * a non-{@code null} value in the field.
   *
   * @param expectedType the type to which the fetched value is cast
   * @param instance the instance from which the value is fetched
   * @param fieldName the name of the field in {@code instance} holding the value
   * @param <V> the declared type of the returned value
   * @param <T> the declared type of the object from which the value is obtained
   *
   * @return the value of the named field
   *
   * @throws FieldInaccessibleException if an error occurs while attempting to access the field
   * @throws AssertionError if the field {@code fieldName} contains {@code null}
   */
  private <V, T> V getFieldValue(Class<V> expectedType, T instance, String fieldName) {
    return getFieldValue(expectedType, instance, fieldName, false);
  }

  /**
   * Gets the value of the designated field from the object instance provided.  This method expects
   * a non-{@code null} value in the field.
   *
   * @param <V> the declared type of the returned value
   * @param <T> the declared type of the object from which the value is obtained
   *
   * @param expectedType the type to which the fetched value is cast
   * @param instance the instance from which the value is fetched
   * @param fieldName the name of the field in {@code instance} holding the value
   * @param quiet if {@code true}, suppress the informational message related to the value fetch and permit
   *              the return of a {@code null} value
   *
   * @return the value of the named field; may be {@code null} if, and only if, {@code quiet} is {@code true}
   *
   * @throws FieldInaccessibleException if an error occurs while attempting to access the field
   * @throws AssertionError if the field {@code fieldName} contains {@code null}
   */
  @SuppressWarnings("JavaReflectionMemberAccess")
  private <V, T> V getFieldValue(Class<V> expectedType, T instance, String fieldName, boolean quiet) {
    V fieldValue;
    try {
      /*
       * Traverse the current and super class definitions looking for the named field.
       */
      Class<?> fieldHoldingClass = instance.getClass();
      NoSuchFieldException firstFault = null;
      Field fieldDef = null;
      do {
        try {
          fieldDef = fieldHoldingClass.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
          if (firstFault == null) {
            firstFault = e;
          }
          fieldHoldingClass = fieldHoldingClass.getSuperclass();
          if (fieldHoldingClass == null) {
            throw firstFault;
          }
        }
      } while (fieldDef == null);
      fieldDef.setAccessible(true);
      fieldValue = expectedType.cast(fieldDef.get(instance));
      if (!quiet) {
        if (fieldValue == null) {
          throw new AssertionError(String.format("%s.%s is null; expecting %s instance",
              instance.getClass().getSimpleName(), fieldName, expectedType.getSimpleName()));
        }
        printStream.format("%s    %s.%s -> %s%n",
            linePrefix, instance.getClass().getSimpleName(), fieldName, getObjectId(fieldValue));
      }

    } catch (RuntimeException | NoSuchFieldException | IllegalAccessException e) {
      String addOpens = "";
      if (e instanceof RuntimeException) {
        // For Java 17+, need to use '--add-opens java.base/java.nio=ALL-UNNAMED'
        if (e.getClass().getName().equals("java.lang.reflect.InaccessibleObjectException")) {
          Object jvmModule;
          try {
            jvmModule = Class.forName("java.lang.Module")
                .getMethod("getName")
                .invoke(Class.class.getMethod("getModule").invoke(instance.getClass()));
          } catch (ReflectiveOperationException ex) {
            jvmModule = "?UNKNOWN?";
          }
          String classPackage = instance.getClass().getPackage().getName();
          addOpens = String.format(";%n    add JVM option \"--add-opens %s/%s=ALL-UNNAMED\"", jvmModule, classPackage);
        } else {
          throw (RuntimeException)e;
        }
      }
      throw new FieldInaccessibleException(
          String.format("Unable to access '%s' field from %s instance%s", fieldName, instance.getClass().getName(), addOpens),
          e);
    }
    return fieldValue;
  }

  /**
   * Gets an object identifier similar to the one produced by {@link Object#toString() Object.toString}.
   *
   * @param o the object for which the identifier is generated
   *
   * @return the object identifier
   */
  private static String getObjectId(Object o) {
    if (o == null) {
      return "null@0";
    }
    return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
  }

  /**
   * Thrown if reflective access to a required internal field fails.
   */
  public static final class FieldInaccessibleException extends UnsupportedOperationException {
    private static final long serialVersionUID = -2136579828792539023L;

    public FieldInaccessibleException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
