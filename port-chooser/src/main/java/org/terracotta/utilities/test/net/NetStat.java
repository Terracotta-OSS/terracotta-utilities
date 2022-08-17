/*
 * Copyright 2020-2022 Terracotta, Inc., a Software AG company.
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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.*;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Produces a network status collection similar to that obtained using {@code netstat}.
 * This class is intended for diagnostic purposes; elevated privileges may be required
 * for full status collection.
 *
 * <h3>Implementation Notes</h3>
 * <ul>
 *   <li>
 *     For a Windows platform, {@code powershell} must be available via {@code PATH}.
 *   </li>
 *   <li>
 *     For a Mac OS X platform, {@code nettop} and {@code ps} must be available via {@code PATH}.
 *   </li>
 *   <li>
 *     For a Linux platform, {@code lsof} and {@code ps} must be available via {@code PATH}.  For
 *     a complete port list, the effective user must be permitted to use {@code sudo} to execute
 *     the {@code lsof} command <i>without a password.</i>
 *   </li>
 *   <li>
 *     Not all connections observed using {@code netstat} are shown by {@code lsof} --
 *     see <a href="https://raw.githubusercontent.com/lsof-org/lsof/master/00FAQ"><code>lsof</code> FAQ</a>
 *     item 3.29.
 *   </li>
 * </ul>
 */
public class NetStat {
  private static final Logger LOGGER = LoggerFactory.getLogger(NetStat.class);
  private static final AtomicBoolean LSOF_WARNING_EMITTED = new AtomicBoolean(false);

  /**
   * Private niladic constructor to prevent instantiation.
   */
  private NetStat() {
  }

