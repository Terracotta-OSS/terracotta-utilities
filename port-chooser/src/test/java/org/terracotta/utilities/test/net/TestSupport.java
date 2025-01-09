/*
 * Copyright 2020-2022 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;
import org.terracotta.utilities.test.runtime.Os;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Static methods for to aid testing of {@link NetStat} and {@link PortManager}.
 */
public final class TestSupport {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestSupport.class);

  private static final String[] SUDO_VERSION = new String[] { "sudo", "-V" };
  private static final String[] LSOF_VERSION = new String[] { "lsof", "-v" };
  private static final String[] LSOF_TCP = new String[] { "lsof", "-nP", "-iTCP", "-F", "n" };
  private static final String[] SUDO_PREFIX = new String[] { "sudo", "--non-interactive", "--" };

  private TestSupport() {}

  /**
   * Determines if {@code sudo --non-interactive -- lsof -nP -iTCP ...} can be used to get TCP port information.
   * This method ensures the following:
   * <ol>
   *   <li>{@code sudo} is available in {@code PATH}</li>
   *   <li>{@code lsof} is available in {@code PATH}</li>
   *   <li>{@code sudo --non-interactive -- lsof -nP -iTCP -F n} succeeds</li>
   *   <li>{@code sudo --non-interactive -- lsof -nP -iTCP -F n} returns results</li>
   * </ol>
   * ({@code lsof -nP -iTCP} will fail to return results if the platform implementation of {@code /proc} does
   * not include sufficient for the network stack.)
   *
   * @return {@code true} if {@code sudo --non-interactive -- lsof -nP -iTCP} returns expected results;
   *    {@code false} otherwise
   */
  public static boolean sudoLsofWorks() {

    /*
     * sudo / lsof are only used on Linux -- see org.terracotta.utilities.test.net.NetStat.Platform.LINUX
     */
    if (!Os.isLinux()) {
      return true;
    }

    /*
     * Ensure 'sudo' is accessible from PATH by getting its version.
     */
    if (!checkCommand(SUDO_VERSION)) {
      return false;
    }

    /*
     * Ensure 'lsof' is accessible from PATH by getting its version.
     */
    if (!checkCommand(LSOF_VERSION)) {
      return false;
    }

    /*
     * Both 'sudo' and 'lsof' are available on PATH.  Now check to see if 'sudo --non-interactive -- lsof -nP -iTCP -F n'
     * works and returns any useful results.
     */
    String[] sudoLsofTcp = Arrays.copyOf(SUDO_PREFIX, SUDO_PREFIX.length + LSOF_TCP.length);
    System.arraycopy(LSOF_TCP, 0, sudoLsofTcp, SUDO_PREFIX.length, LSOF_TCP.length);
    return tryLsof(sudoLsofTcp);
  }

  /**
   * Determines if {@code lsof -nP -iTCP ...} can be used to get TCP port information.
   * This method ensures the following:
   * <ol>
   *   <li>{@code lsof} is available in {@code PATH}</li>
   * </ol>
   * ({@code lsof -nP -iTCP} will fail to return results if the platform implementation of {@code /proc} does
   * not include sufficient for the network stack.)
   *
   * @return {@code true} if {@code lsof -nP -iTCP} returns expected results;
   *    {@code false} otherwise
   */
  public static boolean lsofWorks() {

    /*
     * lsof is only used on Linux -- see org.terracotta.utilities.test.net.NetStat.Platform.LINUX
     */
    if (!Os.isLinux()) {
      return true;
    }

    /*
     * Ensure 'lsof' is accessible from PATH by getting its version.
     */
    if (!checkCommand(LSOF_VERSION)) {
      return false;
    }

    /*
     * 'lsof' is available on PATH.  Now check to see if 'lsof -nP -iTCP -F n'
     * works and returns any useful results.
     */
    return tryLsof(LSOF_TCP);
  }

  private static boolean tryLsof(String[] lsofCommand) {
    try {
      Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, lsofCommand);
      if (result.exitCode() == 0) {
        /*
         * '-F n' causes the output to be "parsable" and include only groups of process IDs, FDs, and network numbers.
         * Ensure we actually got process groups with network numbers.
         */
        Map<String, List<String>> portsPerProcess = new LinkedHashMap<>();
        List<String> currentProcess = null;
        for (String line : result) {
          switch (line.charAt(0)) {
            case 'p':
              currentProcess = portsPerProcess.get(line);
              if (currentProcess != null) {
                LOGGER.warn("Unexpected output from {}: multiple '{}'{}",
                    Arrays.toString(lsofCommand), escape(line), collectEscapedLines(result));
                return false;
              }
              currentProcess = new ArrayList<>();
              portsPerProcess.put(line, currentProcess);
              break;
            case 'f':
              if (currentProcess == null) {
                LOGGER.warn("Unexpected output from {}; missing process '{}'{}",
                    Arrays.toString(lsofCommand), escape(line), collectEscapedLines(result));
                return false;
              }
              break;
            case 'n':
              if (currentProcess == null) {
                LOGGER.warn("Unexpected output from {}; missing process '{}'{}",
                    Arrays.toString(lsofCommand), escape(line), collectEscapedLines(result));
                return false;
              }
              currentProcess.add(line);
              break;
            default:
              LOGGER.warn("Unexpected output from {}: '{}'{}",
                  Arrays.toString(lsofCommand), escape(line), collectEscapedLines(result));
              return false;
          }
        }

        if (portsPerProcess.isEmpty() || portsPerProcess.values().stream().allMatch(List::isEmpty)) {
          LOGGER.debug("{} returned no ports:{}", Arrays.toString(lsofCommand), collectEscapedLines(result));
          return false;
        }

        return true;

      } else {
        LOGGER.debug("Failed to run {}; rc={}{}", Arrays.toString(lsofCommand), result.exitCode(), collectEscapedLines(result));
        return false;
      }
    } catch (IOException e) {
      LOGGER.debug("Failed to run {}", Arrays.toString(lsofCommand), e);
      return false;
    }
  }

  private static String collectEscapedLines(Iterable<String> lines) {
    String messages = StreamSupport.stream(lines.spliterator(), false)
        .map(TestSupport::escape)
        .collect(Collectors.joining("\n    "));
    if (!messages.isEmpty()) {
      messages = "\n    " + messages;
    }
    return messages;
  }

  private static boolean checkCommand(String[] command) {
    try {
      Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, command);
      if (result.exitCode() != 0) {
        String messages = "\n    " + String.join("\n    ", result);
        LOGGER.debug("Failed to run {}; rc={}{}", Arrays.toString(command), result.exitCode(), messages);
        return false;
      }
    } catch (IOException e) {
      LOGGER.debug("Failed to run {}", Arrays.toString(command), e);
      return false;
    }
    return true;
  }


  private static String escape(CharSequence chars) {
    StringBuilder sb = new StringBuilder(chars.length() * 2);
    for (int i = 0; i < chars.length(); i++) {
      char c = chars.charAt(i);
      if (c <= 0x001F || c == '\\' || c == 0x007F) {
        sb.append('\\');
        switch (c) {
          case 0x0008:
            sb.append('b');
            break;
          case 0x0009:
            sb.append('t');
            break;
          case 0x000A:
            sb.append('n');
            break;
          case 0x000C:
            sb.append('f');
            break;
          case 0x000D:
            sb.append('r');
            break;
          case '\\':
            sb.append('\\');
            break;
          default:
            sb.append(Integer.toOctalString(c));
        }
      } else if (c < 0x007F) {
        sb.append(c);
      } else {
        sb.append("\\u").append(String.format("%04x", (int)c));
      }
    }
    return sb.toString();
  }
}
