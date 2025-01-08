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
package org.terracotta.utilities.io;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Tests the basics of {@link NullOutputStream}.
 */
public class NullOutputStreamTest {

  @Test
  public void testWriteInt() throws Exception {
    OutputStream stream = new NullOutputStream();
    stream.write('a');

    stream.close();
    assertThat(() -> stream.write('a'), threw(instanceOf(IOException.class)));
  }

  @Test
  public void testByteArray() throws Exception {
    OutputStream stream = new NullOutputStream();
    stream.write(new byte[0]);
    stream.write(new byte[] {0, 1});

    stream.close();
    assertThat(() -> stream.write(new byte[0]), threw(instanceOf(IOException.class)));
  }

  @Test
  public void testByteArrayRange() throws Exception {
    OutputStream stream = new NullOutputStream();
    stream.write(new byte[0], 0, 0);
    stream.write(new byte[] {0, 1}, 1, 1);
    assertThat(() -> stream.write(new byte[0], 1, 1), threw(instanceOf(IndexOutOfBoundsException.class)));
    assertThat(() -> stream.write(null, 0, 1), threw(instanceOf(NullPointerException.class)));

    stream.close();
    assertThat(() -> stream.write(new byte[0], 0, 0), threw(instanceOf(IOException.class)));
  }

  @Test
  public void testFlush() throws Exception {
    OutputStream stream = new NullOutputStream();
    stream.flush();
  }
}