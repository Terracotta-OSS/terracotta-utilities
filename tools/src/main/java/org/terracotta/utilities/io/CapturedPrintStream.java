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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Extends {@link PrintStream} to capture the output to an in-memory buffer.
 * The captured stream is available by calling {@link #getReader()}.
 * {@link StandardCharsets#UTF_8 UTF-8} is used for encoding and decoding the stream
 * content.
 *
 * @see #getInstance()
 */
public final class CapturedPrintStream extends PrintStream {

  private CapturedPrintStream() throws UnsupportedEncodingException {
    super(new LocalBufferedOutputStream(4096), false, StandardCharsets.UTF_8.name());
  }

  /**
   * Instantiates a new {@code CapturedPrintStream}.
   * @return a new {@code CapturedPrintStream}
   */
  public static CapturedPrintStream getInstance() {
    try {
      return new CapturedPrintStream();
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("Unexpected exception creating CapturedPrintStream", e);
    }
  }

  /**
   * Gets a {@code BufferedReader} over the bytes written to this {@code PrintStream}.
   * The content of this stream is not altered by this method.
   * @return a new {@code BufferedReader} over the bytes written to this stream
   * @see #reset()
   */
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(toByteArray()), StandardCharsets.UTF_8), 4096);
  }

  /**
   * Discards the captured output.
   */
  public void reset() {
    flush();
    ((LocalBufferedOutputStream)out).reset();
  }

  /**
   * Gets a copy of the content of this stream as a byte array.  The content of this
   * stream is not altered by this method.
   * @return a byte array containing a copy of the captured data
   * @see #reset()
   */
  public byte[] toByteArray() {
    flush();
    return ((LocalBufferedOutputStream)out).toByteArray();
  }

  private static final class LocalBufferedOutputStream extends BufferedOutputStream {
    LocalBufferedOutputStream(int size) {
      super(new ByteArrayOutputStream(size), size);
    }

    void reset() {
      ((ByteArrayOutputStream)out).reset();
    }

    byte[] toByteArray() {
      return ((ByteArrayOutputStream)out).toByteArray();
    }
  }
}
