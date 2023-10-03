/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.terracotta.utilities.io.CapturedPrintStream;
import org.terracotta.utilities.test.ModuleSupport;

import java.io.BufferedReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

/**
 * Tests for {@link DumpUtility}.
 */
@RunWith(Parameterized.class)
public class DumpUtilityTest extends AbstractDumpUtilityTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
            { "directBuffer", MemoryType.DIRECT, BufferType.BYTE, null, null, Access.WRITABLE },
            { "directIntBufferBigUnaligned", MemoryType.DIRECT, BufferType.INTEGER, Endian.BIG, Alignment.UNALIGNED, Access.WRITABLE },
            { "directLongBufferBigUnaligned", MemoryType.DIRECT, BufferType.LONG, Endian.BIG, Alignment.UNALIGNED, Access.WRITABLE },
            { "directIntBufferLittleUnaligned", MemoryType.DIRECT, BufferType.INTEGER, Endian.LITTLE, Alignment.UNALIGNED, Access.WRITABLE },
            { "directLongBufferLittleUnaligned", MemoryType.DIRECT, BufferType.LONG, Endian.LITTLE, Alignment.UNALIGNED, Access.WRITABLE },
            { "directIntBufferBigAligned", MemoryType.DIRECT, BufferType.INTEGER, Endian.BIG, Alignment.ALIGNED, Access.WRITABLE },
            { "directLongBufferBigAligned", MemoryType.DIRECT, BufferType.LONG, Endian.BIG, Alignment.ALIGNED, Access.WRITABLE },
            { "directIntBufferLittleAligned", MemoryType.DIRECT, BufferType.INTEGER, Endian.LITTLE, Alignment.ALIGNED, Access.WRITABLE },
            { "directLongBufferLittleAligned", MemoryType.DIRECT, BufferType.LONG, Endian.LITTLE, Alignment.ALIGNED, Access.WRITABLE },

            { "heapBuffer", MemoryType.HEAP, BufferType.BYTE, null, null, Access.WRITABLE },
            { "heapIntBufferBigUnaligned", MemoryType.HEAP, BufferType.INTEGER, Endian.BIG, Alignment.UNALIGNED, Access.WRITABLE },
            { "heapLongBufferBigUnaligned", MemoryType.HEAP, BufferType.LONG, Endian.BIG, Alignment.UNALIGNED, Access.WRITABLE },
            { "heapIntBufferLittleUnaligned", MemoryType.HEAP, BufferType.INTEGER, Endian.LITTLE, Alignment.UNALIGNED, Access.WRITABLE },
            { "heapLongBufferLittleUnaligned", MemoryType.HEAP, BufferType.LONG, Endian.LITTLE, Alignment.UNALIGNED, Access.WRITABLE },
            { "heapIntBufferBigAligned", MemoryType.HEAP, BufferType.INTEGER, Endian.BIG, Alignment.ALIGNED, Access.WRITABLE },
            { "heapLongBufferBigAligned", MemoryType.HEAP, BufferType.LONG, Endian.BIG, Alignment.ALIGNED, Access.WRITABLE },
            { "heapIntBufferLittleAligned", MemoryType.HEAP, BufferType.INTEGER, Endian.LITTLE, Alignment.ALIGNED, Access.WRITABLE },
            { "heapLongBufferLittleAligned", MemoryType.HEAP, BufferType.LONG, Endian.LITTLE, Alignment.ALIGNED, Access.WRITABLE },

            { "directBufferRO", MemoryType.DIRECT, BufferType.BYTE, null, null, Access.READ_ONLY },
            { "directIntBufferBigUnalignedRO", MemoryType.DIRECT, BufferType.INTEGER, Endian.BIG, Alignment.UNALIGNED, Access.READ_ONLY },
            { "directLongBufferBigUnalignedRO", MemoryType.DIRECT, BufferType.LONG, Endian.BIG, Alignment.UNALIGNED, Access.READ_ONLY },
            { "directIntBufferLittleUnalignedRO", MemoryType.DIRECT, BufferType.INTEGER, Endian.LITTLE, Alignment.UNALIGNED, Access.READ_ONLY },
            { "directLongBufferLittleUnalignedRO", MemoryType.DIRECT, BufferType.LONG, Endian.LITTLE, Alignment.UNALIGNED, Access.READ_ONLY },
            { "directIntBufferBigAlignedRO", MemoryType.DIRECT, BufferType.INTEGER, Endian.BIG, Alignment.ALIGNED, Access.READ_ONLY },
            { "directLongBufferBigAlignedRO", MemoryType.DIRECT, BufferType.LONG, Endian.BIG, Alignment.ALIGNED, Access.READ_ONLY },
            { "directIntBufferLittleAlignedRO", MemoryType.DIRECT, BufferType.INTEGER, Endian.LITTLE, Alignment.ALIGNED, Access.READ_ONLY },
            { "directLongBufferLittleAlignedRO", MemoryType.DIRECT, BufferType.LONG, Endian.LITTLE, Alignment.ALIGNED, Access.READ_ONLY },

            { "heapBufferRO", MemoryType.HEAP, BufferType.BYTE, null, null, Access.READ_ONLY },
            { "heapIntBufferBigUnalignedRO", MemoryType.HEAP, BufferType.INTEGER, Endian.BIG, Alignment.UNALIGNED, Access.READ_ONLY },
            { "heapLongBufferBigUnalignedRO", MemoryType.HEAP, BufferType.LONG, Endian.BIG, Alignment.UNALIGNED, Access.READ_ONLY },
            { "heapIntBufferLittleUnalignedRO", MemoryType.HEAP, BufferType.INTEGER, Endian.LITTLE, Alignment.UNALIGNED, Access.READ_ONLY },
            { "heapLongBufferLittleUnalignedRO", MemoryType.HEAP, BufferType.LONG, Endian.LITTLE, Alignment.UNALIGNED, Access.READ_ONLY },
            { "heapIntBufferBigAlignedRO", MemoryType.HEAP, BufferType.INTEGER, Endian.BIG, Alignment.ALIGNED, Access.READ_ONLY },
            { "heapLongBufferBigAlignedRO", MemoryType.HEAP, BufferType.LONG, Endian.BIG, Alignment.ALIGNED, Access.READ_ONLY },
            { "heapIntBufferLittleAlignedRO", MemoryType.HEAP, BufferType.INTEGER, Endian.LITTLE, Alignment.ALIGNED, Access.READ_ONLY },
            { "heapLongBufferLittleAlignedRO", MemoryType.HEAP, BufferType.LONG, Endian.LITTLE, Alignment.ALIGNED, Access.READ_ONLY },
        }
    );
  }

  @Parameterized.Parameter(0)
  public String testId;

  @Parameterized.Parameter(1)
  public MemoryType memoryType;

  @Parameterized.Parameter(2)
  public BufferType bufferType;

  @Parameterized.Parameter(3)
  public Endian byteOrder;

  @Parameterized.Parameter(4)
  public Alignment alignment;

  @Parameterized.Parameter(5)
  public Access access;

  /**
   * The expected test {@code Buffer} implementation class name as computed by
   * {@link #expectedBufferClass()}.
   */
  private String expectedImplementationClass;

  /**
   * Pattern to test and parse the dump descriptor line.
   */
  private static final Pattern DUMP_DESCRIPTOR =
      Pattern.compile("\\s+ByteOrder=(?<byteOrder>[^;]+)" +
          ";\\s+capacity=(?<capacity>\\d+)\\s.+" +
          ";\\s+limit=(?<limit>\\d+)\\s.+" +
          ";\\s+position=(?<position>\\d+)\\s.+?" +
          "(;\\s+address=(?<address>.+))?");

  @BeforeClass
  public static void beforeClass() {
    assumeTrue("Needs JVM option '--add-opens java.base/java.nio=ALL-UNNAMED' for proper testing", ModuleSupport.isOpen(ByteBuffer.class));
  }

  @Before
  public void setUp() {
    this.expectedImplementationClass = expectedBufferClass();
  }

  /**
   * Tests an zero-length (empty) buffer.
   */
  @Test
  public void testEmpty() throws Exception {
    assumeThat("An alignment offset cannot be applied to a zero-length buffer", alignment, is(not(Alignment.UNALIGNED)));
    byte[] content = new byte[0];
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    assertThat(extractDump(lines, false), is(empty()));
  }

  /**
   * Tests a buffer of all zeros.
   */
  @Test
  public void testAllZeros() throws Exception {
    byte[] content = zeros(4096);
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer holding one byte.
   */
  @Test
  public void testOneByte() throws Exception {
    byte[] content = toBytes("FF");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer holding nine bytes -- one byte beyond the first dump "word".
   */
  @Test
  public void testNineBytes() throws Exception {
    byte[] content = toBytes("090807060504030201");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer with sixteen bytes -- one-half a complete dump line.
   */
  @Test
  public void testSixteenBytes() throws Exception {
    byte[] content = toBytes("000102030405060708090a0b0c0d0e0f");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer with seventeen bytes -- one-half a complete dump line plus one byte.
   */
  @Test
  public void testSeventeenBytes() throws Exception {
    byte[] content = toBytes("000102030405060708090a0b0c0d0e0f10");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer with thirty-two bytes -- one complete dump line.
   */
  @Test
  public void testThirtyTwoBytes() throws Exception {
    byte[] content = toBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a buffer with thirty-three bytes -- one complete dump line plus one byte.
   */
  @Test
  public void testThirtyThreeBytes() throws Exception {
    byte[] content = toBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a longer dump with non-zero content.
   */
  @Test
  public void testNonZero() throws Exception {
    byte[] content = latin1Repeat(16);
    List<String> lines = captureDump(getBuffer(content));
    verifyDumpHeader(lines, content.length);
    ByteBuffer observed = parseDump(extractDump(lines, true), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Verifies the content of the dump header.
   *
   * @param lines    the dump lines to examine
   * @param rawCapacity the expected rawCapacity of the <i>raw</i> (byte) buffer
   */
  private void verifyDumpHeader(List<String> lines, int rawCapacity) {
    assertThat(lines.get(0), containsString(expectedImplementationClass));
    if (bufferType == BufferType.BYTE) {
      verifyDescriptor(lines.get(1), false, rawCapacity, 0);
      if (lines.size() > 2) {
        assertThat(lines.get(2), startsWith("00000000 "));
      }
    } else {
      // All non-BYTE buffers wrap a ByteBuffer
      int capacity = bufferType.capacity(rawCapacity, alignment);
      verifyDescriptor(lines.get(1), false, capacity, 0);
      assertThat(lines.size(), is(greaterThanOrEqualTo(5)));
      verifyDescriptor(lines.get(4), true, rawCapacity, alignment.offset());
    }
  }

  /**
   * Parses and verifies the content of the buffer descriptor.
   *
   * @param bufferDescriptor the buffer descriptor line to process
   * @param skipOrderCheck indicates the {@code ByteOrder} check should be skipped for the current {@code bufferDescriptor}
   * @param capacity         the expected capacity of the buffer
   * @param position         the expected position of the buffer
   */
  private void verifyDescriptor(String bufferDescriptor, boolean skipOrderCheck, int capacity, int position) {
    Matcher descriptor = DUMP_DESCRIPTOR.matcher(bufferDescriptor);
    assertTrue("Dump descriptor line not found where expected", descriptor.matches());
    if (!skipOrderCheck && byteOrder != null && capacity != 0) {
      // Short/Long aligned, read-only buffers based on empty ByteBuffer don't respect byte ordering
      assertThat(descriptor.group("byteOrder"), is(byteOrder.byteOrder().toString()));
    }
    assertThat(Integer.parseInt(descriptor.group("capacity")), is(capacity));
    assertThat(Integer.parseInt(descriptor.group("limit")), is(capacity));
    assertThat(Integer.parseInt(descriptor.group("position")), is(position));
    if (memoryType == MemoryType.DIRECT) {
      assertThat(descriptor.group("address"), is(notNullValue()));
    } else {
      assertThat(descriptor.group("address"), is(nullValue()));
    }
  }

  /**
   * Dumps the specified buffer capturing the output.
   * @param buffer the {@code Buffer} to dump
   * @return the dumped lines
   * @throws Exception if an error is raised retrieving the dump lines
   */
  private List<String> captureDump(Buffer buffer) throws Exception {
    try (CapturedPrintStream printStream = CapturedPrintStream.getInstance()) {
      callDump(buffer, new DumpUtility(printStream));
      try (BufferedReader reader = printStream.getReader();
           Stream<String> content = reader.lines()) {
        Stream<String> dumpLines = content;
        if (DIAGNOSTICS_ENABLED) {
          dumpLines = dumpLines.peek(System.out::println);
        }
        return dumpLines.collect(Collectors.toList());
      }
    }
  }

  /**
   * Create the test buffer.  This method allocates the buffer, loads the provided data into the buffer,
   * and applies attributes to the buffer.
   * @param content the bytes to load into the allocated buffer
   * @return the buffer
   */
  private Buffer getBuffer(byte[] content) {
    // Access must be applied before byteOrder -- seems byteOrder is not propagated past ByteBuffer.asReadOnlyBuffer
    Buffer buffer = make(content, memoryType, bufferType, alignment, access, byteOrder);

    /*
     * Assert the expected implementation class is the class that was created.
     * Deviation from the computed name indicates a change in implementation that may require
     * changes to DumpUtility.
     */
    assertThat(buffer.getClass().getName(), is(expectedImplementationClass));

    return buffer;
  }

  /**
   * Uses the {@code DumpUtility} provided to dump the supplied {@code Buffer}.
   * @param buffer the {@code Buffer} to dump
   * @param dumpUtility the {@code DumpUtility} instance to use
   */
  private void callDump(Buffer buffer, DumpUtility dumpUtility) {
    if (buffer instanceof LongBuffer) {
      dumpUtility.dumpBuffer((LongBuffer)buffer);
    } else if (buffer instanceof IntBuffer) {
      dumpUtility.dumpBuffer((IntBuffer)buffer);
    } else {
      dumpUtility.dumpBuffer((ByteBuffer)buffer);
    }
  }

  /**
   * Constructs the expected implementation class name of the buffer.
   * The names of the {@code LongBuffer} and {@code IntBuffer} implementations
   * (formed as a view on a {@code ByteBuffer}) are <i>formulaic</i>, e.g. computable.
   * This method computes the expected implementation class name using the properties
   * used to generate the test buffers.
   * @return the expected buffer class name
   */
  private String expectedBufferClass() {
    StringBuilder builder = new StringBuilder("java.nio.")
        .append(memoryType.nameComponent(bufferType))     // Direct / Heap / ByteBufferAs
        .append(bufferType.nameComponent())               // Byte / Int / Long
        .append("Buffer")
        .append(access.nameComponent());                  // "" / "R"
    if (bufferType != BufferType.BYTE) {
      if (memoryType == MemoryType.DIRECT) {
        builder.append(byteOrder == Endian.BIG ? 'S' : 'U');
      } else {
        builder.append(byteOrder == Endian.BIG ? 'B' : 'L');
      }
    }
    return builder.toString();
  }

  /**
   * Creates a test {@code Buffer} using the specified attributes.
   * @param content the {@code byte[]} holding the content to set in the buffer
   * @param allocator the {@code MemoryType} providing the buffer allocation method
   * @param convert the {@code BufferType} providing the method that converts the {@code ByteBuffer} to the final type
   * @param modifications the attributes to apply to the {@code ByteBuffer} before finalizing the {@code Buffer}
   * @return the filled test {@code Buffer}
   */
  @SafeVarargs
  private static Buffer make(byte[] content, MemoryType allocator, BufferType convert, UnaryOperator<ByteBuffer>... modifications) {
    ByteBuffer buffer = allocator.apply(content.length);
    showBuffer("initial", buffer);
    loadBuffer(content, buffer);
    for (UnaryOperator<ByteBuffer> mod : modifications) {
      if (mod != null) {
        buffer = mod.apply(buffer);
        showBuffer(mod.toString(), buffer);
      }
    }
    Buffer result = convert.apply(buffer);
    showBuffer(convert.toString(), result);
    return result;
  }

  private static void showBuffer(String identifier, Buffer buffer) {
    if (!DIAGNOSTICS_ENABLED) {
      return;
    }
    System.out.format("%13s Buffer[%s@0%x]:", identifier, buffer.getClass().getSimpleName(), System.identityHashCode(buffer));
    System.out.format(" direct=%b, readOnly=%b, position=%d", buffer.isDirect(), buffer.isReadOnly(), buffer.position());
    if (buffer instanceof ByteBuffer) {
      System.out.format(", order=%s", ((ByteBuffer)buffer).order());
    } else if (buffer instanceof LongBuffer) {
      System.out.format(", order=%s", ((LongBuffer)buffer).order());
    } else if (buffer instanceof IntBuffer) {
      System.out.format(", order=%s", ((IntBuffer)buffer).order());
    }
    System.out.println();
  }

  /**
   * Writes bytes to the {@code ByteBuffer} provided.  This method rewinds the buffer after writing the bytes.
   * @param content the bytes to write
   * @param buffer the {@code ByteBuffer} into which {@code content} is written
   * @return the input {@code ByteBuffer}
   */
  @SuppressWarnings("UnusedReturnValue")
  private static ByteBuffer loadBuffer(byte[] content, ByteBuffer buffer) {
    buffer.put(content);
    buffer.rewind();
    return buffer;
  }


  /**
   * Memory type of the test {@code ByteBuffer}.
   */
  public enum MemoryType implements IntFunction<ByteBuffer> {
    DIRECT {
      @Override
      public String nameComponent(BufferType bufferType) {
        return "Direct";
      }
      @Override
      public ByteBuffer apply(int size) {
        return ByteBuffer.allocateDirect(size);
      }
    },
    HEAP {
      @Override
      public String nameComponent(BufferType bufferType) {
        if (bufferType == BufferType.BYTE) {
          return "Heap";
        } else {
          return "ByteBufferAs";
        }
      }
      @Override
      public ByteBuffer apply(int size) {
        return ByteBuffer.allocate(size);
      }
    };

    public abstract String nameComponent(BufferType bufferType);
  }

  /**
   * Specifies the type of {@code Buffer} to create.
   */
  public enum BufferType implements Function<ByteBuffer, Buffer> {
    BYTE {
      @Override
      public String nameComponent() {
        return "Byte";
      }
      @Override
      public int capacity(int rawCapacity, Alignment alignment) {
        return rawCapacity;
      }
      @Override
      public Buffer apply(ByteBuffer buffer) {
        return buffer;
      }
    },

    INTEGER {
      @Override
      public String nameComponent() {
        return "Int";
      }
      @Override
      public int capacity(int rawCapacity, Alignment alignment) {
        int capacity = alignment == Alignment.UNALIGNED ? rawCapacity - 1 : rawCapacity;
        return capacity / Integer.BYTES;
      }
      @Override
      public Buffer apply(ByteBuffer buffer) {
        return buffer.asIntBuffer();
      }
    },

    LONG {
      @Override
      public String nameComponent() {
        return "Long";
      }
      @Override
      public int capacity(int rawCapacity, Alignment alignment) {
        int capacity = alignment == Alignment.UNALIGNED ? rawCapacity - 1 : rawCapacity;
        return capacity / Long.BYTES;
      }
      @Override
      public Buffer apply(ByteBuffer buffer) {
        return buffer.asLongBuffer();
      }
    };

    public abstract String nameComponent();

    public abstract int capacity(int rawCapacity, Alignment alignment);
  }

  /**
   * Specifies the "endianness" of the test buffer.
   */
  public enum Endian implements UnaryOperator<ByteBuffer> {
    BIG(BIG_ENDIAN),
    LITTLE(LITTLE_ENDIAN);

    private final ByteOrder byteOrder;

    Endian(ByteOrder byteOrder) {
      this.byteOrder = byteOrder;
    }

    public ByteOrder byteOrder() {
      return byteOrder;
    }

    @Override
    public ByteBuffer apply(ByteBuffer buffer) {
      return buffer.order(byteOrder());
    }


    @Override
    public String toString() {
      return byteOrder().toString();
    }
  }

  /**
   * Specifies the alignment of a long/int buffer over a {@code ByteBuffer}.
   */
  public enum Alignment implements UnaryOperator<ByteBuffer> {
    ALIGNED {
      @Override
      public int offset() {
        return 0;
      }
      @Override
      public ByteBuffer apply(ByteBuffer buffer) {
        return buffer;
      }
    },

    UNALIGNED {
      @Override
      public int offset() {
        return 1;
      }
      @Override
      public ByteBuffer apply(ByteBuffer buffer) {
        buffer.position(1);
        return buffer;
      }
    };

    public abstract int offset();
  }

  /**
   * Specifies the accessibility of a {@code ByteBuffer}.
   */
  public enum Access implements UnaryOperator<ByteBuffer> {
    WRITABLE {
      @Override
      public String nameComponent() {
        return "";
      }
      @Override
      public ByteBuffer apply(ByteBuffer buffer) {
        return buffer;      }
    },
    READ_ONLY {
      @Override
      public String nameComponent() {
        return "R";
      }

      @Override
      public ByteBuffer apply(ByteBuffer buffer) {
        return buffer.asReadOnlyBuffer();
      }
    };

    public abstract String nameComponent();
  }
}