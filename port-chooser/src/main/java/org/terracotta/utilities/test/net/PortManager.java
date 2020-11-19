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
package org.terracotta.utilities.test.net;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;

import static java.lang.Integer.toHexString;
import static java.lang.System.identityHashCode;
import static java.util.Objects.requireNonNull;

/**
 * Manages TCP port reservation/de-reservation while attempting to avoid
 * intra- and extra-JVM interference.
 * <p>
 * The port manager maintains two levels of reservation -- intra-JVM using
 * an internal {@link BitSet} and inter-JVM using locks against a
 * {@link java.io.RandomAccessFile}
 * allocated in a system-wide, world-writable, system-dependent location.
 * To avoid reservation/use races,
 * <a href="https://en.wikipedia.org/wiki/Ephemeral_port">ephemeral ports</a>
 * are not used.
 * <p>
 * All users of {@code PortManager} in a JVM must use the same {@code PortManager}
 * instance to ensure proper intra-JVM reservations.
 */
public class PortManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(PortManager.class);

  private static final PortManager INSTANCE = new PortManager();
  static {
    emitInstanceNotification("Instantiated");
  }

  /**
   * Gets the singleton instance of {@code PortManager} to use in a JVM.
   * @return the singleton {@code PortManager} instance
   */
  public static PortManager getInstance() {
    emitInstanceNotification("Using");
    return INSTANCE;
  }

  private static final int MAXIMUM_PORT_NUMBER = 65535;
  private static final int MINIMUM_ASSIGNABLE_PORT_COUNT = 1024;

  private static final InetAddress LOCALHOST;
  static {
    InetAddress localHost = null;
    try {
      // Tests use "localhost" as the server host name which _does_ go through name resolution ...
      localHost = InetAddress.getByName("localhost");
    } catch (UnknownHostException e) {
      LOGGER.warn("Unable to obtain an InetAddress for localhost via InetAddress.getByName(\"localhost\")", e);
    }
    LOCALHOST = localHost;
  }


  private final Random rnd = new SecureRandom();

  /**
   * Marks the ports which cannot be allocated by this class.  A {@code true}
   * bit in this {@code BitSet} indicates a non-reservable (restricted) port.
   */
  private final BitSet restrictedPorts = new BitSet();

  /**
   * Port allocation map.  A zero bit represents an available port.
   */
  private final BitSet portMap = new BitSet(MAXIMUM_PORT_NUMBER + 1);

  /**
   * References to outstanding {@link PortRef} instances.
   */
  private final Map<Integer, AllocatedPort> allocatedPorts = new HashMap<>();

  /**
   * A {@code ReferenceQueue} accepting {@link PortRef} instances no longer held
   * by the reserving party.
   */
  private final ReferenceQueue<PortRef> dereferencedPorts = new ReferenceQueue<>();

  /**
   * Manages port reservations at a system level using file locks.
   */
  private final SystemLevelLocker systemLevelLocker = new SystemLevelLocker();

  /**
   * The number of non-reserved ports.  This is the absolute upper limit of the
   * number of ports that can possibly be assigned.
   */
  private final int assignablePortCount;

  /**
   * Creates a {@code PortManager} instance primed for reserving ports
   * outside of current system's the ephemeral port range.
   */
  private PortManager() {
    // Prevent the assignment of ports in the system port range
    portMap.set(0, 1025);

    /*
     * Prevent the assignment of ports in the ephemeral/dynamic port range.
     * Doing so prevents the port from being dynamically allocated for a
     * connection by some application that doesn't participate in this
     * reservation scheme.
     */
    EphemeralPorts.Range range = EphemeralPorts.getRange();
    portMap.set(range.getLower(), range.getUpper() + 1);

    restrictedPorts.or(portMap);

    assignablePortCount = MAXIMUM_PORT_NUMBER + 1 - portMap.cardinality();
    if (assignablePortCount < MINIMUM_ASSIGNABLE_PORT_COUNT) {
      LOGGER.warn("\n*****************************************************************************************" +
              "\nOnly {} ports available for assignment (ephemeral range=[{}]); operations may be unstable" +
              "\n*****************************************************************************************",
          assignablePortCount, range);
    }
  }

  /**
   * Indicates if the designated port is in the range of ports that <i>may</i> be allocated
   * by this class.  If the designated port is in the range of ports that may be allocated,
   * the {@link #reserve(int)} method may be used to attempt to allocate the port.  The port
   * may also be returned from the {@link #reservePort()} and {@link #reservePorts(int)} methods.
   * @throws IllegalArgumentException if {@code port} is not between 0 and
   *    {@value #MAXIMUM_PORT_NUMBER} (inclusive)
   */
  public boolean isReservablePort(int port) {
    if (port < 0 || port > MAXIMUM_PORT_NUMBER) {
      throw new IllegalArgumentException("Port " + port + " is not a valid port number");
    }
    return !restrictedPorts.get(port);
  }

  /**
   * Gets the <i>active</i> {@link PortRef} instance for the designated port.
   * <p>
   * This method returns a reference to the most recent {@code PortRef} created
   * for the designated port if the {@code PortRef} is both <i>strongly reachable</i>
   * and not closed.  The result of this method may be immediately stale -- the
   * {@code PortRef} may be closed between the time the {@code PortRef} is checked
   * and the reference returned to the called.
   * @param port the port number for which the {@code PortRef} is returned
   * @return an {@code Optional} of the {@code PortRef} for {@code port} if the
   *      {@code PortRef} is both strongly reachable and not closed;
   *      {@code Optional.empty} if the {@code PortRef} for {@code port} is either
   *      not strongly reachable or closed
   * @throws IllegalArgumentException if {@code port} is not between 0 and
   *    {@value #MAXIMUM_PORT_NUMBER} (inclusive) or is not a reservable port
   */
  public synchronized Optional<PortRef> getPortRef(int port) {
    if (port < 0 || port > MAXIMUM_PORT_NUMBER) {
      throw new IllegalArgumentException("Port " + port + " is not a valid port number");
    }
    if (restrictedPorts.get(port)) {
      throw new IllegalArgumentException("Port " + port + " is not reservable");
    }

    cleanReleasedPorts();
    if (!portMap.get(port)) {
      return Optional.empty();
    }

    return Optional.ofNullable(allocatedPorts.get(port))
        .map(AllocatedPort::get)
        .filter(portRef -> !portRef.isClosed());
  }

  /**
   * Attempts to reserve the specified port.
   * <p>
   * The {@link PortRef#close()} method should be called when the port is no longer
   * needed.  However, the returned {@code PortRef} instance must strongly-referenced as
   * long as the port is required -- once the {@code PortRef} instance becomes
   * weakly-referenced, the port reservation may be released.
   *
   * @param port the port number to reserve
   * @return an {@code Optional} containing the {@link PortRef} if the
   *    port was successfully reserved; {@code Optional.empty} if the
   *    port could not be reserved
   * @throws IllegalArgumentException if {@code port} is not between 0 and
   *    {@value #MAXIMUM_PORT_NUMBER} (inclusive) or is not a reservable port
   * @throws IllegalStateException if reservation fails due to an error
   */
  public synchronized Optional<PortRef> reserve(int port) {
    if (port < 0 || port > MAXIMUM_PORT_NUMBER) {
      throw new IllegalArgumentException("Port " + port + " is not a valid port number");
    }
    if (restrictedPorts.get(port)) {
      throw new IllegalArgumentException("Port " + port + " is not reservable");
    }

    cleanReleasedPorts();
    if (portMap.get(port)) {
      LOGGER.trace("Port {} is already reserved", port);
      return Optional.empty();
    }

    return Optional.ofNullable(reserveInternal(port));
  }

  /**
   * Reserves the specified number of ports returning a list of {@link PortRef}
   * instances for those reserved.
   * <p>
   * The {@link PortRef#close()} method should be called on each port when no longer
   * needed.  However, each returned {@code PortRef} instance must strongly-referenced as
   * long as the port is required -- once the {@code PortRef} instance becomes
   * weakly-referenced, the port reservation may be released.
   *
   * @param portCount the number of ports to reserve
   * @return the list of {@code PortRef} instances for the reserved ports; the
   *    ports <b>are not</b> assigned sequentially
   * @throws IllegalArgumentException if {@code portCount} is less than 1 or greater
   *      than the number of reservable ports
   * @throws IllegalStateException if the reservable ports are exhausted or
   *      reservation fails due to an error
   */
  public List<PortRef> reservePorts(int portCount) {
    if (portCount <= 0 || portCount > assignablePortCount) {
      throw new IllegalArgumentException("portCount " + portCount + " not valid");
    }
    List<PortRef> ports = new ArrayList<>(portCount);
    try {
      for (int i = 0; i < portCount; i++) {
        ports.add(reservePort());
      }
      return Collections.unmodifiableList(ports);
    } finally {
      if (ports.size() < portCount) {
        ports.forEach(r -> {
          try {
            r.close();
          } catch (Exception ignored) {
          }
        });
      }
    }
  }

  /**
   * Reserve a single, randomly selected port.
   * <p>
   * The {@link PortRef#close()} method should be called on the port when no longer
   * needed.  However, the returned {@code PortRef} instance must strongly-referenced
   * as long as the port is required -- once the {@code PortRef} instance becomes
   * weakly-referenced, the port reservation may be released.
   *
   * @return a {@code PortRef} instance identifying the reserved port
   * @throws IllegalStateException if the reservable ports are exhausted or
   *      reservation fails due to an error
   */
  public synchronized PortRef reservePort() {
    cleanReleasedPorts();

    /*
     * Get a starting point for a free port search that's not a system or ephemeral port.
     */
    int startingPoint;
    while (restrictedPorts.get(startingPoint = rnd.nextInt(MAXIMUM_PORT_NUMBER + 1))) {
      // empty
    }
    LOGGER.trace("Starting port reservation search at {}", startingPoint);

    /*
     * Using the starting point obtained above, search for an unreserved port.
     * Continue until a reservable port is found or the pool of reservable ports
     * is exhausted.
     */
    BitSearch bitSearch = (rnd.nextBoolean() ? BitSearch.ASCENDING : BitSearch.DESCENDING);
    boolean switched = false;
    int candidatePort = startingPoint;
    while (true) {
      if ((candidatePort = bitSearch.nextFree(portMap).applyAsInt(candidatePort)) == -1) {
        if (!switched) {
          // Exhausted search on one direction; now try the other from the same starting point
          bitSearch = bitSearch.reverse();
          switched = true;
          candidatePort = startingPoint;
          candidatePort = bitSearch.nextFree(portMap).applyAsInt(candidatePort);
        }
      }

      if (candidatePort == -1) {
        throw new IllegalStateException("No port available for reservation");
      }

      PortRef portRef;
      if ((portRef = reserveInternal(candidatePort)) != null) {
        return portRef;
      } else {
        candidatePort = bitSearch.successor(candidatePort);
      }
    }
  }

  /**
   * Releases {@code PortRef} instances no longer strongly held.  These {@code PortRef}
   * instances are considered available for reuse.
   */
  private void cleanReleasedPorts() {
    Reference<? extends PortRef> reference;
    while ((reference = dereferencedPorts.poll()) != null) {
      if (reference instanceof AllocatedPort) {
        AllocatedPort allocatedPort = (AllocatedPort)reference;
        LOGGER.info("Port {} dereferenced; releasing reservation", allocatedPort.port);
        allocatedPort.release();
      } else {
        LOGGER.warn("Unexpected Reference observed: {}", reference);
      }
    }
  }

  /**
   * Reserve and vet the specified candidate port.
   *
   * @param candidatePort the port to vet and reserve
   * @return a new {@code PortRef} instance is the reservation was successful;
   *      {@code null} otherwise
   * @throws IllegalStateException if port reservation fails due to a locking error
   */
  @SuppressWarnings("try")
  private PortRef reserveInternal(int candidatePort) {
    LOGGER.trace("Vetting port {}", candidatePort);

    PortRef portRef = new PortRef(candidatePort);
    /*
     * We now have a candidate port; vet the port to ensure it is actually
     * available.
     */
    boolean releaseRequired = true;
    try {
      // 1) Mark the port reserved in presumption the vetting will succeed
      portMap.set(candidatePort);
      allocatedPorts.put(portRef.port, new AllocatedPort(portRef, dereferencedPorts));
      portRef.onClose(() -> release(candidatePort));

      // 2) Create a server Socket for the candidate port
      boolean systemLevelLockHeld;
      try (ServerSocket ignored = new ServerSocket(candidatePort)) {
        // 3) While the server socket is open, obtain the system-wide lock for the candidate port
        systemLevelLockHeld = systemLevelLocker.lock(portRef);
      }

      /*
       * 4) If the port is locked, check the client-side connectability; Windows
       *    has some odd behaviors in which an outstanding listener isn't required
       *    for a port to be connectable.
       */
      if (systemLevelLockHeld) {
        if (refusesConnect(candidatePort)) {
          ClassLoader classLoader = this.getClass().getClassLoader();
          LOGGER.info("Port {} reserved (JVM-level) by {}@{} ({}@{})",
              candidatePort,
              this.getClass().getSimpleName(),
              toHexString(identityHashCode(this)),
              classLoader.getClass().getSimpleName(),
              toHexString(identityHashCode(classLoader)));
          releaseRequired = false;   // Vetting successful; don't close PortRef on exit
          return portRef;
        } else {
          LOGGER.trace("Port {} failed refusesConnect", candidatePort);
        }
      } else {
        LOGGER.trace("Port {} failed to obtain system-level lock", candidatePort);
      }

    } catch (IllegalStateException e) {
      // Vetting failed for a non-recoverable reason
      LOGGER.error("Failed to reserve port {}; abandoning reservation", candidatePort, e);
      throw e;

    } catch (BindException e) {
      // Port is in use ...

    } catch (Exception e) {
      // Some other reservation failure
      LOGGER.warn("Failed to reserve port {}; checking next port", candidatePort, e);

    } finally {
      if (releaseRequired) {
        // Failed vetting -- remove the tentative reservation
        try {
          portRef.close();
        } catch (Exception ignored) {
        }
      }
    }

    return null;
  }

  private synchronized void release(int port) {
    portMap.clear(port);
    allocatedPorts.remove(port);
    LOGGER.info("Port {} released (JVM-level)", port);
  }

  /**
   * Determines if a client connection to the specified port is accepted or refused.
   * <p>
   * On some versions of Windows, a port can pass the server bind test and still not
   * really be free.  According to issue <a href="https://jira.terracotta.org/browse/MNK-5621">MNK-5621</a>,
   * some Windows services, like Remote Desktop (RDP) don't establish an open listener
   * but do respond to connection requests on their assigned port(s).  A "failure to connect"
   * is necessary to determine if the port is actually available.  (This check presumes
   * firewall rules aren't responsible for dropping the connection request -- not much we
   * can do about that.)
   * @param port the port to test
   * @return {@code true} if the port is available (free); {@code false} otherwise
   * @see <a href="https://support.microsoft.com/en-us/help/832017/service-overview-and-network-port-requirements-for-windows">
   *   Service overview and network port requirements for Windows</a>
   */
  private boolean refusesConnect(int port) {
    InetSocketAddress endpoint;
    if (LOCALHOST == null) {
      endpoint = new InetSocketAddress("localhost", port);
    } else {
      endpoint = new InetSocketAddress(LOCALHOST, port);
    }

    boolean isFree;
    Socket sock = new Socket();
    //noinspection TryFinallyCanBeTryWithResources
    try {
      sock.connect(endpoint, 50);
      isFree = false;     // Connected: not free
    } catch (SocketTimeoutException | ConnectException e) {
      isFree = true;      // Connection attempt timed out or failed (hopefully connection refused); **free**
    } catch (Exception e) {
      LOGGER.debug("[refusesConnect] Client connection to port {} failed for unexpected reason", endpoint, e);
      isFree = false;     // Other error; can't assess -- presumed not free
    }  finally {
      try {
        sock.close();
      } catch (IOException ignored) {
      }
    }

    return isFree;
  }

  @SuppressFBWarnings("DE_MIGHT_IGNORE")
  private static void emitInstanceNotification(String use) {
    try {
      ClassLoader classLoader = INSTANCE.getClass().getClassLoader();
      LOGGER.info("PID {}: {} PortManager instance: {}@{} ({}@{})",
          Pid.PID.orElse(-1), use,
          INSTANCE.getClass().getName(),
          toHexString(identityHashCode(INSTANCE)),
          classLoader.getClass().getName(),
          toHexString(identityHashCode(classLoader)));
    } catch (Exception ignored) {
    }
  }

  /**
   * Determines the process identifier of the current process.
   */
  private static final class Pid {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static final OptionalLong PID = getPidInternal();

    private static OptionalLong getPidInternal() {
      Long pid = null;
      try {
        // Use Java 9+ ProcessHandle.current().pid() if available
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
        Method currentMethod = processHandleClass.getMethod("current");
        Method getPidMethod = processHandleClass.getMethod("pid");
        pid = (Long)getPidMethod.invoke(currentMethod.invoke(null));

      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
        // Expected to be of the form "<pid>@<hostname>"
        String jvmProcessName = ManagementFactory.getRuntimeMXBean().getName();
        try {
          pid = Long.parseLong(jvmProcessName.substring(0, jvmProcessName.indexOf('@')));

        } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
        }
      }
      return (pid == null ? OptionalLong.empty() : OptionalLong.of(pid));
    }
  }

  /**
   * {@code Reference} implementation used to track no longer used reserved ports.
   */
  private static class AllocatedPort extends WeakReference<PortRef> {
    private final int port;
    private final AtomicReference<Runnable> closer;
    private AllocatedPort(PortRef referent, ReferenceQueue<? super PortRef> q) {
      super(referent, q);
      this.port = referent.port();
      this.closer = referent.closers();
    }

    private void release() {
      try {
        Optional.ofNullable(this.closer.get()).ifPresent(Runnable::run);
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Represents a reserved TCP port.  When use of the port is no longer needed, this
   * reference should be closed to release the reservation.
   */
  public static class PortRef implements AutoCloseable {
    private final int port;
    private final AtomicReference<Runnable> closers = new AtomicReference<>(() -> {});
    private final AtomicBoolean closed = new AtomicBoolean();

    private PortRef(int port) {
      this.port = port;
    }

    /**
     * Prepends a close action to this {@code PortRef}.  Actions are executed
     * in reverse order of their addition.
     * @param action the action to take to close this {@code PortRef} instance
     */
    void onClose(Runnable action) {
      closers.accumulateAndGet(action, PortManager::combine);
    }

    /**
     * Gets the "closers" {@code AtomicReference} for use during weak-reference release.
     * @return the {@code AtomicReference} instance to call for closing this {@code PortRef}
     */
    private AtomicReference<Runnable> closers() {
      return closers;
    }

    /**
     * Gets the reserved port number.
     * @return the port number
     */
    public int port() {
      return port;
    }

    /**
     * Returns whether or not this {@code PortRef} is closed.
     * @return {@code true} if this {@code PortRef} has been closed; {@code false} otherwise
     */
    public boolean isClosed() {
      return closed.get();
    }

    /**
     * Closes this {@code PortRef}.
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        Optional.ofNullable(closers.getAndSet(null)).ifPresent(Runnable::run);
      }
    }
  }

  /**
   * Combines {@code Runnable} instances to run in reverse sequence.
   * @param a the second {@code Runnable} to run
   * @param b the first {@code Runnable} to run
   * @return a new {@code Runnable} running {@code b} then {@code a}
   */
  private static Runnable combine(Runnable a, Runnable b) {
    requireNonNull(a, "a");
    requireNonNull(b, "b");
    return () -> {
      try {
        b.run();
      } catch (Throwable t1) {
        try {
          a.run();
        } catch (Throwable t2) {
          t1.addSuppressed(t2);
        }
        throw t1;
      }
      a.run();
    };
  }

  /**
   * Enumeration of search for a {@code BitSet}.
   */
  private enum BitSearch {
    /**
     * Search in an ascending order of bit index.
     */
    ASCENDING {
      @Override
      IntUnaryOperator nextFree(BitSet bitSet) {
        return fromIndex -> {
          int nextClearBit = bitSet.nextClearBit(fromIndex);
          return (nextClearBit > MAXIMUM_PORT_NUMBER ? -1 : nextClearBit);
        };
      }

      @Override
      int successor(int value) {
        // nextFree performs bounds checking
        return value + 1;
      }

      @Override
      BitSearch reverse() {
        return DESCENDING;
      }
    },

    /**
     * Search in a descending order of bit index.
     */
    DESCENDING {
      @Override
      IntUnaryOperator nextFree(BitSet bitSet) {
        return bitSet::previousClearBit;
      }

      @Override
      int successor(int value) {
        // nextFree handles a -1
        return value - 1;
      }

      @Override
      BitSearch reverse() {
        return ASCENDING;
      }
    };

    /**
     * Gets the function used to search the specified {@code BitSet} for the next clear (free) bit.
     * @param bitSet the {@code BitSet} to search
     * @return an {@code IntUnaryOperator} accepting the starting index for the bit search and
     *      returning the index of the next clear bit
     * @see BitSet#nextClearBit(int)
     * @see BitSet#previousClearBit(int)
     */
    abstract IntUnaryOperator nextFree(BitSet bitSet);

    /**
     * Advances the value to the next value appropriate for the direction.
     */
    abstract int successor(int value);

    /**
     * Gets the {@code BitSearch} value that searches in the opposite direction.
     * @return the "opposite" {@code BitSearch} instance
     */
    abstract BitSearch reverse();
  }
}
