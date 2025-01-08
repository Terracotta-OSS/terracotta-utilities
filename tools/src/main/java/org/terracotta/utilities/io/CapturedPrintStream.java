/*
 * Copyright 2020-2023 Terracotta, Inc., a Software AG company.
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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/**
 * Extends {@link PrintStream} to capture the output to an in-memory or file-backed buffer.
 * The captured stream is available by calling {@link #getReader()}.
 * {@link StandardCharsets#UTF_8 UTF-8} is used for encoding and decoding the stream
 * content.
 *
 * @see #getInstance()
 * @see #getInstance(Path)
 * @see #getInstance(Path, boolean)
 */
public abstract class CapturedPrintStream extends PrintStream {

  volatile PrintStream delegate;

  private CapturedPrintStream(PrintStream delegate) throws UnsupportedEncodingException {
    super(new NullOutputStream(), false, UTF_8.name());
    this.delegate = delegate;
  }

  /**
   * Instantiates a new {@code CapturedPrintStream} using file as a buffer and a caller-specified auto-flush setting.
   * @param filePath {@code Path} to file used to capture stream output
   * @param autoFlush {@code true} enables auto-flushing of the capture {@code PrintStream};
   *          {@code false} disables auto-flushing of the capture {@code PrintStream}
   * @return a new {@code CapturedPrintStream}
   * @throws IOException if an error is raised opening an output stream over {@code filePath}
   *
   * @see PrintStream#PrintStream(OutputStream, boolean, String)
   */
  public static CapturedPrintStream getInstance(Path filePath, boolean autoFlush) throws IOException {
    FileOutputStream fos = FileCapturedPrintStream.open(Objects.requireNonNull(filePath, "filePath"), CREATE, APPEND);
    try {
      return new FileCapturedPrintStream(filePath, fos, autoFlush);
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("Unexpected exception creating CapturedPrintStream", e);
    }
  }

  /**
   * Instantiates a new {@code CapturedPrintStream} using file as a buffer and auto-flush enabled.
   * <p>
   * This method is equivalent to <pre>{@code CapturedPrintStream.getInstance(filePath, true)}</pre>
   * @param filePath {@code Path} to file used to capture stream output
   * @return a new {@code CapturedPrintStream}
   * @throws IOException if an error is raised opening an output stream over {@code filePath}
   */
  public static CapturedPrintStream getInstance(Path filePath) throws IOException {
    return CapturedPrintStream.getInstance(filePath, true);
  }

  /**
   * Instantiates a new {@code CapturedPrintStream} using an in-memory buffer.
   * @return a new {@code CapturedPrintStream}
   */
  public static CapturedPrintStream getInstance() {
    try {
      return new MemoryCapturedPrintStream(new LocalBufferedOutputStream(4096));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("Unexpected exception creating CapturedPrintStream", e);
    }
  }

  /**
   * Swaps in a new {@code PrintStream} delegate.
   * @param newDelegate the {@code PrintStream} to use
   */
  final void swapDelegate(PrintStream newDelegate) {
    delegate.flush();
    delegate.close();
    delegate = newDelegate;
  }

  /**
   * Gets a {@code BufferedReader} over the bytes written to this {@code PrintStream}.
   * The content of this stream is not altered by this method.
   * @return a new {@code BufferedReader} over the bytes written to this stream
   * @see #reset()
   */
  public abstract BufferedReader getReader();

  /**
   * Discards the captured output.
   */
  public abstract void reset();

  /**
   * Gets a copy of the content of this stream as a byte array.  The content of this
   * stream is not altered by this method.
   * @return a byte array containing a copy of the captured data
   * @see #reset()
   */
  public abstract byte[] toByteArray();

  @Override
  public synchronized void flush() {
    delegate.flush();
  }

  @Override
  public synchronized void close() {
    delegate.close();
  }

  @Override
  public synchronized boolean checkError() {
    return delegate.checkError();
  }

  @Override
  public synchronized void write(int b) {
    delegate.write(b);
  }

  @Override
  public synchronized void write(byte[] buf, int off, int len) {
    delegate.write(buf, off, len);
  }

  @Override
  public synchronized void print(boolean b) {
    delegate.print(b);
  }

  @Override
  public synchronized void print(char c) {
    delegate.print(c);
  }

  @Override
  public synchronized void print(int i) {
    delegate.print(i);
  }

  @Override
  public synchronized void print(long l) {
    delegate.print(l);
  }

  @Override
  public synchronized void print(float f) {
    delegate.print(f);
  }

  @Override
  public synchronized void print(double d) {
    delegate.print(d);
  }

  @Override
  public synchronized void print(char[] s) {
    delegate.print(s);
  }

  @Override
  public synchronized void print(String s) {
    delegate.print(s);
  }

  @Override
  public synchronized void print(Object obj) {
    delegate.print(obj);
  }

  @Override
  public synchronized void println() {
    delegate.println();
  }

