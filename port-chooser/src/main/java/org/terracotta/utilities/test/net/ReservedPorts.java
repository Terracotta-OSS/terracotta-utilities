/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies reserved ports on a platform.
 * <p>
 * More recent releases of Microsoft Windows has the ability, by API or command, to reserve a port
 * for use by an application.  While typical usage would be to reserve an ephemeral/dynamic port,
 * reservation is not restricted to these ports so reserved ports need to be avoided in {@link PortManager}
 * processing.
 */
// public for 'main' method
public final class ReservedPorts {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReservedPorts.class);

  /**
   * Gets the list of port ranges considered <i>reserved</i> by the platform.
   * <p>
   * This list is calculated for each call to this method; since determining the
   * list of reserved ports is likely to be expensive, repeated calls should be
   * avoided.
   *
   * @return a list, possibly empty, of reserved port ranges
   */
  static List<EphemeralPorts.Range> getRange() {
    if (Os.isLinux()) { return new Linux().getRanges(); }
    if (Os.isMac()) { return Collections.emptyList(); }
    if (Os.isWindows()) { return new Windows().getRanges(); }
    if (Os.isSolaris()) { return Collections.emptyList(); }
    if (Os.isAix()) { return Collections.emptyList(); }
    if (Os.isHpux()) { return Collections.emptyList(); }

    throw new AssertionError("No support for this OS: " + Os.getOsName());
  }

  /**
   * Determine the reserved ports on a Linux platform.  The {@code ip_local_reserved_ports} Proc file
   * contains a comma-separated list of port ranges and individual ports.
   *
   * @see <a href="https://github.com/torvalds/linux/blob/master/Documentation/networking/ip-sysctl.rst#ip-variables">/proc/sys/net/ipv4/* Variables : IP Variables</a>
   */
  private static class Linux {
    private static final String SOURCE = "/proc/sys/net/ipv4/ip_local_reserved_ports";

    public List<EphemeralPorts.Range> getRanges() {
      List<EphemeralPorts.Range> reservedRanges = new ArrayList<>();

      File rangeSource = new File(SOURCE);
      if (!rangeSource.exists() || !rangeSource.canRead()) {
        LOGGER.warn("Cannot access \"{}\"; cannot determine reserved ports", SOURCE);
        return Collections.emptyList();
      }

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(Paths.get(SOURCE)), StandardCharsets.UTF_8))) {
        String reservations = reader.readLine();
        if (reservations == null || reservations.isEmpty()) {
          LOGGER.debug("No reserved port ranges read from {}", SOURCE);
          return Collections.emptyList();
        } else {
          Pattern rangePattern = Pattern.compile("(\\d+)(?:-(\\d+))?");
          for (String token : Pattern.compile(",").split(reservations)) {
            Matcher rangeMatch = rangePattern.matcher(token);
            if (rangeMatch.matches()) {
              int lower = Integer.parseInt(rangeMatch.group(1));
              int upper = (rangeMatch.group(2) == null ? lower : Integer.parseInt(rangeMatch.group(2)));
              reservedRanges.add(new EphemeralPorts.Range(lower, upper));
            } else {
              LOGGER.warn("Failed to match '{}' from {}", token, SOURCE);
            }
          }
          if (reservedRanges.isEmpty()) {
            LOGGER.debug("No reserved port ranges parsed from {}", SOURCE);
          }
        }

      } catch (IOException e) {
        LOGGER.warn("Unable to determine reserved ports", e);
        return Collections.emptyList();
      }

      return Collections.unmodifiableList(reservedRanges);
    }
  }

  /**
   * Determine the reserved ports on a Windows platform.
   */
  private static class Windows {
    List<EphemeralPorts.Range> getRanges() {
      List<EphemeralPorts.Range> excludedRanges = new ArrayList<>();

      try {
        // and use netsh to determine dynamic port range
        File netshExe = new File(Os.findWindowsSystemRoot(), "netsh.exe");

        String[] cmd = { netshExe.getAbsolutePath(), "interface", "ipv4", "show", "excludedportrange", "protocol=tcp" };
        Shell.Result result = Shell.execute(Shell.Encoding.CHARSET,
            cmd);
        if (result.exitCode() != 0) {
          LOGGER.warn("Cannot determine excluded ports: command {} failed; rc={}:%n    {}",
              Arrays.toString(cmd), result.exitCode(), String.join("\n    ", result));
          return Collections.emptyList();
        }

        Pattern pattern = Pattern.compile("^\\s+(\\d+)\\s+(\\d+).*");
        for (String line : result) {
          Matcher matcher = pattern.matcher(line);
          if (matcher.matches()) {
            excludedRanges.add(
                new EphemeralPorts.Range(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
          }
        }

      } catch (Exception e) {
        LOGGER.warn("Unable to excluded port ranges", e);
        return Collections.emptyList();
      }

      return Collections.unmodifiableList(excludedRanges);
    }
  }

  public static void main(String[] args) {
    ReservedPorts.getRange().forEach(System.out::println);
  }
}
