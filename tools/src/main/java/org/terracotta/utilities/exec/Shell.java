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
package org.terracotta.utilities.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Utility functions for use with execution of shell commands.
 */
public final class Shell {

  private static final Logger LOGGER = LoggerFactory.getLogger(Shell.class);
  private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  /** Private niladic constructor to prevent instantiation. */
  private Shell() { }

  /**
   * Executes a command in the host and returns the command output, both stdout and stderr,
   * as a list of strings.  This method blocks until the command is complete and its output
   * consumed.
   *
   * @param consoleEncoding the {@link Charset} to use for decoding the command response
   * @param command the command to execute; this command is presented to
   *                {@link ProcessBuilder#command()} and must be properly quoted for the OS
   * @return the command result
   * @throws IOException if an error is raised while executing the command or
   *      retrieving the results
   */
  public static Result execute(Charset consoleEncoding, String... command) throws IOException {
    Process process = new ProcessBuilder()
        .command(command)
        .redirectErrorStream(true)
        .start();
    process.getOutputStream().close();

    List<String> commandLines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), consoleEncoding))) {
      String line;
      while ((line = reader.readLine()) != null) {
        commandLines.add(line);
      }
    }
    int rc;
    try {
      rc = process.waitFor();
    } catch (InterruptedException e) {
      throw new IOException(e);
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Command: {}; rc={}", Arrays.toString(command), rc);
      commandLines.forEach(l -> LOGGER.debug("    {}", l));
    }

    return new Result(rc, commandLines);
  }

  /**
   * The {@link #execute(Charset, String...)} command execution result.
   */
  // Java 21 warns of non-Seriazable fields in a Serialization object
  public static final class Result implements Iterable<String>, Serializable {
    private static final long serialVersionUID = -5555911693879268481L;

    private final transient List<String> commandLines;
    private final int exitCode;

    private Result(int exitCode, List<String> commandLines) {
      this.exitCode = exitCode;
      this.commandLines = Collections.unmodifiableList(new ArrayList<>(commandLines));
    }

    /**
     * Gets the process exit code.
     * @return the process exit code
     */
    public int exitCode() {
      return exitCode;
    }

    /**
     * Gets the list holding the captured stdout/stderr lines.
     * @return an unmodifiable list of the captured output lines
     */
    public List<String> lines() {
      return this.commandLines;
    }

    /**
     * Gets an iterator over the captured stdout/stderr lines.
     * @return a new {@code Iterator} over the stdout/stderr lines
     */
    @Override
    public Iterator<String> iterator() {
      return commandLines.iterator();
    }

    private void readObject(ObjectInputStream s) throws InvalidObjectException {
      throw new InvalidObjectException("SerializationProxy expected");
    }

    private Object writeReplace() {
      return new SerializationProxy(this);
    }

    /**
     * Internal proxy for serialization of {@link Result} instances.
     */
    private static final class SerializationProxy implements Serializable {
      private static final long serialVersionUID = -8686283741098525116L;
      private final int exitCode;
      private final ArrayList<String> commandLines;

      SerializationProxy(Result result) {
        this.exitCode = result.exitCode;
        this.commandLines = new ArrayList<>(result.commandLines);
      }

      private Object readResolve() {
        return new Result(exitCode, commandLines);
      }
    }
  }

  /**
   * Singleton identifying the {@link Charset} used for encoding/decoding interactions with the "shell"
   * command interpreter environment.  On Windows, especially, this is likely not the value of
   * {@link Charset#defaultCharset()} -- which is the {@code Charset} used for file content encoding.
   * <p>
   * For Windows, this method calls the Windows {@code chcp} command to determine the code page used
   * by a "fresh" {@code CMD} shell and returns the corresponding {@code Charset}.
   * <p>
   * The following Java system properties and values deal with encodings:
   * <dl>
   *     <dt>{@code file.encoding}</dt>
   *     <dd>The encoding used for file content:
   *         <dl>
   *             <dt>Windows</dt>
   *             <dd>Derived from {@code GetLocaleInfoEx(GetUserDefaultLCID(), LOCALE_IDEFAULTANSICODEPAGE, ...)};
   *             uses code page 1252 if the {@code GetLocaleInfoEx} request fails.</dd>
   *             <dt>Non-Windows</dt>
   *             <dd>{@code setlocale(LC_ALL, "")} is used to establish the system's default locale; then
   *             {@code setlocale(LC_CTYPE, NULL)} is used to fetch the character-handling aspects of the
   *             default locale.  The encoding value is parsed from the default locale name or obtained from
   *             {@code nl_langinfo(CODESET)} depending on the values involved.  On Mac OS X, if the value
   *             is determined to be {@code US-ASCII} and none of the environment variables {@code LANG},
   *             {@code LC_ALL}, or {@code LC_CTYPE} are set, a value of {@code UTF-8} is used instead.</dd>
   *         </dl>
   *         The value of {@link Charset#defaultCharset()} is derived from this value if it represents a
   *         supported character set.
   *     </dd>
   *     <dt>{@code sun.jnu.encoding}</dt>
   *     <dd>The "platform" encoding -- the encoding used bt the JVM to form strings presented to the OS:
   *         <dl>
   *             <dt>Windows</dt>
   *             <dd>Derived from {@code GetLocaleInfoEx(GetSystemDefaultLCID(), LOCALE_IDEFAULTANSICODEPAGE, ...)};
   *             uses encoding 1252 if the {@code GetLocaleInfoEx} request fails.</dd>
   *             <dt>Non-Windows</dt>
   *             <dd>See {@code file.encoding} above.</dd>
   *         </dl>
   *     </dd>
   *     <dt>{@code sun.stdout.encoding}</dt>
   *     <dd>The encoding used for output to STDOUT; <b>only available for "console" applications</b>:
   *         <dl>
   *             <dt>Windows</dt>
   *             <dd>Determined by {@code GetConsoleCP()}.  <i>This is actually in error -- it should rely on
   *             {@code GetConsoleOutputCP()}.</i></dd>
   *             <dt>Non-Windows</dt>
   *             <dd>Not used.</dd>
   *         </dl>
   *     </dd>
   *     <dt>{@code sun.stderr.encoding}</dt>
   *     <dd>The encoding used for output to STDERR; <b>only available for "console" applications</b>:
   *         <dl>
   *             <dt>Windows</dt>
   *             <dd>Determined by {@code GetConsoleCP()}.  <i>This is actually in error -- it should rely on
   *             {@code GetConsoleOutputCP()}.</i></dd>
   *             <dt>Non-Windows</dt>
   *             <dd>Not used.</dd>
   *         </dl>
   *     </dd>
   * </dl>
   */
  public static final class Encoding {
    /**
     * The {@code Charset} to use when interacting with the shell.
     */
    public static final Charset CHARSET = getShellEncoding();

    /**
     * Attempt to determine the "shell" character encoding -- the encoding used by the command
     * interpreter to generate output lines.
     *
     * @return the {@code Charset} representing the encoding to use for decoding of command output lines
     */
    private static Charset getShellEncoding() {
      Charset javaBasedSystemEncoding = getJavaBasedSystemEncoding();

      if (IS_WINDOWS) {
        try {
          for (String line : execute(javaBasedSystemEncoding, "cmd", "/C", "chcp")) {
            String[] parts = line.split(":\\s*");
            if (parts.length == 2 && parts[1].matches("\\d+") && Charset.isSupported(parts[1])) {
              return Charset.forName(parts[1]);
            }
          }
          LOGGER.info("Unable to determine the shell encoding from 'chcp'; using {}", javaBasedSystemEncoding);
        } catch (Exception e) {
          LOGGER.info("Unable to determine the shell encoding from 'chcp'; using {}", javaBasedSystemEncoding, e);
        }
      }

      return javaBasedSystemEncoding;     // Use the locale-based encoding
    }

    /**
     * Gets the {@code Charset} used by the JDK to interpret host command output.
     * This value may not be the correct value with which to interpret Windows command shell
     * output.
     * @return the host command encoding {@code Charset}
     */
    private static Charset getJavaBasedSystemEncoding() {
      for (String property : Arrays.asList("sun.stdout.encoding", "sun.jnu.encoding")) {
        String encoding = System.getProperty(property);
        if (encoding != null && Charset.isSupported(encoding)) {
          return Charset.forName(encoding);
        }
      }
      return Charset.defaultCharset();
    }
  }
}