  @Override
  public synchronized void println(boolean x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(char x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(int x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(long x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(float x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(double x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(char[] x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(String x) {
    delegate.println(x);
  }

  @Override
  public synchronized void println(Object x) {
    delegate.println(x);
  }

  @Override
  public synchronized PrintStream printf(String format, Object... args) {
    delegate.printf(format, args);
    return this;
  }

  @Override
  public synchronized PrintStream printf(Locale l, String format, Object... args) {
    delegate.printf(l, format, args);
    return this;
  }

  @Override
  public synchronized PrintStream format(String format, Object... args) {
    delegate.format(format, args);
    return this;
  }

  @Override
  public synchronized PrintStream format(Locale l, String format, Object... args) {
    delegate.format(l, format, args);
    return this;
  }

  @Override
  public synchronized PrintStream append(CharSequence csq) {
    delegate.append(csq);
    return this;
  }

  @Override
  public synchronized PrintStream append(CharSequence csq, int start, int end) {
    delegate.append(csq, start, end);
    return this;
  }

  @Override
  public synchronized PrintStream append(char c) {
    delegate.append(c);
    return this;
  }

  @Override
  public synchronized void write(byte[] b) throws IOException {
    delegate.write(b);
  }

  /**
   * A {@link PrintStream} to capture to a file-backed buffer.
   * The captured stream is available by calling {@link #getReader()}.
   * {@link StandardCharsets#UTF_8 UTF-8} is used for encoding and decoding the stream content.
   * @see CapturedPrintStream#getInstance(Path)
   */
  public static final class FileCapturedPrintStream extends CapturedPrintStream {
    private final Path filePath;
    private final boolean autoFlush;

    private volatile FileDescriptor fd;

    private FileCapturedPrintStream(Path filePath, FileOutputStream fileOutputStream, boolean autoFlush) throws IOException {
      super(new PrintStream(new BufferedOutputStream(fileOutputStream, 4096), autoFlush, UTF_8.name()));
      this.filePath = filePath;
      this.autoFlush = autoFlush;
      this.fd = fileOutputStream.getFD();
    }

    private static FileOutputStream open(Path filePath, OpenOption... options) throws IOException {
      Set<OpenOption> specifiedOptions = new HashSet<>(Arrays.asList(options));

      if (specifiedOptions.contains(CREATE)) {
        // Create the file if necessary
        try {
          Files.createFile(filePath);
        } catch (FileAlreadyExistsException e) {
          // file exists -- will be re-used
        }
      }

      boolean append = !specifiedOptions.contains(TRUNCATE_EXISTING);
      return new FileOutputStream(filePath.toFile(), append);
    }

    /**
     * Ensures all buffered content is flushed to disk.
     * @throws IOException if an error is raised from the {@link FileDescriptor#sync()} call
     */
    public void sync() throws IOException {
      flush();
      fd.sync();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method calls {@link #flush()} and {@link FileDescriptor#sync()} to ensure all captured
     * output is available to the reader.
     *
     * @throws UncheckedIOException for a {@link FileCapturedPrintStream} if an {@link IOException} is
     *        thrown when opening the file for reading
     */
    @Override
    public synchronized BufferedReader getReader() {
      try {
        flush();
        fd.sync();
        return Files.newBufferedReader(filePath);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method <i>closes</i> the previous {@code PrintStream} delegate iff the new delegate is successfully created.
     *
     * @throws UncheckedIOException if an error is raised while opening the new delegate
     */
    @Override
    public synchronized void reset() {
      try {
        FileOutputStream fos = open(filePath, CREATE, TRUNCATE_EXISTING);
        swapDelegate(new PrintStream(new BufferedOutputStream(fos, 4096), autoFlush, UTF_8.name()));
        this.fd = fos.getFD();
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError("Unexpected exception creating file buffer for CapturedPrintStream", e);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method calls {@link #flush()} and {@link FileDescriptor#sync()} to ensure all captured
     * output is available to the reader.
     *
     * @throws UncheckedIOException if an error is raised while reading the backing file
     */
    @Override
    public synchronized byte[] toByteArray() {
      try {
        flush();
        fd.sync();
        return Files.readAllBytes(filePath);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * A {@link PrintStream} to capture to a memory-based buffer.
   * The captured stream is available by calling {@link #getReader()}.
   * {@link StandardCharsets#UTF_8 UTF-8} is used for encoding and decoding the stream content.
   * @see CapturedPrintStream#getInstance()
   */
  public static final class MemoryCapturedPrintStream extends CapturedPrintStream {

    private final LocalBufferedOutputStream out;

    private MemoryCapturedPrintStream(LocalBufferedOutputStream out) throws UnsupportedEncodingException {
      super(new PrintStream(out, false, UTF_8.name()));
      this.out = out;
    }

    @Override
    public synchronized BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(toByteArray()), UTF_8), 4096);
    }

    @Override
    public synchronized void reset() {
      flush();
      out.reset();
    }

    @Override
    public synchronized byte[] toByteArray() {
      flush();
      return out.toByteArray();
    }
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
