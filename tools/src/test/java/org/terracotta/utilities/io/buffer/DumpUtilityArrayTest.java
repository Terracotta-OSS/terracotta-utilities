/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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

import org.junit.Test;
import org.terracotta.utilities.io.CapturedPrintStream;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for the {@link DumpUtility} methods dumping {@code byte[]}.
 */
public class DumpUtilityArrayTest extends AbstractDumpUtilityTest {

  /**
   * Tests an zero-length (empty) array.
   */
  @Test
  public void testEmpty() throws Exception {
    byte[] content = new byte[0];
    List<String> lines = captureDump(content);
    assertThat(extractDump(lines, false), is(empty()));
  }

  /**
   * Tests an array of all zeros.
   */
  @Test
  public void testAllZeros() throws Exception {
    byte[] content = zeros(4096);
    List<String> lines = captureDump(content);
    assertThat(lines.get(0), startsWith("00000000 "));
    ByteBuffer observed = parseDump(extractDump(lines, false), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Tests a longer dump with non-zero content.
   */
  @Test
  public void testNonZero() throws Exception {
    byte[] content = latin1Repeat(16);
    List<String> lines = captureDump(content);
    ByteBuffer observed = parseDump(extractDump(lines, false), content.length);
    assertThat(ByteBuffer.wrap(content), is(observed));
  }

  /**
   * Dumps the byte array provided capturing the output.
   * @param bytes the {@code byte[]} to dump
   * @return the dumped lines
   * @throws Exception if an error is raised retrieving the dump lines
   */
  private List<String> captureDump(byte[] bytes) throws Exception {
    try (CapturedPrintStream printStream = CapturedPrintStream.getInstance()) {
      new DumpUtility(printStream).dump(bytes);
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
}
