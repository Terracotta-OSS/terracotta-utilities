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
package org.terracotta.utilities.io;

import org.junit.Test;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Basic tests for {@link CapturedPrintStream}.
 */
public class CapturedPrintStreamTest {

  @Test
  public void testEmpty() throws Exception {
    BufferedReader reader = CapturedPrintStream.getInstance().getReader();
    assertThat(reader.readLine(), is(nullValue()));
  }

  @Test
  public void testNoLineEnd() throws Exception {
    CapturedPrintStream stream = CapturedPrintStream.getInstance();
    stream.print("foo");
    BufferedReader reader = stream.getReader();
    assertThat(reader.readLine(), is("foo"));
    assertThat(reader.readLine(), is(nullValue()));
  }

  @Test
  public void testTwoLines() throws Exception {
    CapturedPrintStream stream = CapturedPrintStream.getInstance();
    stream.println("foo");
    stream.println("bar");
    BufferedReader reader = stream.getReader();
    assertThat(reader.readLine(), is("foo"));
    assertThat(reader.readLine(), is("bar"));
    assertThat(reader.readLine(), is(nullValue()));
  }

  @Test
  public void testReset() throws Exception {
    try (CapturedPrintStream stream = CapturedPrintStream.getInstance()) {
      stream.print("abcdefghijklmno");
      try (BufferedReader reader = stream.getReader()) {
        assertThat(reader.readLine(), is("abcdefghijklmno"));
      }
      try (BufferedReader reader = stream.getReader()) {
        assertThat(reader.readLine(), is("abcdefghijklmno"));
      }

      stream.reset();
      try (BufferedReader reader = stream.getReader()) {
        assertThat(reader.readLine(), is(nullValue()));
      }

      stream.println("content");
      stream.reset();
      stream.println("new content");
      try (BufferedReader reader = stream.getReader()) {
        assertThat(reader.readLine(), is("new content"));
      }
    }
  }

  @Test
  public void testToByteArray() {
    try (CapturedPrintStream stream = CapturedPrintStream.getInstance()) {
      String quote = "Now is the time for all good men to come to the aid of their country.";
      stream.println(quote);

      ByteBuffer buffer = StandardCharsets.UTF_8.encode(quote + System.lineSeparator());
      byte[] expected = new byte[buffer.limit()];
      buffer.get(expected);
      assertThat(stream.toByteArray(), is(expected));

      stream.reset();
      assertThat(stream.toByteArray(), is(new byte[0]));
    }
  }
}