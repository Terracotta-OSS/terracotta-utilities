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
package org.terracotta.utilities.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.terracotta.org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Basic tests for {@link CapturedPrintStream}.
 */
@RunWith(Enclosed.class)
public class CapturedPrintStreamTest {

  public static class NonParameterizedTests {
    @Test
    public void testNullFile() {
      assertThat(() -> CapturedPrintStream.getInstance(null), threw(instanceOf(NullPointerException.class)));
      assertThat(() -> CapturedPrintStream.getInstance(null, false), threw(instanceOf(NullPointerException.class)));
    }

    @Test
    public void testPrintStreamDelegate() throws IOException {
      PrintStream delegate = mock(PrintStream.class);
      CapturedPrintStream testInstance = CapturedPrintStream.getInstance();
      testInstance.swapDelegate(delegate);

      testInstance.flush();
      verify(delegate).flush();

      testInstance.close();
      verify(delegate).close();

      testInstance.checkError();
      verify(delegate).checkError();

      testInstance.write(17);
      verify(delegate).write(17);

      byte[] bytes = new byte[0];
      testInstance.write(bytes, 5, 18);
      verify(delegate).write(bytes, 5, 18);

      testInstance.print(true);
      verify(delegate).print(true);

      testInstance.print('D');
      verify(delegate).print('D');

      testInstance.print(27);
      verify(delegate).print(27);

      testInstance.print(2414682040998L);   // Dedekind 7
      verify(delegate).print(2414682040998L);

      testInstance.print(1.61803398874989484820d);   // Golden Ratio
      verify(delegate).print(1.61803398874989484820d);

      char[] charArray = "reveal".toCharArray();
      testInstance.print(charArray);
      verify(delegate).print(charArray);

      testInstance.print("nonsense");
      verify(delegate).print("nonsense");

      Object o = new Object();
      testInstance.print(o);
      verify(delegate).print(o);

      testInstance.println();
      verify(delegate).println();

      testInstance.println(true);
      verify(delegate).println(true);

      testInstance.println('D');
      verify(delegate).println('D');

      testInstance.println(27);
      verify(delegate).println(27);

      testInstance.println(2414682040998L);   // Dedekind 7
      verify(delegate).println(2414682040998L);

      testInstance.println(1.61803398874989484820d);   // Golden Ratio
      verify(delegate).println(1.61803398874989484820d);

      testInstance.println(charArray);
      verify(delegate).println(charArray);

      testInstance.println("nonsense");
      verify(delegate).println("nonsense");

      testInstance.println(o);
      verify(delegate).println(o);

      testInstance.printf("%d", 83);
      verify(delegate).printf("%d", 83);

      testInstance.printf(Locale.ROOT, "%d", 97);
      verify(delegate).printf(Locale.ROOT, "%d", 97);

      testInstance.format("%d", 83);
      verify(delegate).format("%d", 83);

      testInstance.format(Locale.ROOT, "%d", 97);
      verify(delegate).format(Locale.ROOT, "%d", 97);

      testInstance.append("sequence");
      verify(delegate).append("sequence");

      testInstance.append("string", 4, 6);
      verify(delegate).append("string", 4, 6);

      testInstance.append('C');
      verify(delegate).append('C');

      testInstance.write(bytes);
      verify(delegate).write(bytes);
    }
  }

  @RunWith(Parameterized.class)
  public static class ParameterizedTests {

    public enum Case {
      MEMORY {
        @Override
        public CapturedPrintStream getCaptureStream(ParameterizedTests test) {
          return CapturedPrintStream.getInstance();
        }
      },
      FILE_BACKED {
        @Override
        public CapturedPrintStream getCaptureStream(ParameterizedTests test) throws IOException {
          return CapturedPrintStream.getInstance(test.folder.newFile(test.testName.getMethodName()).toPath());
        }
      },
      FILE_BACKED_NO_AUTOFLUSH {
        @Override
        public CapturedPrintStream getCaptureStream(ParameterizedTests test) throws IOException {
          return CapturedPrintStream.getInstance(test.folder.newFile(test.testName.getMethodName()).toPath(), false);
        }
      };

      public abstract CapturedPrintStream getCaptureStream(ParameterizedTests test) throws Exception;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Case> data() {
      return EnumSet.allOf(Case.class);
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Rule
    public final TestName testName = new TestName();

    @Parameterized.Parameter
    public Case testCase;

    @Test
    public void testEmpty() throws Exception {
      try (CapturedPrintStream captureStream = testCase.getCaptureStream(this)) {
        BufferedReader reader = captureStream.getReader();
        assertThat(reader.readLine(), is(nullValue()));
      }
    }

    @Test
    public void testNoLineEnd() throws Exception {
      try (CapturedPrintStream stream = testCase.getCaptureStream(this)) {
        stream.print("foo");
        BufferedReader reader = stream.getReader();
        assertThat(reader.readLine(), is("foo"));
        assertThat(reader.readLine(), is(nullValue()));
      }
    }

    @Test
    public void testTwoLines() throws Exception {
      try (CapturedPrintStream stream = testCase.getCaptureStream(this)) {
        stream.println("foo");
        stream.println("bar");
        BufferedReader reader = stream.getReader();
        assertThat(reader.readLine(), is("foo"));
        assertThat(reader.readLine(), is("bar"));
        assertThat(reader.readLine(), is(nullValue()));
      }
    }

    @Test
    public void testReset() throws Exception {
      try (CapturedPrintStream stream = testCase.getCaptureStream(this)) {
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
    public void testToByteArray() throws Exception {
      try (CapturedPrintStream stream = testCase.getCaptureStream(this)) {
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
}