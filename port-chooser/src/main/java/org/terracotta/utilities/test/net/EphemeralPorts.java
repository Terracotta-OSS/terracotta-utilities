/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test.net;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;
import org.terracotta.utilities.test.runtime.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// http://www.ncftp.com/ncftpd/doc/misc/ephemeral_ports.html can tell you alot about what this class is about

// Imported from https://github.com/Terracotta-OSS/terracotta-core/blob/master/common/src/main/java/com/tc/net/EphemeralPorts.java
public class EphemeralPorts {

  private static final Logger LOGGER = LoggerFactory.getLogger(EphemeralPorts.class);

  private static final Range IANA_DEFAULT_RANGE = new Range(49152, 65535);

  private static Range range = null;

  public static synchronized Range getRange() {
    if (range == null) {
      range = findRange();
    }
    return range;
  }

  private static Range findRange() {
    if (Os.isLinux()) { return new Linux().getRange(); }
    if (Os.isMac()) { return new Mac().getRange(); }
    if (Os.isWindows()) { return new Windows().getRange(); }
    if (Os.isSolaris()) { return new SolarisAndHPUX(false).getRange(); }
    if (Os.isAix()) { return new Aix().getRange(); }
    if (Os.isHpux()) { return new SolarisAndHPUX(true).getRange(); }

    throw new AssertionError("No support for this OS: " + Os.getOsName());
  }

