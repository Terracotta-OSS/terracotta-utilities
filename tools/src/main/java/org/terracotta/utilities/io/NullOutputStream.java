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

import java.io.IOException;
import java.io.OutputStream;

/**
 * An {@code OutputStream} which discards all data written to it.
 * For Java 11 and beyond, use {@code OutputStream.nullOutputStream()}.
 */
public class NullOutputStream extends OutputStream {

  private volatile boolean closed = false;

  @Override
  public void write(int b) throws IOException {
    checkClosed();
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    int arrayLength = b.length;
    if ((arrayLength | off | len) < 0 || len > arrayLength - off) {
      throw new IndexOutOfBoundsException();
    }
    checkClosed();
  }

  @Override
  public void close() {
    closed = true;
  }

  private void checkClosed() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
  }
}