  /**
   * Gets the list of busy ports on the current host.
   * <p>
   * Note that individual ports may appear more than once in the returned list.
   * Depending on the source, a port may be listed for both IPv4 and IPv6 uses;
   * for some applications (e.g. {@code sshd}), a port may be shared among multiple
   * processes.
   * @return the list of {@link BusyPort} instances representing the busy TCP ports
   * @throws RuntimeException if the busy port information cannot be obtained
   */
  public static List<BusyPort> info() {
    try {
      return Platform.getPlatform().netstat();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Gets the list of busy ports associated with a single local port.
   * <p>
   * Note that individual ports may appear more than once in the returned list.
   * Depending on the source, a port may be listed for both IPv4 and IPv6 uses;
   * for some applications (e.g. {@code sshd}), a port may be shared among multiple
   * processes.
   * @param port the target port
   * @return the list of {@link BusyPort} instances representing the busy TCP ports
   * @throws IllegalArgumentException if {@code port} is not a valid port number
   * @throws RuntimeException if the busy port information cannot be obtained
   */
  public static List<BusyPort> info(int port) {
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException(port + " is not a valid port number");
    }
    try {
      return Platform.getPlatform().netstat(port);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Writes the list returned by {@link #info()} to {@code System.out}.
   * @param args zero or more port numbers to check
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      for (BusyPort port : info()) {
        System.out.println(port.toString("\t"));
      }
    } else {
      for (String arg : args) {
        try {
          int port = Integer.parseInt(arg);
          for (BusyPort busyPort : info(port)) {
            System.out.println(busyPort.toString("\t"));
          }
        } catch (IllegalArgumentException e) {
          System.out.format("'%s' is not a valid port number: %s", arg, e);
        }
      }
    }
  }

  /**
   * Specifies the platform-dependent support for obtaining {@code netstat}-like information.
   */
  private enum Platform {
    /**
     * Handles processing for Windows variants.  For successful operation, {@code powershell} must be
     * available via {@code PATH}.
     */
    WINDOWS("win32") {
      /**
       * {@inheritDoc}
       * <p>
       * This method returns the TCP connections as obtained from the PowerShell {@code Get-NetTCPConnection}
       * cmdlet.
       * @return {@inheritDoc}
       * @throws HostExecutionException {@inheritDoc}
       * @see <a href="https://docs.microsoft.com/en-us/powershell/module/nettcpip/get-nettcpconnection?view=win10-ps">
       *     Get-NetTCPConnection</a>
       */
      @Override
      public List<BusyPort> netstat() throws HostExecutionException {
        Function<Stream<String>, List<BusyPort>> conversion =
            stream -> stream.skip(1).map(NetStat::parseWindowsCsv).collect(toList());
        return runCommand(prepareCommand(POWERSHELL_NETSTAT_COMMAND, ""), conversion);
      }

      /**
       * {@inheritDoc}
       * <p>
       * This method returns the TCP connections as obtained from the PowerShell {@code Get-NetTCPConnection}
       * cmdlet.
       * @return {@inheritDoc}
       * @throws HostExecutionException {@inheritDoc}
       * @see <a href="https://docs.microsoft.com/en-us/powershell/module/nettcpip/get-nettcpconnection?view=win10-ps">
       *     Get-NetTCPConnection</a>
       */
      @Override
      public List<BusyPort> netstat(int port) throws HostExecutionException {
        Function<Stream<String>, List<BusyPort>> conversion =
            stream -> stream.skip(1).map(NetStat::parseWindowsCsv).collect(toList());
        return runCommand(prepareCommand(POWERSHELL_NETSTAT_COMMAND, String.format("-LocalPort %d", port)), conversion);
      }
    },

    /**
     * Handles processing for Mac OS X.  This uses the {@code nettop} command in single-sample mode and
     * augments the output with a {@code ps} command.
     * Unlike {@code lsof}, {@code nettop} does not require elevated privileges to observe the open
     * ports of processes opened by "other" users.
     */
    MAC("mac") {
      @Override
      public List<BusyPort> netstat() throws HostExecutionException {
        List<BusyPort> busyPorts = runCommand(NETTOP_COMMAND, NetStat::parseNetTop);

        /*
         * Since the 'nettop' command doesn't provide a useful command string, 'ps' is used to
         * augment what was returned by 'nettop'.
         */
        return mergeCommands(busyPorts);
      }

      @Override
      public List<BusyPort> netstat(int port) throws HostExecutionException {
        /*
         * nettop has no option to return results for a single port so the results are filtered
         */
        List<BusyPort> busyPorts =
            runCommand(NETTOP_COMMAND, NetStat::parseNetTop).stream()
                .filter(busyPort -> busyPort.localEndpoint.getPort() == port)
                .collect(toList());

        /*
         * Since the 'nettop' command doesn't provide a useful command string, 'ps' is used to
         * augment what was returned by 'nettop'.
         */
        return mergeCommands(busyPorts);
      }
    },

    /**
     * Handles processing for Linux variants.  This uses the {@code lsof} for discovering open ports
     * and augments the {@code lsof} output with output from {@code ps}.  {@code lsof} requires
     * elevated privileges to see all ports and executed under the control of {@code sudo} with
     * the password prompt suppressed.
     * <p>
     * On Linux implementations which have {@code sudo} and {@code lsof} but do not have full
     * representation of the network stack in the {@code /proc} filesystem, the {@link #netstat()}
     * method will return an <i>empty</i> list.
     */
    LINUX("linux") {
      @Override
      public List<BusyPort> netstat() throws HostExecutionException {
        return busyPorts(prepareCommand(LSOF_COMMAND, "TCP"), true);
      }

      @Override
      public List<BusyPort> netstat(int port) throws HostExecutionException {
        return busyPorts(prepareCommand(LSOF_COMMAND, String.format(":%d", port)), false);
      }

      private List<BusyPort> busyPorts(String[] lsofCommand, boolean allPorts) throws HostExecutionException {
        String[] sudoLsof = Arrays.copyOf(SUDO_PREFIX, SUDO_PREFIX.length + lsofCommand.length);
        System.arraycopy(lsofCommand, 0, sudoLsof, SUDO_PREFIX.length, lsofCommand.length);
        List<BusyPort> busyPorts;
        try {
          busyPorts = runCommand(sudoLsof, NetStat::parseLsof);
        } catch (HostExecutionException e) {
          /*
           * 'lsof', if instances of the targeted file handle -- in this case, a TCP port -- are not found,
           * returns no output and an exit code of 1.  'sudo', if there is a fault attempting to execute
           * 'lsof', returns an exit code of 1 but _generally_ emits a message.  So, an exit code of 1
           * with NO output will be interpreted, not as a failure, but an absence of detectable ports.
           */
          if (e.exitCode() == 1 && e.output().isEmpty()) {
            // No detected ports
            if (allPorts) {
              LOGGER.warn("'lsof' returned no active TCP ports; cannot determine in-use TCP ports: {}", e.getMessage());
            }
            return Collections.emptyList();
          } else {
            /*
             * Failed to run 'sudo ... lsof'; emit notification of the potential problem.
             */
            if (LSOF_WARNING_EMITTED.compareAndSet(false, true)) {
              String message = "\n" +
                  "\n********************************************************************************" +
                  "\nObtaining a full set of in-use TCP ports requires use of 'sudo' to execute" +
                  "\n'lsof'; add sudoers permissions to allow this.  For example, add a line like the" +
                  "\nfollowing to the bottom of the '/etc/sudoers' file (using 'sudo visudo'):" +
                  "\n    %sudo   ALL=NOPASSWD: /usr/bin/lsof" +
                  "\nEnsure an appropriate group or username is used in place of '%sudo' and the" +
                  "\ncorrect path to 'lsof' is used." +
                  "\n" +
                  "\nNote: Both 'sudo' and 'lsof' must be accessible from PATH." +
                  "\n    PATH=" + System.getenv("PATH") +
                  "\n********************************************************************************" +
                  "\n";
              if (LOGGER.isDebugEnabled()) {
                LOGGER.warn("Failed to run elevated `lsof` command; cannot obtain all active TCP ports using 'sudo ... lsof'{}", message, e);
              } else {
                LOGGER.warn("Failed to run elevated `lsof` command; cannot obtain all active TCP ports using 'sudo ... lsof'{}{}", message, e.getMessage());
              }
            } else {
              LOGGER.warn("Failed to run elevated `lsof` command; cannot obtain all active TCP ports using 'sudo ... lsof': {}", e.getMessage());
            }

            /*
             * Attempt 'lsof' command without 'sudo'.
             */
            LOGGER.warn("Attempting {} without 'sudo' elevation; ports owned by other users may be omitted", Arrays.toString(lsofCommand));
            try {
              busyPorts = runCommand(lsofCommand, NetStat::parseLsof);
            } catch (Exception ex) {
              if ((ex instanceof HostExecutionException)) {
                HostExecutionException hex = (HostExecutionException)ex;
                if (hex.exitCode() == 1 && hex.output().isEmpty()) {
                  if (allPorts) {
                    LOGGER.warn("'lsof' returned no active TCP ports; cannot determine in-use TCP ports: {}", hex.getMessage());
                  }
                  return Collections.emptyList();
                }
              }
              LOGGER.error("Failed to run non-elevated 'lsof' command; cannot determine in-use TCP ports: {}", ex.getMessage());
              HostExecutionException hostExecutionException =
                  new HostExecutionException("Failed to obtain active TCP ports using 'lsof'", ex);
              hostExecutionException.addSuppressed(e);
              throw hostExecutionException;
            }
          }
        }

        /*
         * Since the 'lsof' command doesn't provide a useful command, 'ps' is used to
         * augment what was returned by 'lsof'.
         */
        return Platform.mergeCommands(busyPorts);
      }
    },
    ;

    private final String osPlatform;

    Platform(String osPlatform) {
      this.osPlatform = osPlatform;
    }

    /**
     * Gets the list of TCP connections joined with information about the process that owns each connection.
     * Administrator privileges may be required for complete output.
     *
     * @return a list of {@code BusyPort} instances describing the active ports; this list may include both
     *        IPv4 and IPv6 connections
     * @throws HostExecutionException if there was a failure in obtaining the TCP connections or host processes lists
     */
    public abstract List<BusyPort> netstat() throws HostExecutionException;

    /**
     * Gets the list of TCP connections for a single local port joined with information about the process
     * that owns each connection. Administrator privileges may be required for complete output.
     * @param port the target port
     * @return a list of {@code BusyPort} instances describing the active connections for {@code port}; this
     *        list may include both IPv4 and IPv6 connections
     * @throws HostExecutionException if there was a failure in obtaining the TCP connections or host processes lists
     */
    public abstract List<BusyPort> netstat(int port) throws HostExecutionException;

    /**
     * Gets the {@code Platform} constant for the current operating system.
     * @return the current {@code Platform} constant
     * @throws EnumConstantNotPresentException if the current operating system is
     *        not supported
     */
    public static Platform getPlatform() throws EnumConstantNotPresentException {
      String platform = Os.platform();
      for (Platform value : values()) {
        if (value.osPlatform.equals(platform)) {
          return value;
        }
      }
      throw new EnumConstantNotPresentException(Platform.class, platform);
    }

    /**
     * Creates an updated {@code BusyPort} list merging in a more complete command string
     * from the {@code ps} command.
     * @param busyPorts a list of {@code BusyPort} instances to update
     * @return a new list of updated {@code BusyPort} instances
     * @throws HostExecutionException if an error is raised trying to run the {@code ps} command
     */
    private static List<BusyPort> mergeCommands(List<BusyPort> busyPorts) throws HostExecutionException {
      return runCommand(PS_COMMAND, psStream -> {
        Map<Long, String> processMap = psStream.skip(1)
            .map(line -> {
              Matcher matcher = PS_PATTERN.matcher(line);
              if (!matcher.matches()) {
                throw new IllegalStateException("Failed to process process line - '" + line + "'");
              }
              return matcher;
            })
            .collect(toMap(m -> Long.parseLong(m.group(1)), m -> m.group(3)));

        return busyPorts.stream()
            .map(p -> {
              String command = processMap.get(p.processId());
              return command == null ? p : BusyPort.builder(p).commandLine(command).build();
            })
            .collect(toList());
      });
    }
    private static final String[] PS_COMMAND = new String[] { "ps", "-ax", "-opid,user,command" };
    private static final Pattern PS_PATTERN = Pattern.compile("\\s*(\\S+)\\s(\\S+)\\s+(.+)");

    private static <T> List<T> runCommand(String[] command, Function<Stream<String>, List<T>> conversion)
        throws HostExecutionException {
      Shell.Result result;
      try {
        result = Shell.execute(Shell.Encoding.CHARSET, command);
      } catch (IOException e) {
        throw new HostExecutionException(Arrays.toString(command), null, e);
      }

      if (result.exitCode() == 0) {
        if (LOGGER.isTraceEnabled()) {
          LOGGER.trace("Command complete {}; rc=0{}",
              Arrays.toString(command), "\n    " + String.join("\n    ", result.lines()));
        }
        return conversion.apply(result.lines().stream());
      } else {
        throw new HostExecutionException(Arrays.toString(command), result);
      }
    }

    /**
     * Prepares a command array using {@code String.format} to substitute arguments.  If the
     * argument list contains more than one argument, {@code commandTemplate} should use format
     * specifiers using the argument index form.
     * @param commandTemplate the array composing the command to prepare
     * @param args the arguments to substitute into the command array strings
     * @return the prepared command array
     */
    private static String[] prepareCommand(String[] commandTemplate, Object... args) {
      String[] command = Arrays.copyOf(commandTemplate, commandTemplate.length);
      for (int i = 0; i < command.length; i++) {
        String segment = command[i];
        if (segment.indexOf('%') != 0) {
          command[i] = String.format(segment, args);
        }
      }
      return command;
    }
  }

  /**
   * Parses a response line from {@link #POWERSHELL_NETSTAT_COMMAND}.
   * @param line a comma-separated-value line containing:
   *             <ul>
   *             <li>OwningProcess</li>
   *             <li>LocalAddress</li>
   *             <li>LocalPort</li>
   *             <li>RemoteAddress</li>
   *             <li>RemotePort</li>
   *             <li>State</li>
   *             <li>ProcessCaption (mapping to {@link BusyPort#shortCommand() BusyPort.shortCommand})</li>
   *             <li>ProcessCommandLine (mapping to {@link BusyPort#commandLine() BusyPort.commandLine})</li>
   *             </ul>
   * @return a {@code BusyPort} instance formed from {@code line}
   */
  private static BusyPort parseWindowsCsv(String line) {
    requireNonNull(line, "line");
    if (line.isEmpty()) {
      throw new IllegalArgumentException("line cannot be empty");
    }

    List<String> fields = new ArrayList<>();

    StringBuilder sb = new StringBuilder();
    int quoteCount = 0;
    StringCharacterIterator iterator = new StringCharacterIterator(line);
    for (char c = iterator.first(); c != CharacterIterator.DONE; c = iterator.next()) {
      switch (c) {
        case '"':
          if (quoteCount++ != 0 && quoteCount % 2 != 0) {
            sb.append('"');
          }
          break;
        case ',':
          if (quoteCount % 2 == 0) {
            fields.add(sb.toString());
            sb.setLength(0);
            quoteCount = 0;
          } else {
            sb.append(',');
          }
          break;
        default:
          sb.append(c);
      }
    }
    if (quoteCount % 2 != 0) {
      throw new IllegalArgumentException("Line ends with unbalanced quote at index " +
          iterator.getIndex() + " - " + line);
    }
    if (sb.length() > 0) {
      fields.add(sb.toString());
    }

    BusyPort.Builder builder = BusyPort.builder();
    try {
      builder.processId(fields.get(0));
      builder.localEndpoint(BusyPort.IPVersion.IPV4, fields.get(1), fields.get(2));
      builder.remoteEndpoint(BusyPort.IPVersion.IPV4, fields.get(3), fields.get(4));
      builder.state(BusyPort.TcpState.fromMicrosoftString(fields.get(5)));
      builder.shortCommand(fields.get(6));
      // The full command line may be missing ...
      if (fields.size() >= 8) {
        builder.commandLine(fields.get(7));
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Error in line - " + line, e);
    }

    return builder.build();
  }
  private static final String[] POWERSHELL_NETSTAT_COMMAND = new String[] {
      "powershell.exe",
      "-NoLogo",
      "-NoProfile",
      "-NonInteractive",
      "-Command",
      "&{$ErrorActionPreference = 'Stop'; " +
          " $processes = Get-WmiObject -Class \"Win32_Process\" -Namespace \"ROOT\\CIMV2\" " +
          "| Select-Object -Property ProcessId, Caption, CommandLine; " +
          " Get-NetTCPConnection %1$s -ErrorAction SilentlyContinue " +
          "| Select-Object -Property OwningProcess, LocalAddress, LocalPort, RemoteAddress, RemotePort, State " +
          "| Foreach-Object {" +
          "  $connection = $_;" +
          "  $process = $processes | Where-Object {$_.ProcessId -eq $connection.OwningProcess} | Select-Object -Unique;" +
          "  if ($process -ne $null) {" +
          "    $connection | Add-Member -NotePropertyMembers @{ProcessCaption = $process.Caption; ProcessCommandLine = $process.CommandLine}" +
          "  };" +
          "  $connection;" +
          "}" +
          "| ConvertTo-Csv -NoTypeInformation" +
          "}"
  };


  /**
   * Parses the output of {@link #LSOF_COMMAND}.
   * <p>
   * The {@code F} options are:
   * <dl>
   *   <dt>{@code p}</dt><dd>process id</dd>
   *   <dt>{@code g}</dt><dd>process group id</dd>
   *   <dt>{@code R}</dt><dd>parent process id</dd>
   *   <dt>{@code c}</dt><dd>command</dd>
   *   <dt>{@code L}</dt><dd>process login name</dd>
   *   <dt>{@code f}</dt><dd>file descriptor (fd)</dd>
   *   <dt>{@code t}</dt><dd>file's type  (IPv6 | IPv4)</dd>
   *   <dt>{@code P}</dt><dd>protocol name (TCP)</dd>
   *   <dt>{@code n}</dt><dd>internet address pair</dd>
   *   <dt>{@code T}</dt><dd>TCP information
   *     <dl>
   *       <dt>ST</dt><dd>connection state</dd>
   *       <dt>QR</dt><dd>receive queue size</dd>
   *       <dt>QS</dt><dd>send queue size</dd>
   *     </dl>
   *   </dd>
   * </dl>
   * Input lines from the {@code lsof} command are structured for program consumption:
   * <ul>
   *   <li>Fields within each line are separated by a NUL ({@code \0})</li>
   *   <li>Lines are grouped by process
   *   <ol>
   *     <li>a line beginning with a {@code p} begins each process group</li>
   *     <li>each TCP ports used by the process is represented by a line beginning with an {@code f}</li>
   *   </ol>
   *   </li>
   *   <li>The value of the {@code c} (command) field is <i>not</i> the full command line; this value is
   *   applied as the {@link BusyPort.Builder#shortCommand() BusyPort.shortCommand}.  This value is generally
   *   not sufficient to establish, diagnostically, what application is actually using a port -- all Java
   *   processes are listed simply as {@code java}.</li>
   * </ul>
   * The following is a sample of the expected input from {@code lsof}:
   * <pre>{@code
   *    p1\0g1\0R0\0claunchd\0Lroot
   *    f21\0tIPv6\0PTCP\0n*:445\0TST=LISTEN\0TQR=0\0TQS=0
   *    f22\0tIPv4\0PTCP\0n*:445\0TST=LISTEN\0TQR=0\0TQS=0
   *    f24\0tIPv6\0PTCP\0n*:445\0TST=LISTEN\0TQR=0\0TQS=0
   *    f25\0tIPv4\0PTCP\0n*:445\0TST=LISTEN\0TQR=0\0TQS=0
   *    f35\0tIPv6\0PTCP\0n*:548\0TST=LISTEN\0TQR=0\0TQS=0
   *    f36\0tIPv4\0PTCP\0n*:548\0TST=LISTEN\0TQR=0\0TQS=0
   *    f37\0tIPv6\0PTCP\0n*:548\0TST=LISTEN\0TQR=0\0TQS=0
   *    f38\0tIPv4\0PTCP\0n*:548\0TST=LISTEN\0TQR=0\0TQS=0
   *    p121\0g121\0R1\0cUserEventAgent\0Lroot
   *    f71\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49177->[fe80:5::aede:48ff:fe33:4455]:59602\0TST=ESTABLISHED\0TQR=0\0TQS=0
   *    f91\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49178\0TST=LISTEN\0TQR=0\0TQS=0
   *    f94\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49179\0TST=LISTEN\0TQR=0\0TQS=0
   *    f102\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49180\0TST=LISTEN\0TQR=0\0TQS=0
   *    f103\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49181\0TST=LISTEN\0TQR=0\0TQS=0
   *    f104\0tIPv6\0PTCP\0n[fe80:5::aede:48ff:fe00:1122]:49182\0TST=LISTEN\0TQR=0\0TQS=0
   *    p22899\0g22706\0R22706\0cjava\0Lclifford
   *    f66\0tIPv6\0PTCP\0n127.0.0.1:14542\0TST=LISTENT\0QR=0T\0QS=0
   *    f67\0tIPv6\0PTCP\0n127.0.0.1:51880\0TST=LISTENT\0QR=0T\0QS=0
   *    f68\0tIPv6\0PTCP\0n127.0.0.1:54696->127.0.0.1:51828\0TST=ESTABLISHED\0TQR=0\0TQS=0
   * }</pre>
   * @param lines a {@code Stream} delivering lines from the {@code lsof} command
   * @return the list of busy ports
   */
  private static List<BusyPort> parseLsof(Stream<String> lines) {
    List<BusyPort> busyPorts = new ArrayList<>();
    Iterator<String> iterator = lines.iterator();
    BusyPort.Builder builder = null;
    while(iterator.hasNext()) {
      String line = iterator.next();
      String[] fields = line.split("\0");

      if (line.charAt(0) == 'p') {
        try {
          /* Extract process details ... */
          builder = BusyPort.builder();
          for (String field : fields) {
            char fieldId = field.charAt(0);
            String fieldValue = field.substring(1);
            switch (fieldId) {
              case 'p':
                builder.processId(fieldValue);
                break;
              case 'c':
                builder.shortCommand(fieldValue);
                break;
              default:
                // spotbugs appeasement
            }
          }
        } catch (Exception e) {
          throw new IllegalStateException("Expecting process ('p') line; found '" + line + "'", e);
        }

      } else {
        if (builder == null) {
          throw new IllegalStateException("Expecting process ('p') line; found '" + line + "'");
        }

        if (line.charAt(0) == 'f') {
          try {
            /* Process file descriptors (ports). */
            BusyPort.IPVersion ipVersion = null;
            for (String field : fields) {
              char fieldId = field.charAt(0);
              String fieldValue = field.substring(1);
              switch (fieldId) {
                case 't':
                  // TCP Address type/version
                  ipVersion = BusyPort.IPVersion.fromId(fieldValue);
                  break;
                case 'n':
                  // TCP Endpoints
                  InetEndpointPair endpointPair = parseLsofEndpointPair(ipVersion, fieldValue);
                  builder.localEndpoint(endpointPair.getLocalEndpoint());
                  builder.remoteEndpoint(endpointPair.getRemoteEndpoint());
                  break;
                case 'T':
                  // TCP information fields
                  int p = fieldValue.indexOf('=');
                  if (p > 0) {
                    String infoClass = fieldValue.substring(0, p);
                    if ("ST".equals(infoClass)) {
                      // TCP Status
                      BusyPort.TcpState state = BusyPort.TcpState.fromLsofString(fieldValue.substring(1 + p));
                      builder.state(state);
                    }
                  }
                  break;
                default:
                  // spotbugs appeasement
              }
            }
            busyPorts.add(builder.build());
            builder.resetTcp();
          } catch (Exception e) {
            throw new IllegalStateException("Expecting fd ('f') line; found '" + line + "'", e);
          }

        } else {
          throw new IllegalStateException("Expecting fd ('f') line; found '" + line + "'");
        }
      }
    }

    return busyPorts;
  }
  private static final String[] SUDO_PREFIX = new String[] {
      "sudo",       // The use of 'sudo' is required to obtain a list of open file descriptors for processes not owned by the current user.
      "--non-interactive",
      "--"
  };
  private static final String[] LSOF_COMMAND = new String[] {
      "lsof",
      "-nP",          // Use numeric IP addresses; use numeric ports
      "-i%1$s",       // Match only TCP connections ('TCP') or a single port (':<port>')
      "-F",           // Field list ...
      "0pPRgLnTftc",  // ... see 'parseLsof' for description
      "+c0",          // Use expanded COMMAND value
      "-w"            // Suppress warnings
  };

  /**
   * Converts the TCP endpoint address pair obtained from the "name" field output from {@code lsof} into
   * an {@link InetSocketAddress}.
   * The endpoint pair is of the following form:
   * <pre>{@code <localHost>':'<localPort>['->'<remoteHost>':'<remotePort>]}</pre>
   * If the port is a <i>listener</i> port, the remote endpoint portion is omitted.
   * <p>
   * From the man page for {@code lsof}:
   * <blockquote>
   * ...<br>
   * the local host name or IP number is followed by a colon (':'), the port, ``->'', and the two-part
   * remote address; IP addresses may be reported as numbers or names, depending on the +|-M, -n, and
   * -P options; colon-separated IPv6  numbers  are enclosed  in  square brackets; IPv4 INADDR_ANY and
   * IPv6 IN6_IS_ADDR_UNSPECIFIED addresses, and zero port numbers are represented by an asterisk ('*');
   * a UDP destination address may be followed by the amount of time elapsed since the last packet was
   * sent to the destination; TCP,  UDP  and  UDPLITE  remote addresses may be followed by TCP/TPI
   * information in parentheses - state (e.g., ``(ESTABLISHED)'', ``(Unbound)''), queue sizes, and
   * window sizes (not all dialects) - in a fashion similar to what netstat(1) reports; see the -T
   * option description or the description of the TCP/TPI field in OUTPUT  FOR  OTHER PROGRAMS for
   * more information on state, queue size, and window size;
   * </blockquote>
   *
   * @param ipVersion the {@link BusyPort.IPVersion IPVersion} constant identifying the IP address type
   * @param endpointPair tbe value of the "name" field from a file descriptor describing a TCP port/connection
   * @return an {@code InetEndpointPair} describing the connection/port
   */
  private static InetEndpointPair parseLsofEndpointPair(BusyPort.IPVersion ipVersion, String endpointPair) {
    if (requireNonNull(endpointPair, "endpointPair").isEmpty()) {
      throw new IllegalArgumentException("endpointPair is empty");
    }

    String[] endpoints = endpointPair.split(Pattern.quote("->"));
    InetSocketAddress localEndpoint = parseLsofEndpoint(ipVersion, endpoints[0]);
    if (endpoints.length == 1) {
      return new InetEndpointPair(localEndpoint, ipVersion.getInetSocketAddress("*", "*"));
    } else {
      return new InetEndpointPair(localEndpoint, parseLsofEndpoint(ipVersion, endpoints[1]));
    }
  }

  private static InetSocketAddress parseLsofEndpoint(BusyPort.IPVersion ipVersion, String endpoint) {
    int p = endpoint.lastIndexOf(':');
    return ipVersion.getInetSocketAddress(endpoint.substring(0, p), endpoint.substring(1 + p));
  }

  /**
   * Parses the output of {@link #NETTOP_COMMAND}.
   * <p>
   * The output begins with a header line which is dropped by this method.
   * The remaining output is grouped:
   * <ul>
   *   <li>each group begins with process line {@code <processName>'.'<processId>',,'}</li>
   *   <li>following the process line, are one or more lines each of which describes a port used by
   *     that process; each of these lines is of the form:
   *     {@code ['tcp6'|'tcp4'] <localEndpoint>'<->'<remoteEndpoint>','<tcpState>','}
   *   </li>
   *   <li>'tcp4' endpoints are in the form {@code [<ipAddress>|'*']':'[<port>|'*']}</li>
   *   <li>'tcp6' endpoints are in the form {@code [<ipv6Address>'%'<interface>'.'<port>|'*'.[<port>|'*']]}
   *      note that the {@code <ipv6Address>} is <b>not</b> wrapped in square brackets
   *   </li>
   * </ul>
   * The following is a sample of the expected input from {@code nettop}:
   * <pre>{@code
   * launchd.1,,
   * tcp6 *.445<->*.*,Listen,
   * tcp4 *:445<->*:*,Listen,
   * tcp6 *.548<->*.*,Listen,
   * tcp4 *:548<->*:*,Listen,
   * UserEventAgent.121,,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49177<->fe80::aede:48ff:fe33:4455%en11.59602,Established,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49178<->*.*,Listen,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49179<->*.*,Listen,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49180<->*.*,Listen,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49181<->*.*,Listen,
   * tcp6 fe80::aede:48ff:fe00:1122%en11.49182<->*.*,Listen,
   * ...
   * idea.22706,,
   * tcp4 127.0.0.1:51828<->localhost:51173,Established,
   * tcp4 127.0.0.1:6942<->*:*,Listen,
   * tcp4 127.0.0.1:63342<->*:*,Listen,
   * tcp4 127.0.0.1:51828<->*:*,Listen,
   * tcp4 127.0.0.1:52002<->*:*,Listen,
   * tcp4 127.0.0.1:52002<->127.0.0.1:64042,Established,
   * tcp4 127.0.0.1:51828<->127.0.0.1:51172,Established,
   * java.22899,,
   * tcp4 127.0.0.1:14542<->*:*,Listen,
   * tcp4 127.0.0.1:51880<->*:*,Listen,
   * tcp4 127.0.0.1:51173<->127.0.0.1:51828,Established,
   * java.22900,,
   * tcp4 127.0.0.1:4720<->*:*,Listen,
   * tcp4 127.0.0.1:51884<->*:*,Listen,
   * tcp4 127.0.0.1:51172<->127.0.0.1:51828,Established,
   * }</pre>
   *
   * @param lines a {@code Stream} delivering lines from the {@code nettop} command
   * @return the list of busy ports
   */
  private static List<BusyPort> parseNetTop(Stream<String> lines) {
    List<BusyPort> busyPorts = new ArrayList<>();
    Iterator<String> iterator = lines.skip(1).iterator();
    BusyPort.Builder builder = null;
    while(iterator.hasNext()) {
      String line = iterator.next();

      if (line.startsWith("tcp4 ")) {
        // IPv4-based endpoints
        if (builder == null) {
          throw new IllegalStateException("Expecting process identifier line; found '" + line + "'");
        }

        processNettopEndpoints(BusyPort.IPVersion.IPV4, builder, line, TCP4_ENDPOINT_PATTERN, Function.identity());

        busyPorts.add(builder.build());
        builder.resetTcp();

      } else if (line.startsWith("tcp6 ")) {
        // IPv6-based endpoints
        if (builder == null) {
          throw new IllegalStateException("Expecting process identifier line; found '" + line + "'");
        }

        processNettopEndpoints(BusyPort.IPVersion.IPV6, builder, line, TCP6_ENDPOINT_PATTERN, host -> {
          if (host.indexOf(':') != -1) {
            host = '[' + host + ']';
          }
          return host;
        });

        busyPorts.add(builder.build());
        builder.resetTcp();

      } else {
        // Process identifier line
        if (builder == null) {
          builder = BusyPort.builder();
        }

        if (line.endsWith(",,")) {
          line = line.substring(0, line.length() - 2);
          int p = line.lastIndexOf('.');
          builder.processId(line.substring(1 + p));
          builder.shortCommand(line.substring(0, p));
        } else {
          throw new IllegalStateException("Expecting process identifier line; found '" + line + "'");
        }
      }
    }

    return busyPorts;
  }
  private static final String[] NETTOP_COMMAND = new String[] {
      "nettop",
      "-L1",            // Take only one (1) sample
      "-m",             // Monitor only ...
      "tcp",            // ... TCP sockets
      "-n",             // Use numeric IP addresses
      "-J",             // Include fields ...
      "state"           // ... 'state'
  };

  private static void processNettopEndpoints(BusyPort.IPVersion ipVersion, BusyPort.Builder builder, String line,
                                             Pattern endpointPattern, Function<String, String> endpointHostMapper) {
    String[] fields = line.substring(5).split(",");
    if (fields.length != 2) {
      throw new IllegalStateException("Expecting tcp port line; found '" + line + "'");
    }
    String[] endpoints = fields[0].split(Pattern.quote("<->"));
    if (endpoints.length != 2) {
      throw new IllegalStateException("Expecting tcp port line; found '" + line + "'");
    }
    Matcher matcher = endpointPattern.matcher(endpoints[0]);
    if (matcher.matches()) {
      builder.localEndpoint(ipVersion, matcher.group(1), matcher.group(2));
    } else {
      throw new IllegalStateException("Expecting tcp port line; found '" + line + "'");
    }
    matcher = endpointPattern.matcher(endpoints[1]);
    if (matcher.matches()) {
      builder.remoteEndpoint(ipVersion, endpointHostMapper.apply(matcher.group(1)), matcher.group(2));
    } else {
      throw new IllegalStateException("Expecting tcp port line; found '" + line + "'");
    }

    builder.state(BusyPort.TcpState.fromNettopString(fields[1]));
  }

  /**
   * Pattern for parsing IPv4 endpoints expressed by {@code nettop}.
   * <dl>
   *   <dd>Group 1</dd><dt>IP Address</dt>
   *   <dd>Group 2</dd><dt>Port</dt>
   * </dl>
   */
  private static final Pattern TCP4_ENDPOINT_PATTERN = Pattern.compile("([^:]+):(\\*|\\d+)");

  /**
   * Pattern for parsing IPv6 endpoints expressed by {@code nettop}.
   * <dl>
   *   <dd>Group 1</dd><dt>IP Address</dt>
   *   <dd>Group 2</dd><dt>Port</dt>
   * </dl>
   * {@code nettop} separates the IP address and port with a period ('.').  The port can be
   * a number or an asterisk ('*') when used as the undesignated target for a listening port.
   */
  private static final Pattern TCP6_ENDPOINT_PATTERN = Pattern.compile("(?:([^%.]+(?:%[^.]+)?)|(?:\\*))\\.(\\*|\\d+)");

  private static class InetEndpointPair {
    private final InetSocketAddress localEndpoint;
    private final InetSocketAddress remoteEndpoint;

    private InetEndpointPair(InetSocketAddress localEndpoint, InetSocketAddress remoteEndpoint) {
      this.localEndpoint = localEndpoint;
      this.remoteEndpoint = remoteEndpoint;
    }

    private InetSocketAddress getLocalEndpoint() {
      return localEndpoint;
    }

    private InetSocketAddress getRemoteEndpoint() {
      return remoteEndpoint;
    }
  }


  /**
   * Describes an active TCP port and associates it with the controlling process.
   */
  @SuppressWarnings("unused")
  public static class BusyPort {
    private final long processId;
    private final InetSocketAddress localEndpoint;
    private final InetSocketAddress remoteEndpoint;
    private final TcpState state;
    private final String shortCommand;
    private final String commandLine;

    private BusyPort(long processId,
                     InetSocketAddress localEndpoint,
                     InetSocketAddress remoteEndpoint,
                     TcpState state,
                     String shortCommand,
                     String commandLine) {
      this.processId = processId;
      this.localEndpoint = localEndpoint;
      this.remoteEndpoint = remoteEndpoint;
      this.state = state;
      this.shortCommand = shortCommand;
      this.commandLine = commandLine;
    }


    /**
     * Gets the process id associated with this connection.
     * @return the process id
     */
    public long processId() {
      return processId;
    }

    /**
     * Gets the local endpoint socket address.
     * @return the local endpoint socket address
     */
    public InetSocketAddress localEndpoint() {
      return localEndpoint;
    }

    /**
     * Gets the remote endpoint socket address.  If {@link #localEndpoint()}
     * is a <i>listening</i> port, the value returned uses a socket number of
     * zero and an IP address corresponding to a
     * {@link InetAddress#isAnyLocalAddress() wildcard address}.
     * @return the remote endpoint socket address
     */
    public InetSocketAddress remoteEndpoint() {
      return remoteEndpoint;
    }

    /**
     * Gets the state of this TCP connection.
     * @return the TCP connection state
     */
    public TcpState state() {
      return state;
    }

    /**
     * Gets the <i>short</i> representation of the command as provided
     * by the TCP connection information source.
     * @return the short command string
     */
    public String shortCommand() {
      return shortCommand;
    }

    /**
     * Gets the <i>long</i> representation of the command.  This value
     * is the full command line if available.
     * @return the long command string; may be {@code null}
     */
    public String commandLine() {
      return commandLine;
    }

    private static Builder builder() {
      return new Builder();
    }

    private static Builder builder(BusyPort busyPort) {
      return new Builder(busyPort);
    }

    /**
     * Produces a print-suitable string of this {@code BusyPort} instance.  This method displays only
     * one of {@code shortCommand} or {@code commandLine} -- if {@code commandLine} is set, its value is
     * included; otherwise, the {@code shortCommand} value is used.
     * @param fieldSeparator the field separator to use; if {@code null} or empty, a single space is used
     * @return a print-suitable string
     */
    public String toString(String fieldSeparator) {
      if (fieldSeparator == null || fieldSeparator.isEmpty()) {
        fieldSeparator = " ";
      }
      StringBuilder sb = new StringBuilder();
      sb.append(processId);
      sb.append(fieldSeparator).append(localEndpoint);

      sb.append(fieldSeparator);
      if (remoteEndpoint == null) {
        sb.append("*.*");
      } else {
        sb.append(remoteEndpoint);
      }

      sb.append(fieldSeparator).append(state);

      sb.append(fieldSeparator);
      if (commandLine == null || commandLine.isEmpty()) {
        sb.append(shortCommand);
      } else {
        sb.append(commandLine);
      }

      return sb.toString();
    }

    @Override
    public String toString() {
      return "BusyPort{" + "processId=" + processId +
          ", localEndpoint=" + localEndpoint +
          ", remoteEndpoint=" + remoteEndpoint +
          ", state='" + state + '\'' +
          ", shortCommand='" + shortCommand + '\'' +
          ", commandLine='" + commandLine + '\'' +
          '}';
    }

    @SuppressWarnings("UnusedReturnValue")
    private static class Builder {
      private long processId;
      private InetSocketAddress localEndpoint;
      private InetSocketAddress remoteEndpoint;
      private TcpState state;
      private String shortCommand;
      private String commandLine;

      Builder() {
      }

      Builder(BusyPort busyPort) {
        this.processId = busyPort.processId;
        this.localEndpoint = busyPort.localEndpoint;
        this.remoteEndpoint = busyPort.remoteEndpoint;
        this.state = busyPort.state;
        this.shortCommand = busyPort.shortCommand;
        this.commandLine = busyPort.commandLine;
      }

      /**
       * Constructs a {@link BusyPort} instance from the content of this {@code Builder}.  This
       * method does not reset the content of the {@code Builder}.
       * @return a new {@code BusyPort} instance
       */
      BusyPort build() {
        return new BusyPort(processId, localEndpoint, remoteEndpoint, state, shortCommand, commandLine);
      }

      /**
       * Resets the TCP information in this {@code Builder}.  The process-related information
       * is retained.
       * @return {@code this} {@code Builder}
       */
      Builder resetTcp() {
        this.localEndpoint = null;
        this.remoteEndpoint = null;
        this.state = null;
        return this;
      }

      /**
       * Sets the process id for the connection.
       * @param processId the process id extracted from command output
       * @return {@code this} {@code Builder}
       */
      Builder processId(String processId) {
        requireNonNull(processId, "processId");
        if (processId.isEmpty()) {
          throw new IllegalArgumentException("processId cannot be empty");
        }
        if (processId.charAt(0) == '-' || processId.charAt(0) == '+') {
          throw new IllegalArgumentException("processId '" + processId + "' is poorly formed");
        }
        try {
          this.processId = Long.parseLong(processId);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("processId '" + processId + "' is poorly formed", e);
        }
        return this;
      }

      /**
       * Sets the short command string for the connection.
       * @param shortCommand the short command string extracted from command output
       * @return {@code this} {@code Builder}
       */
      Builder shortCommand(String shortCommand) {
        this.shortCommand = shortCommand;
        return this;
      }

      /**
       * Sets the full/long command string for the connection.
       * @param commandLine the  full command string extracted from command output
       * @return {@code this} {@code Builder}
       */
      Builder commandLine(String commandLine) {
        this.commandLine = commandLine;
        return this;
      }

      /**
       * Sets the {@link TcpState} for the connection.
       * @param state the {@code TcpState}
       * @return {@code this} {@code Builder}
       */
      Builder state(TcpState state) {
        this.state = state;
        return this;
      }

      /**
       * Sets the local endpoint for the connection.
       * @param endpoint the local endpoint
       * @return {@code this} {@code Builder}
       * @see #localEndpoint(IPVersion, String, String)
       */
      Builder localEndpoint(InetSocketAddress endpoint) {
        this.localEndpoint = endpoint;
        return this;
      }

      /**
       * Sets the local endpoint for the connection.
       * @param ipVersion the {@code IPVersion} to use when interpreting ambiguous {@code address} values
       * @param address the IP address in a form suitable to {@link InetAddress#getByName(String)};
       *                an asterisk ({@code *}) may be used to designate "any address"
       * @param port the port number for the connection; an asterisk ({@code *}) may be used to designate
       *             an unassigned port
       * @return {@code this} {@code Builder}
       * @see #localEndpoint(InetSocketAddress)
       */
      Builder localEndpoint(IPVersion ipVersion, String address, String port) {
        this.localEndpoint = processEndpoint(ipVersion, address, port);
        return this;
      }

      /**
       * Sets the remote endpoint for the connection.
       * @param endpoint the remote endpoint
       * @return {@code this} {@code Builder}
       * @see #remoteEndpoint(IPVersion, String, String)
       */
      Builder remoteEndpoint(InetSocketAddress endpoint) {
        this.remoteEndpoint = endpoint;
        return this;
      }

      /**
       * Sets the remote endpoint for the connection.
       * @param ipVersion the {@code IPVersion} to use when interpreting ambiguous {@code address} values
       * @param address the IP address in a form suitable to {@link InetAddress#getByName(String)};
       *                an asterisk ({@code *}) may be used to designate "any address"
       * @param port the port number for the connection; an asterisk ({@code *}) may be used to designate
       *             an unassigned port
       * @return {@code this} {@code Builder}
       * @see #remoteEndpoint(InetSocketAddress)
       */
      Builder remoteEndpoint(IPVersion ipVersion, String address, String port) {
        this.remoteEndpoint = processEndpoint(ipVersion, address, port);
        return this;
      }

      private InetSocketAddress processEndpoint(IPVersion ipVersion, String address, String port) {
        return requireNonNull(ipVersion, "ipVersion").getInetSocketAddress(address, port);
      }
    }

    /**
     * Enumerates the types of IP addresses supported.
     */
    public enum IPVersion {
      IPV4(Inet4Address.class, 4, "IPv4", "tcp4") {
        @Override
        public byte[] loopback() {
          // Use 127.0.0.1 from 127.0.0.0/8 (RFC-6890)
          return new byte[] { 127, 0, 0, 1 };
        }

        @Override
        public InetAddress unspecified() {
          try {
            // IPv4 lacks "unspecified"; use 0.0.0.0 from 0.0.0.0/8 "this host on this network" (RFC-6890)
            byte[] address = new byte[IPV4.addressBytes];
            return InetAddress.getByAddress(address);
          } catch (UnknownHostException e) {
            throw new AssertionError("Failed to get InetAddress for 0.0.0.0");
          }
        }
      },
      IPV6(Inet6Address.class, 16, "IPv6", "tcp6") {
        @Override
        public byte[] loopback() {
          // Use ::1/128 (RFC-6890)
          byte[] loopback = new byte[IPV6.addressBytes];
          loopback[IPV6.addressBytes - 1] = 1;
          return loopback;
        }

        @Override
        public InetAddress unspecified() {
          try {
            // Using the IPv6 "unspecified" address -- ::/128 (RFC-6890)
            byte[] address = new byte[IPV6.addressBytes];
            return InetAddress.getByAddress(address);
          } catch (UnknownHostException e) {
            throw new AssertionError("Failed to get InetAddress for ::/128");
          }
        }
      },
      ;

      private final int addressBytes;
      private final List<String> versionIds;

      IPVersion(Class<? extends InetAddress> addressClass, int addressBytes, String... versionIds) {
        this.addressBytes = addressBytes;
        this.versionIds = Arrays.asList(versionIds);
      }

      /**
       * Gets the byte-array form of "any local address" for this {@code IPVersion}.
       * @return a byte array for "any local address"
       */
      public byte[] anyLocal() {
        return new byte[addressBytes];
      }

      /**
       * Gets the byte-array form of the "loopback address" for this {@code IPVersion}.
       * @return a byte array for "loopback address
       */
      public abstract byte[] loopback();

      /**
       * Returns an {@link InetAddress} instance indicating an "unspecified"
       * (or failed) host address.
       * @return an "unspecified" address
       */
      public abstract InetAddress unspecified();

      /**
       * Gets an {@link InetSocketAddress} instance composed of the IP address and port provided.
       * @param address the IP address conforming to {@code this} {@code IPVersion}; a value of {@code *}
       *                is interpreted as the "any local" address
       * @param port the socket port; a value of {@code *} is interpreted as 0 (undesignated)
       * @return an {@code InetSocketAddress} instance for {@code address} and {@code port}; the
       *        {@code InetSocketAddress} returned may not be of the type implied by {@code this}
       *        {@code IPVersion} instance -- {@code lsof} does not maintain strict type alignment
       *        for IP addresses
       */
      public InetSocketAddress getInetSocketAddress(String address, String port) {
        requireNonNull(address, "address");
        if (address.isEmpty()) {
          throw new IllegalArgumentException("address cannot be empty");
        }
        requireNonNull(port, "port");
        if (port.isEmpty()) {
          throw new IllegalArgumentException("port cannot be empty");
        }

        int portNumber;
        if ("*".equals(port)) {
          portNumber = 0;
        } else {
          portNumber = Integer.parseInt(port);
        }

        InetAddress inetAddress;
        try {
          if ("*".equals(address)) {
            inetAddress = InetAddress.getByAddress(this.anyLocal());
          } else {
            inetAddress = InetAddress.getByName(address);
          }
        } catch (UnknownHostException e) {
          if (LOGGER.isTraceEnabled()) {
            LOGGER.debug("Failed to convert address={}, port={} to an InetSocketAddress; using \"unspecified\" address", address, port, e);
          } else {
            LOGGER.debug("Failed to convert address={}, port={} to an InetSocketAddress; using \"unspecified\" address: {}", address, port, e.toString());
          }
          inetAddress = this.unspecified();
        }
        return new InetSocketAddress(inetAddress, portNumber);
      }

      /**
       * Determines the {@code IPVersion} from the identifier provided.
       * @param versionId the identifier to check
       * @return the {@code IPVersion} corresponding to {@code versionId}
       * @throws EnumConstantNotPresentException if {@code versionId} is not a recognized {@code IPVersion} designation
       */
      public static IPVersion fromId(String versionId) {
        for (IPVersion ipVersion : values()) {
          if (ipVersion.versionIds.contains(versionId)) {
            return ipVersion;
          }
        }
        throw new EnumConstantNotPresentException(IPVersion.class, versionId);
      }
    }

    /**
     * TCP Connection states.
     * <p>
     * This class maps the states observed by the utilities used to examine the TCP connections on the
     * platform to a common form.  Where the state maps to a state described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc793">RFC-793 Transmission Control Protocol</a>,
     * that state name is used for the enumeration constant.
     * <p>
     * The following diagram shows the usual TCP state transitions.  For a discussion of this diagram,
     * see <i>RFC-793 Transmission Control Protocol</i> and
     * <i>TCP/IP Illustrated, Volume 1, 1ed: The Protocols</i> by W. Richard Stevens.
     * <table>
     *   <tbody>
     *     <tr>
     *       <td><img src="doc-files/tcp_state_diagram.png" alt="TCP State Diagram"></td>
     *       <td><img src="doc-files/tcp_state_diagram_legend.png" alt="Legend for TCP State Diagram"></td>
     *     </tr>
     *   </tbody>
     * </table>
     * <ul>
     *   <li>
     *     The CLOSE_WAIT and FIN_WAIT_2 states are paired -- one side of the connection will be in CLOSE_WAIT and
     *     the other in FIN_WAIT_2.  Until the application in CLOSE_WAIT actually <i>closes</i> the connection, the
     *     connection remains with neither socket endpoint being available for re-use.  (According to (Stevens 1994),
     *     some TCP implementations will move a socket in FIN_WAIT_2 to CLOSED after 10+ minutes of idle time.)
     *   </li>
     *   <li>
     *     The TIME_WAIT state is applied to a newly closed connection and is used to prevent immediate re-use of
     *     the socket to allow delivery of potentially delayed packets.  The amount of time the socket remains in
     *     TIME_WAIT varies by implementation (and potentially configuration) but is commonly 2 minutes.
     *   </li>
     * </ul>
     *
     *
     * <h3>Implementation Notes</h3>
     * <h4>Mac OS X</h4>
     * The {@code nettop} utility used for Mac OS X is closed source but source for a variant
     * ({@code netbottom.c}) is available.  The TCP state constants returned by {@code nettop}
     * are neither documented nor listed in an available source file.  The values used in this
     * enum are <i>presumed</i> from the known values and the names of external references,
     * in the form of {@code kNStatSrcTcpStateXxxxxxxxxx}, made by {@code netbottom.c}.
     *
     * @see <a href="https://docs.microsoft.com/en-us/powershell/module/nettcpip/get-nettcpconnection?view=win10-ps">
     *     Get-NetTCPConnection</a>
     * @see <a href="https://docs.microsoft.com/en-us/previous-versions/windows/desktop/nettcpipprov/msft-nettcpconnection">
     *     MSFT_NetTCPConnection class</a>
     * @see <a href="https://raw.githubusercontent.com/lsof-org/lsof/df01ed314cf9e98a62058501684ab54f44c18c42/dialects/linux/dsock.c">
     *   lsof/dialects/linux/dsock.c</a>
     * @see <a href="https://raw.githubusercontent.com/apple/darwin-xnu/0a798f6738bc1db01281fc08ae024145e84df927/bsd/netinet/tcp_fsm.h">
     *   apple/darwin-xnu/bsd/netinet/tcp_fsm.h</a>
     * @see <a href="http://newosxbook.com/src.jl?tree=listings&file=netbottom.c"><code>netbottom.c</code> Sources</a>
     * @see "Stevens, W. Richard (1994) <i>TCP/IP Illustrated, Volume 1, 1ed: The Protocols</i>, Addison-Wesley"
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc793">RFC-793 Transmission Control Protocol</a>
     */
    /*
     * The following PlantUML diagrams are processed using the com.github.funthomas424242:plantuml-maven-plugin plugin.
     *
     * @startuml doc-files/tcp_state_diagram.png
     * title TCP State Transition Diagram\nAdapted from Stevens, W. Richard (1994) //TCP/IP Illustrated, Volume 1, 1ed: The Protocols//, Addison-Wesley, Figure 18.12, p. 241
     * hide empty description
     *
     * [*] --> CLOSED
     * LISTEN : //passive open//
     * SYN_SENT : //active open//
     * ESTABLISHED : //data transfer state//
     *
     * CLOSED -down[#blue,dashed]-> LISTEN : appl: **passive open**\nsend: <nothing>
     * LISTEN -down[#blue,dashed]-> SYN_RCVD : recv: SYN\nsend: SYN, ACK
     * LISTEN -down-> SYN_SENT : appl: **send data**\nsend: SYN
     * CLOSED -down[#blue]-> SYN_SENT : appl: **active open**\nsend: SYN
     * SYN_RCVD --> LISTEN : recv: RST
     * SYN_RCVD -down[#blue,dashed]-> ESTABLISHED : recv: ACK\nsend: <nothing>
     * SYN_SENT -down[#blue]-> ESTABLISHED : recv: SYN, ACK\nsend: ACK
     * SYN_SENT --> CLOSED : appl: **close**\nor timeout
     * SYN_SENT -left-> SYN_RCVD : recv: SYN\nsend: SYN, ACK\n//simultaneous open//
     *
     * state "//passive close//" as passiveClose {
     *   ESTABLISHED -right[#blue,dashed]-> CLOSE_WAIT : revc: FIN\nsend: ACK
     *   CLOSE_WAIT -down[#blue,dashed]-> LAST_ACK : appl: **close**\nsend: FIN
     *   LAST_ACK -up[#blue]-> CLOSED : recv: ACK\nsend: <nothing>
     * }
     *
     * state "//active close//" as activeClose {
     *   FIN_WAIT_1 <-down- SYN_RCVD : appl: **close**\nsend: FIN
     *   FIN_WAIT_1 <-down[#blue]- ESTABLISHED : appl: **close**\nsend: FIN
     *   FIN_WAIT_1 -down[#blue]-> FIN_WAIT_2 : recv: ACK\nsend: <nothing>
     *   FIN_WAIT_1 -> CLOSING : recv: FIN\nsend: ACK
     *   FIN_WAIT_1 -> TIME_WAIT : recv: FIN, ACK\nsend: ACK
     *   FIN_WAIT_2 -right[#blue]-> TIME_WAIT : recv: FIN\nsend: ACK
     *   CLOSING -down-> TIME_WAIT : recv: ACK\nsend: <nothing>
     *   CLOSING : //simultaneous close//
     *   TIME_WAIT -up[#blue]-> CLOSED
     *   TIME_WAIT : //2MSL timeout//
     * }
     * @enduml
     *
     * @startuml doc-files/tcp_state_diagram_legend.png
     * scale 0.75
     * state Legend #lightblue {
     *      !$arrow_length = %string("." + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160) + %chr(160))
     *
     *     state "normal transition for client" as client {
     *      [*] -right[#blue]-> [*] : $arrow_length
     *     }
     *     --
     *     state "normal transition for server" as server {
     *      [*] -right[dashed]-> [*] : $arrow_length
     *     }
     *     --
     *     state "application-initiated state transitions" as appl {
     *       [*] -right-> [*] : appl: **<operation>**
     *     }
     *     --
     *     state "segment receipt-initiated state transitions" as recv {
     *       [*] -right-> [*] : recv: <message>
     *     }
     *     --
     *     state "segment sent as a result of state transition" as send {
     *       [*] -right-> [*] : send: <message>
     *     }
     * }
     * @enduml
     *
     */
    public enum TcpState {
      /** Connection state is unknown (MSFT_NetTCPConnection). **/
      UNKNOWN("", 0, "", ""),
      /** Not connected (RFC-793). **/
      CLOSED("Closed", 1, "Closed", "CLOSED"),
      /** Awaiting a connection request (RFC-793). **/
      LISTEN("Listen", 2, "Listen", "LISTEN"),
      /** Awaiting a matching connection request after having sent a connection request (RFC-793). **/
      SYN_SENT("SynSent", 3, "SynSent", "SYN_SENT"),
      /** Awaiting connection request acknowledgement after having sent and received a connection request (RFC-793). **/
      SYN_RECEIVED("SynReceived", 4, "SynReceived", "SYN_RECV", "SYN_RCVD"),
      /** An "open" TCP connection over which data may be transferred (RFC-793). **/
      ESTABLISHED("Established", 5, "Established", "ESTABLISHED"),
      /** Awaiting connection termination request or acknowledgement of sent connection termination request from remote (RFC-793). **/
      FIN_WAIT_1("FinWait1", 6, "FinWait1", "FIN_WAIT_1"),
      /** Awaiting connection termination request from remote (RFC-793). **/
      FIN_WAIT_2("FinWait2", 7, "FinWait2", "FIN_WAIT_2"),
      /** Awaiting application-level connection termination (socket close) (RFC-793). **/
      CLOSE_WAIT("CloseWait", 8, "CloseWait", "CLOSE_WAIT"),
      /** Awaiting connection termination request acknowledgement from remote (RFC-793). **/
      CLOSING("Closing", 9, "Closing", "CLOSING"),
      /** Awaiting acknowledgement of connection termination request sent to remote (RFC-793). **/
      LAST_ACK("LastAck", 10, "LastAck", "LAST_ACK"),
      /** Awaiting expiration of TCP implementation-defined timeout before socket of closed connection can be re-used (RFC-793). **/
      TIME_WAIT("TimeWait", 11, "TimeWait", "TIME_WAIT"),
      /** Indicates the control block representing the TCP connection is being deleted (MSFT_NetTCPConnection). **/
      DELETE_TCB("DeleteTCB",  12, "", ""),
      /** Awaiting application {@code listen}, {@code connect}, {@code accept}, or {@code close} call following {@code bind} (lsof). **/
      BOUND("Bound", -1, "", "BOUND"),
      /** Awaiting application {@code close} following a socket/connection error (lsof/Linux). **/
      CLOSE("", -1, "", "CLOSE"),
      /** Awaiting application {@code bind}, {@code connect},  or {@code close} on socket (lsof).  **/
      IDLE("", -1, "", "IDLE"),
      ;

      /**
       * String representation used by Microsoft.
       */
      private final String msStateString;
      /**
       * Number associated with Microsoft's .Net TcpState enum.
       */
      private final int msStateNumber;
      /**
       * State value reported by Mac OS X {@code nettop} command.
       */
      private final String nettopStateString;
      /**
       * State values reported by {@code lsof}.  Multiple values are permitted because of
       * differences in representation by platform.
       */
      private final List<String> lsofStateStrings;

      TcpState(String msStateString, int msStateNumber, String nettopStateString, String... lsofStateStrings) {
        this.msStateString = msStateString;
        this.msStateNumber = msStateNumber;
        this.nettopStateString = nettopStateString;
        this.lsofStateStrings = new ArrayList<>(Arrays.asList(lsofStateStrings));
      }

      /**
       * Determine the {@code TcpCode} from the state string from the {@code nettop} command.
       * @param nettopStateString the state string from {@code nettop}
       * @return the {@code TcpState} corresponding to {@code nettopStateString}
       * @throws EnumConstantNotPresentException if {@code nettopStateString} is not a recognized {@code TcpState}
       */
      public static TcpState fromNettopString(String nettopStateString) {
        for (TcpState state : values()) {
          if (state.nettopStateString.equals(nettopStateString)) {
            return state;
          }
        }
        throw new EnumConstantNotPresentException(TcpState.class, nettopStateString);
      }

      /**
       * Determine the {@code TcpCode} from the state string from the {@code lsof} command.
       * @param lsofStateString the state string from {@code lsof}
       * @return the {@code TcpState} corresponding to {@code lsofStateString}
       * @see <a href="https://github.com/lsof-org/lsof">lsof-org/lsof Source Repository</a>
       * @throws EnumConstantNotPresentException if {@code lsofStateString} is not a recognized {@code TcpState}
       */
      public static TcpState fromLsofString(String lsofStateString) {
        for (TcpState state : values()) {
          if (state.lsofStateStrings.contains(lsofStateString)) {
            return state;
          }
        }
        throw new EnumConstantNotPresentException(TcpState.class, lsofStateString);
      }

      /**
       * Determine the {@code TcpState} from the state string from the Microsoft {@code Get-NetTCPConnection} cmdlet.
       * @param msStateString the state string from the {@code Get-NetTCPConnection} cmdlet
       * @return the {@code TcpState} corresponding to {@code msStateString}
       * @see <a href="https://docs.microsoft.com/en-us/previous-versions/windows/desktop/nettcpipprov/msft-nettcpconnection">
       *     MSFT_NetTCPConnection class</a>
       * @throws EnumConstantNotPresentException if {@code msStateString} is not a recognized {@code TcpState}
       */
      public static TcpState fromMicrosoftString(String msStateString) {
        for (TcpState state : values()) {
          if (state.msStateString.equalsIgnoreCase(msStateString)) {
            return state;
          }
        }
        throw new EnumConstantNotPresentException(TcpState.class, msStateString);
      }

      /**
       * Determine the {@code TcpState} from the state number corresponding to a Microsoft
       * {@code System.Net.NetworkInformation.TcpState} enum value.
       * @param msStateNumber the {@code TcpState} enum number
       * @return the {@code TcpState} corresponding to {@code msStateNumber}
       * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.net.networkinformation.tcpstate?view=netcore-3.1">
       *   TcpState Enum</a>
       * @throws EnumConstantNotPresentException if {@code msStateNumber} is not a recognized {@code TcpState}
       */
      @SuppressWarnings("unused")
      public static TcpState fromMicrosoftNumber(int msStateNumber) {
        for (TcpState state : values()) {
          if (state.msStateNumber == msStateNumber) {
            return state;
          }
        }
        throw new EnumConstantNotPresentException(TcpState.class, Integer.toString(msStateNumber));
      }
    }
  }

  /**
   * Thrown to indicate the failure of a host command used in construction of TCP connection status information.
   */
  public static final class HostExecutionException extends IOException {
    private static final long serialVersionUID = 3134255439257149986L;

    private final Shell.Result result;

    public HostExecutionException(String message, Throwable cause) {
      super(message, cause);
      this.result = null;
    }

    public HostExecutionException(String command, Shell.Result result, Throwable cause) {
      super(message(command, result), cause);
      this.result = result;
    }

    public HostExecutionException(String command, Shell.Result result) {
      super(message(command, result));
      this.result = result;
    }

    private static String message(String command, Shell.Result result) {
      String messageBase = "Failed to run command: " + command;
      if (null == result) {
        return messageBase;
      } else {
        return messageBase + "; rc=" + result.exitCode() + ("\n    " + String.join("\n    ", result.lines()));
      }
    }

    public int exitCode() {
      if (result != null) {
        return result.exitCode();
      } else {
        return -1;
      }
    }

    public List<String> output() {
      if (result != null) {
        return result.lines();
      } else {
        return Collections.emptyList();
      }
    }
  }
}
