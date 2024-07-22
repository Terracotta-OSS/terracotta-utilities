/*
 * Copyright 2024 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.memory;

import org.junit.Rule;
import org.junit.Test;
import org.terracotta.org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link MemoryInfo}.
 */
public class MemoryInfoTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * Test {@link MemoryInfo#configuredMaxDirectMemory()}.
   * Full functionality of the {@code configuredMaxDirectMemory} method is dependent on setting
   * the {@code --XX:MaxDirectMemorySize} JVM option so this test should be run under at least
   * two configurations -- with the option and without the option.
   */
  @Test
  public void testDirectMemoryArg() {
    String argId = "-XX:MaxDirectMemorySize=";
    Optional<String> maxDirectArg = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(p -> p.startsWith(argId))
        .map(p -> p.substring(argId.length()))
        .findFirst();
    if (maxDirectArg.isPresent()) {
      Matcher matcher = Pattern.compile("(?<amount>\\d+)(?<scale>[kKmMgGtT]?)").matcher(maxDirectArg.get());
      if (matcher.matches()) {
        long value = Long.decode(matcher.group("amount"));
        String scale = matcher.group("scale").toLowerCase(Locale.ROOT);
        value = applyScale(scale, value);

        MemoryInfo memoryInfo = MemoryInfo.getInstance();
        assertThat(memoryInfo.configuredMaxDirectMemory(), is(value));
        assertThat(memoryInfo.configuredMaxDirectMemoryInfo().valueSource(), is("<-XX:MaxDirectMemorySize>"));
        assertThat(memoryInfo.configuredMaxDirectMemoryInfo().maxDirectMemoryAccessFault(), is(emptyString()));

      } else {
        throw new AssertionError("MaxDirectMemorySize '" + maxDirectArg.get() + "' not recognized");
      }
    } else {
      MemoryInfo memoryInfo = MemoryInfo.getInstance();
      assertThat(memoryInfo.configuredMaxDirectMemory(), is(-1L));
      assertThat(memoryInfo.configuredMaxDirectMemoryInfo().valueSource(), is("<unavailable>"));
      assertThat(memoryInfo.configuredMaxDirectMemoryInfo().maxDirectMemoryAccessFault(), emptyString());
    }
  }

  @Test
  public void testDirectMemoryInUse() {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();

    ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

    assertThat(memoryInfo.directMemoryInUse(), is(greaterThanOrEqualTo((long)buffer.capacity())));
    assertThat(memoryInfo.directBufferCount(), is(greaterThanOrEqualTo(1L)));
  }

  @Test
  public void testOffHeapMemoryInUse() throws IOException {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();

    File tempFile = temporaryFolder.newFile();
    try (RandomAccessFile mappedFile = new RandomAccessFile(tempFile, "rw")) {
      MappedByteBuffer mappedBuffer = mappedFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 16 * 1024);

      assertThat(memoryInfo.mappedMemoryInUse(), is(greaterThanOrEqualTo((long)mappedBuffer.capacity())));
      assertThat(memoryInfo.mappedBufferCount(), is(greaterThanOrEqualTo(1L)));
      assertThat(memoryInfo.offHeapMemoryInUse(), is(greaterThanOrEqualTo((long)mappedBuffer.capacity())));
      assertThat(memoryInfo.offHeapBufferCount(), is(greaterThanOrEqualTo(1L)));

      ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

      assertThat(memoryInfo.directMemoryInUse(), is(greaterThanOrEqualTo((long)buffer.capacity())));
      assertThat(memoryInfo.directBufferCount(), is(greaterThanOrEqualTo(1L)));
      assertThat(memoryInfo.offHeapMemoryInUse(), is(greaterThanOrEqualTo((long)mappedBuffer.capacity() + buffer.capacity())));
      assertThat(memoryInfo.offHeapBufferCount(), is(greaterThanOrEqualTo(2L)));
    }
  }

  @Test
  public void testEffectiveDirectMemory() {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();
    long effectiveDirectMemory = memoryInfo.effectiveMaxDirectMemory();
    MemoryInfo.MaxDirectMemoryInfo maxDirectMemoryInfo = memoryInfo.effectiveMaxDirectMemoryInfo();
    if (effectiveDirectMemory == -1) {
      assertThat(maxDirectMemoryInfo.maxDirectMemory(), is(nullValue()));
      assertThat(maxDirectMemoryInfo.maxDirectMemoryAccessFault(), is(notNullValue()));
      assertThat(maxDirectMemoryInfo.valueSource(), either(is("jdk.internal.misc.VM")).or(is("sun.misc.VM")));
    } else {
      assertThat(effectiveDirectMemory, is(greaterThan(0L)));
      assertThat(maxDirectMemoryInfo.maxDirectMemoryAccessFault(), is(emptyString()));
      assertThat(maxDirectMemoryInfo.valueSource(), is(notNullValue()));
    }
  }

  @Test
  public void testPoolBeans() {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();
    assertThat(memoryInfo.memoryPoolMXBeans(), is(not(empty())));
  }

  @Test
  public void testHeapInUse() {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();
    assertThat(memoryInfo.heapInUse(), is(greaterThan(0L)));
  }

  @Test
  public void testFormatSize() {
    assertThat(MemoryInfo.formatSize(-1L), is("-1"));
    assertThat(MemoryInfo.formatSize(0L), is("0 B"));
    assertThat(MemoryInfo.formatSize(1L), is("1 B"));
    assertThat(MemoryInfo.formatSize(1024L), is("1.0 KiB"));
    assertThat(MemoryInfo.formatSize(1536L), is("1.5 KiB"));
    assertThat(MemoryInfo.formatSize(10240L), is("10.0 KiB"));
    assertThat(MemoryInfo.formatSize(1048576L), is("1.0 MiB"));
    assertThat(MemoryInfo.formatSize(1073741824L), is("1.0 GiB"));
    assertThat(MemoryInfo.formatSize(1099511627776L), is("1.0 TiB"));
    assertThat(MemoryInfo.formatSize(1125899906842624L), is("1.0 PiB"));
    assertThat(MemoryInfo.formatSize(1152921504606846976L), is("1.0 EiB"));
    assertThat(MemoryInfo.formatSize(Long.MAX_VALUE), is("8.0 EiB"));
  }

  @SuppressWarnings("fallthrough")
  private static long applyScale(String scale, long value) {
    if (scale.isEmpty()) {
      return value;
    }

    switch (scale) {
      case "t":
        value *= 1024;
      case "g":
        value *= 1024;
      case "m":
        value *= 1024;
      case "k":
        value *= 1024;
    }
    return value;
  }
}