  /**
   * Execute a command and return the first response line.
   * @param cmd the command to execute
   * @return the first response line
   * @throws IndexOutOfBoundsException if the command response is empty
   * @throws IOException if an error is encountered while executing the command
   */
  private static String firstLine(String... cmd) throws IOException {
    Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, cmd);
    if (result.exitCode() == 0) {
      return result.lines().get(0);
    } else {
      throw new IOException(String.format("Command %s failed; rc=%d:%n    %s",
          Arrays.toString(cmd), result.exitCode(), String.join("\n    ", result)));
    }
  }

  /**
   * Execute a command and return an {@code Iterable} with all response lines.
   * @param cmd the command to execute
   * @return an {@code Iterable} over all response lines
   * @throws IOException if an error is encountered while executing the command
   */
  private static Iterable<String> allLines(String... cmd) throws IOException {
    Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, cmd);
    if (result.exitCode() == 0) {
      return result;
    } else {
      throw new IOException(String.format("Command %s failed; rc=%d:%n    %s",
          Arrays.toString(cmd), result.exitCode(), String.join("\n    ", result)));
    }
  }

  /**
   * Execute a command and return a {@code Properties} instance composed from the output.
   * @param cmd the command to execute
   * @return a new {@code Properties} instance
   * @throws IOException if an error is encountered while executing the command or loading
   *        the {@code Properties} instance
   */
  private static Properties asProperties(String... cmd) throws IOException {
    Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, cmd);
    if (result.exitCode() == 0) {
      Properties props = new Properties();
      try {
        props.load(new StringReader(String.join(System.lineSeparator(), result)));
      } catch (IOException e) {
        throw new IOException(String.format("Failed to load Properties from %s:%n    %s",
            Arrays.toString(cmd), String.join("\n    ", result)), e);
      }
      return props;
    } else {
      throw new IOException(String.format("Command %s failed; rc=%d:%n    %s",
          Arrays.toString(cmd), result.exitCode(), String.join("\n    ", result)));
    }
  }

  public static class Range {
    private final int upper;
    private final int lower;

    Range(int lower, int upper) {
      this.lower = lower;
      this.upper = upper;
    }

    /**
     * The last port assigned to the ephemeral/dynamic port range.
     * @return the upper bound of the ephemeral port range
     */
    public int getUpper() {
      return upper;
    }

    /**
     * The first port assigned to the ephemeral/dynamic port range.
     * @return the lower bound of the ephemeral port range
     */
    public int getLower() {
      return lower;
    }

    public boolean isInRange(int num) {
      return num >= lower && num <= upper;
    }

    @Override
    public String toString() {
      return lower + " " + upper;
    }
  }

  private interface RangeGetter {
    Range getRange();
  }

  private static class SolarisAndHPUX implements RangeGetter {
    private final String ndd;

    public SolarisAndHPUX(boolean isHpux) {
      this.ndd = isHpux ? "/usr/bin/ndd" : "/usr/sbin/ndd";
    }

    @Override
    public Range getRange() {
      try {
        String lower = firstLine(ndd, "/dev/tcp", "tcp_smallest_anon_port");
        String upper = firstLine( ndd, "/dev/tcp", "tcp_largest_anon_port");
        int low = Integer.parseInt(lower.replaceAll("\n", ""));
        int high = Integer.parseInt(upper.replaceAll("\n", ""));
        return new Range(low, high);

      } catch (Exception e) {
        LOGGER.warn("Unable to determine tcp_{smallest,largest}_anon_port; using default IANA range", e);
        return IANA_DEFAULT_RANGE;
      }
    }
  }

  private static class Windows implements RangeGetter {

    @Override
    public Range getRange() {
      String osName = System.getProperty("os.name");

      // windows XP and server 2003
      if (osName.equalsIgnoreCase("windows xp") || osName.equalsIgnoreCase("windows 2003")) {
        return getLegacySettings();
      }

      // Assume all other windows OS use the new network parameters
      return getNetshRange();
    }

    private Range getNetshRange() {
      try {
        // and use netsh to determine dynamic port range
        File netshExe = new File(Os.findWindowsSystemRoot(), "netsh.exe");

        int start = -1;
        int num = -1;
        Pattern pattern = Pattern.compile("^.*: (\\p{XDigit}+)");
        for (String line : allLines(netshExe.getAbsolutePath(), "int", "ipv4", "show", "dynamicport", "tcp")) {
          Matcher matcher = pattern.matcher(line);
          if (start == -1 && matcher.matches()) {
            start = Integer.parseInt(matcher.group(1));
          } else if (num == -1 && matcher.matches()) {
            num = Integer.parseInt(matcher.group(1));
          } else if (start != -1 && num != -1) {
            break;
          }
        }

        if ((num == -1) || (start == -1)) { throw new Exception("start: " + start + ", num = " + num); }
        return new Range(start, start + num - 1);

      } catch (Exception e) {
        LOGGER.warn("Unable to determine IPv4 TCP dynamicport range; using default IANA range", e);
        return IANA_DEFAULT_RANGE;
      }
    }

    private Range getLegacySettings() {
      final int DEFAULT_LOWER = 1024;
      final int DEFAULT_UPPER = 5000;
      Range defaultRange = new Range(DEFAULT_LOWER, DEFAULT_UPPER);

      try {
        // use reg.exe if available to see if MaxUserPort is tweaked
        String sysRoot = Os.findWindowsSystemRoot();
        if (sysRoot != null) {
          File regExe = new File(sysRoot, "reg.exe");
          if (regExe.exists()) {
            Pattern pattern = Pattern.compile("^.*MaxUserPort\\s+REG_DWORD\\s+0x(\\p{XDigit}+)");
            for (String line : allLines(regExe.getAbsolutePath(), "query",
                "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters", "/v", "MaxUserPort")) {
              Matcher matcher = pattern.matcher(line);
              if (matcher.matches()) {
                int val = Integer.parseInt(matcher.group(1), 16);
                return new Range(DEFAULT_LOWER, val);
              }
            }
          }
        }
        LOGGER.warn("Unable to locate reg.exe; using default range of [{}]", defaultRange);
      } catch (Exception e) {
        LOGGER.warn("Unable to determine TCP/IP MaxUserPort; using default range of [{}]", defaultRange, e);
      }

      return defaultRange;
    }
  }

  private static class Mac implements RangeGetter {
    @Override
    public Range getRange() {
      try {
        Properties props = asProperties("sysctl", "net.inet.ip.portrange");
        int low = Integer.parseInt(props.getProperty("net.inet.ip.portrange.hifirst"));
        int high = Integer.parseInt(props.getProperty("net.inet.ip.portrange.hilast"));
        return new Range(low, high);

      } catch (Exception e) {
        LOGGER.warn("Unable to determine net.inet.ip.portrange.{hifirst,hilast}; using default IANA range", e);
        return IANA_DEFAULT_RANGE;
      }
    }
  }

  private static class Linux implements RangeGetter {
    private static final String source = "/proc/sys/net/ipv4/ip_local_port_range";

    /*
     * File creation with a proc filesystem path - this is okay as this is linux specific code.
     */
    @Override
    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    public Range getRange() {
      final int DEFAULT_LOWER = 32768;
      final int DEFAULT_UPPER = 61000;
      Range defaultRange = new Range(DEFAULT_LOWER, DEFAULT_UPPER);

      File src = new File(source);
      if (!src.exists() || !src.canRead()) {
        LOGGER.warn("Cannot access \"{}\"; using default range of [{}]", source, defaultRange);
        return defaultRange;
      }

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(src), StandardCharsets.UTF_8))) {
        String data = reader.readLine();
        if (data == null) {
          LOGGER.warn("Unexpected EOF on \"{}\"; using default range of [{}]", source, defaultRange);
          return defaultRange;
        }
        String[] parts = data.split("[ \\t]");
        if (parts.length != 2) {
          LOGGER.warn("Unexpected number of tokens in \"{}\" - \"{}\"; using default range of [{}]", source, data, defaultRange);
          return defaultRange;
        }

        int low = Integer.parseInt(parts[0]);
        int high = Integer.parseInt(parts[1]);

        return new Range(low, high);
      } catch (IOException ioe) {
        LOGGER.warn("Unable to determine ipv4/ip_local_port_range; using default range of [{}]", defaultRange, ioe);
        return defaultRange;
      }
    }
  }

  private static class Aix implements RangeGetter {
    @Override
    public Range getRange() {
      try {
        Properties props = asProperties("/usr/sbin/no", "-a");
        int low = Integer.parseInt(props.getProperty("tcp_ephemeral_low"));
        int high = Integer.parseInt(props.getProperty("tcp_ephemeral_high"));
        return new Range(low, high);

      } catch (Exception e) {
        LOGGER.warn("Unable to determine tcp_ephemeral_{low,high}; using default IANA range", e);
        return IANA_DEFAULT_RANGE;
      }
    }
  }

  public static void main(String[] args) {
    System.err.println(getRange());
  }
}
