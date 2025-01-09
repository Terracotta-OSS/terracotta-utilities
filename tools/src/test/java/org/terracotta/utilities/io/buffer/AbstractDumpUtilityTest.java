/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.io.buffer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.terracotta.utilities.test.ByteCodeVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;

/**
 * Base for tests of {@link DumpUtility}.
 */
public class AbstractDumpUtilityTest {

  protected static final boolean DIAGNOSTICS_ENABLED = Boolean.getBoolean("dumputility.diagnostics.enable");

  /**
   * Pattern for validating/parsing the dump output.
   */
  private static final Pattern DUMP_LINE =
      Pattern.compile("(?<startAddr>\\p{XDigit}{8})" +
          "((" +
            "-(?<endAddr>\\p{XDigit}{8})\\s+duplicates above.*" +             // "duplicates above"
          ")|(" +
            "\\s{2}" +
              "(?<hexdata>(((\\p{XDigit}{2}){1,4})\\s+){1,8})" +              // hex dump data
            "\\*" +
              "(?<chardata>(\\p{Print}{16}\\s\\p{Print}{16}))" +              // char dump data
            "\\*" +
          "))");

  @Rule
  public final TestName testName = new TestName();

  @Before
  public void prepare() {
    if (DIAGNOSTICS_ENABLED) {
      System.out.format("%n%s%n", testName.getMethodName());
      System.out.format("DumpUtility.class compiled with %s%n", ByteCodeVersion.fromClass(DumpUtility.class));
      System.out.format("Current JVM = %s%n", System.getProperty("java.runtime.version", System.getProperty("java.version")));
      System.out.format("JVM options: %s%n", ManagementFactory.getRuntimeMXBean().getInputArguments());
    }
  }

  protected static byte[] zeros(int length) {
    return new byte[length];
  }

  protected static byte[] latin1Repeat(int copies) {
    byte[] latin1 = new byte[256];
    for (int i = 0; i < latin1.length; i++) {
      latin1[i] = (byte)i;
    }

    byte[] content = new byte[latin1.length * copies];
    for (int i = 0; i < copies; i++) {
      System.arraycopy(latin1, 0, content, i * latin1.length, latin1.length);
    }

    return content;
  }

  /**
   * Converts a string of hexadecimal digits to a byte array.
   *
   * @param hexString the hexadecimal digits to convert; is padded with a zero if the length
   *                  is not a multiple of two
   * @return a new {@code byte[]}; will be length zero if {@code hexString} is empty
   * @throws NumberFormatException if {@code hexString} contains characters other than hex digits
   */
  protected static byte[] toBytes(String hexString) throws NumberFormatException {
    if (Objects.requireNonNull(hexString, "Value must be non-null").isEmpty()) {
      return new byte[0];
    }

    if (hexString.length() % 2 == 1) {
      hexString += '0';
    }
    return Arrays.stream(hexString.split("(?<=\\G.{2})"))
        .map(o -> Integer.parseInt(o, 16))
        .collect(ByteArrayOutputStream::new, ByteArrayOutputStream::write, (a, b) -> {
          try {
            b.writeTo(a);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }).toByteArray();
  }

  /**
   * Gets a dump line sublist starting with the first dump data line.
   *
   * @param dumpLines the dump lines to examine
   * @param require   if {@code true}, an assertion failure is raised if the "first"
   *                  dump data line is not found; if {@code false}, an empty sublist
   *                  is returned if the "first" data line is not found
   * @return the sublist holding the data dumped
   */
  protected List<String> extractDump(List<String> dumpLines, boolean require) {
    int lastLineToCheck = Math.min(5, dumpLines.size() - 1);
    int dLine;
    for (dLine = 0; dLine <= lastLineToCheck; dLine++) {
      if (dumpLines.get(dLine).startsWith("00000000 ")) {
        break;
      }
    }

    if (require) {
      assertThat("Failed to find dump within " + (lastLineToCheck + 1) + " lines", dLine,
          is(lessThanOrEqualTo(lastLineToCheck)));
      assertThat("Dump is missing the descriptor line; dLine=" + dLine, dLine, is(greaterThan(1)));
      assertThat("Dump is missing the descriptor line; dLine=" + dLine, dumpLines.get(dLine - 1),
          matchesPattern("\\s*ByteOrder=.*"));
    }

    if (dLine > lastLineToCheck) {
      return dumpLines.subList(dLine, dLine);
    } else {
      return dumpLines.subList(dLine, dumpLines.size());
    }
  }

  /**
   * Reconstructs a {@code ByteBuffer} from a dump line collection.
   *
   * @param dumpLines   the lines from which to reconstruct the buffer
   * @param rawCapacity the expected byte capacity of the reconstructed buffer
   * @return the reconstructed buffer
   */
  protected ByteBuffer parseDump(List<String> dumpLines, int rawCapacity) {
    ByteBuffer buffer = ByteBuffer.allocate(rawCapacity);
    byte[] lastSegment = null;
    for (String line : dumpLines) {
      Matcher matcher = DUMP_LINE.matcher(line);
      assertTrue(matcher.matches());

      int startAddr = Integer.parseInt(matcher.group("startAddr"), 16);
      assertThat(startAddr, is(equalTo(buffer.position())));

      String token = matcher.group("endAddr");
      if (token != null) {
        assertThat("Duplication line found without last full segment: null", lastSegment, is(notNullValue()));
        assertThat("Duplication line found without last full segment: " + lastSegment.length, lastSegment.length,
            is(32));
        int endAddr = Integer.parseInt(token, 16);
        int span = (endAddr + 1) - startAddr;
        assertThat("Duplication line span is not an integral number of segments: " + span % 32, span % 32, is(0));
        int segmentCount = span / 32;
        for (int i = 0; i < segmentCount; i++) {
          buffer.put(lastSegment);
        }
      } else {
        String hexContent = matcher.group("hexdata");
        assertThat(hexContent, is(notNullValue()));
        lastSegment = AbstractDumpUtilityTest.toBytes(hexContent.trim().replace(" ", ""));
        buffer.put(lastSegment);
      }
    }

    assertThat(buffer.position(), is(buffer.capacity()));
    buffer.rewind();
    return buffer;
  }
}
