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

import ch.qos.logback.classic.Level;

import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.test.logging.ConnectedListAppender;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.terracotta.utilities.test.matchers.Eventually.within;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Provides tests over {@link PortManager}.
 */
public class PortManagerTest {

  private static final EphemeralPorts.Range EPHEMERAL_RANGE = EphemeralPorts.getRange();
  private final PortManager portManager = PortManager.getInstance();

  @Test
  public void testSingleton() {
    assertThat(PortManager.getInstance(), is(portManager));
  }

  @Test
  public void testSpecificPortOutOfRange() {
    assertThat(() -> portManager.reserve(-1), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reserve(Integer.MAX_VALUE), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reserve(0), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reserve(EPHEMERAL_RANGE.getLower()), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reserve(EPHEMERAL_RANGE.getUpper()), threw(instanceOf(IllegalArgumentException.class)));
  }

  @Test
  public void testCountOutOfRange() {
    assertThat(() -> portManager.reservePorts(65536), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reservePorts(0), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.reservePorts(-1), threw(instanceOf(IllegalArgumentException.class)));
  }

  @Test(timeout = 50000)
  public void testReserveSinglePort() {
    PortManager.PortRef portRef = portManager.reservePort();
    assertNotNull(portRef);
    int reservedPort = portRef.port();
    try {
      assertThat(portManager.reserve(reservedPort), is(Optional.empty()));
    } finally {
      portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
    }

    // Attempt to directly allocate the just released port
    Optional<PortManager.PortRef> reservation = portManager.reserve(reservedPort);
    assertTrue(reservation.isPresent());
    portRef = reservation.get();
    try {
      assertThat(portRef.port(), is(reservedPort));
    } finally {
      portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
    }
  }

  @Test(timeout = 50000)
  public void testMultipleClose() {
    PortManager.PortRef portRef = portManager.reservePort();
    portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
    portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
  }

  @SuppressWarnings({ "UnusedAssignment", "resource" })
  @Test(timeout = 200000)
  public void testDereferencedPortIsReleased() {
    PortManager.PortRef portRef = portManager.reservePort();
    assertNotNull(portRef);
    int port = portRef.port();

    assertThat(portManager.reserve(port), is(Optional.empty()));

    portRef = null;   // Make eligible for garbage collection

    PortManager.PortRef[] newRef = new PortManager.PortRef[1];
    try {
      assertThat(() -> {
        System.gc();      // This inducement is necessary
        Optional<PortManager.PortRef> newReservation = portManager.reserve(port);
        newRef[0] = newReservation.orElse(null);
        return newRef[0];
      }, within(Duration.ofSeconds(10)).matches(is(notNullValue())));
    } finally {
      if (newRef[0] != null) {
        newRef[0].close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
      }
    }
  }

  @Test(timeout = 200000)
  public void testReserveCount() {
    for (int count : Arrays.asList(1, 2, 4, 8)) {
      List<PortManager.PortRef> portList = portManager.reservePorts(count);
      assertThat(portList, hasSize(count));
      portList.forEach(ref -> ref.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK)));
    }
  }

  /**
   * This test artificially constrains the ports available for reservation by
   * altering the {@code portMap} using reflection.
   */
  @Test(timeout = 50000)
  public void testConstrainedEnvironment() throws Exception {
    Field portMapField = PortManager.class.getDeclaredField("portMap");
    portMapField.setAccessible(true);

    BitSet portMap = (BitSet)portMapField.get(portManager);
    BitSet portMapCopy = (BitSet)portMap.clone();
    int freeBit;
    if ((freeBit = portMap.previousClearBit(32768)) == -1) {
      if ((freeBit = portMap.nextClearBit(32768)) > 65535) {
        throw new AssertionError("No free bit found");
      }
    }

    PortManager.PortRef portRef = null;
    try {
      portMap.set(0, 65536);
      portMap.clear(freeBit);

      portRef = portManager.reservePort();
      assertThat(portRef.port(), is(freeBit));
      assertThat(portManager::reservePort, threw(instanceOf(IllegalStateException.class)));

    } finally {
      if (portRef != null) {
        portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
      }
      portMap.and(portMapCopy);
    }
  }

  @Test(timeout = 50000)
  @SuppressWarnings("try")
  public void testExistingServerPort() throws Exception {
    PortManager.PortRef portRef = portManager.reservePort();
    int port = portRef.port();
    try (ServerSocket ignored = new ServerSocket(port)) {
      portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
      assertThat(portManager.reserve(port), is(Optional.empty()));
    }
    Optional<PortManager.PortRef> optional = portManager.reserve(port);
    assertTrue(optional.isPresent());
    optional.get().close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
  }

  @Test
  public void testGetPortRefActive() {
    try (PortManager.PortRef portRef = portManager.reservePort()) {
      int port = portRef.port();
      assertThat(portManager.getPortRef(port).orElseThrow(AssertionError::new), is(sameInstance(portRef)));
      assertFalse(portRef.isClosed());
    }
  }

  @Test
  public void testGetPortRefClosed() {
    int port;
    PortManager.PortRef portRef = portManager.reservePort();
    try {
      port = portRef.port();
    } finally {
      portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
    }
    assertThat(portManager.getPortRef(port), is(Optional.empty()));
    assertTrue(portRef.isClosed());
  }

  @Test
  public void testGetPortRefReclaimed() throws Exception {
    PortManager.PortRef portRef = portManager.reservePort();
    try {
      int port = portRef.port();

      WeakReference<PortManager.PortRef> reference = new WeakReference<>(portRef);
      portRef = null;         // Make eligible for garbage collection

      while (reference.get() != null) {
        System.gc();
        TimeUnit.MILLISECONDS.sleep(1000);
      }

      assertThat(portManager.getPortRef(port), is(Optional.empty()));

    } finally {
      if (portRef != null) {
        portRef.close();
      }
    }
  }

  @SuppressWarnings("TryFinallyCanBeTryWithResources")
  @Test
  public void testGetPortRefWhenClosing() {
    PortManager.PortRef portRef = portManager.reservePort();
    try {
      int port = portRef.port();

      /*
       * This relies on the first-in, last-out nature of the onClose actions --
       * the port is first marked closed then the onClose actions are executed.
       */
      AtomicReference<Optional<PortManager.PortRef>> altPortRef = new AtomicReference<>();
      portRef.onClose((p, o) -> altPortRef.set(portManager.getPortRef(p)));

      assertThat(portManager.getPortRef(port).orElseThrow(AssertionError::new), is(sameInstance(portRef)));

      assertFalse(portRef.isClosed());
      portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK));
      assertTrue(portRef.isClosed());

      assertThat(altPortRef.get(), is(Optional.empty()));

    } finally {
      portRef.close();
    }
  }

  @Test
  public void testIsReservablePort() {
    assertThat(() -> portManager.isReservablePort(65536), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> portManager.isReservablePort(-1), threw(instanceOf(IllegalArgumentException.class)));

    BitSet restrictedPorts = new BitSet();
    restrictedPorts.set(0, 1025);   // System ports aren't reservable

    EphemeralPorts.Range range = EphemeralPorts.getRange();
    restrictedPorts.set(range.getLower(), 1 + range.getUpper());      // Ephemeral ports aren't reservable

    assertFalse(portManager.isReservablePort(0));
    assertFalse(portManager.isReservablePort(22));
    assertFalse(portManager.isReservablePort(range.getLower()));
    assertFalse(portManager.isReservablePort((range.getUpper() + range.getLower()) / 2));
    assertFalse(portManager.isReservablePort(range.getUpper()));

    int reservablePort = restrictedPorts.nextClearBit(1024);
    try {
      Optional<PortManager.PortRef> portRef = portManager.reserve(reservablePort);
      portRef.ifPresent(ref -> ref.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK)));
    } catch (IllegalArgumentException e) {
      throw new AssertionError("portManager.reservePort(" + reservablePort + ") failed", e);
    }

    reservablePort = restrictedPorts.previousClearBit(65535);
    try {
      Optional<PortManager.PortRef> portRef = portManager.reserve(reservablePort);
      portRef.ifPresent(ref -> ref.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK)));
    } catch (IllegalArgumentException e) {
      throw new AssertionError("portManager.reservePort(" + reservablePort + ") failed", e);
    }

    try (PortManager.PortRef portRef = portManager.reservePort()) {
      assertTrue(portManager.isReservablePort(portRef.port()));
    }
  }

  @SuppressWarnings("try")
  @Test
  public void testReleaseCheckEnabled() throws IOException {
    // This test requires a properly functioning 'lsof ...' on Linux
    assumeTrue("'lsof -iTCP not available", TestSupport.lsofWorks());

    // This test MUST be run when PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE is false or not specified
    assertFalse(PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE + " environment variable must be false or not specified",
        Boolean.parseBoolean(System.getenv(PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE)));

    try (ConnectedListAppender appender = ConnectedListAppender.newInstance(LoggerFactory.getLogger(PortManager.class), "WARN")) {
      PortManager.PortRef portRef = portManager.reservePort();
      int port = portRef.port();
      try (ServerSocket ignored = new ServerSocket(port)) {
        String portReleaseCheck = System.clearProperty(PortManager.DISABLE_PORT_RELEASE_CHECK_PROPERTY);
        try {
          portRef.close();
        } finally {
          if (portReleaseCheck != null) {
            System.setProperty(PortManager.DISABLE_PORT_RELEASE_CHECK_PROPERTY, portReleaseCheck);
          }
        }
        assertThat(appender.events(), hasItem(allOf(
            hasProperty("level", equalTo(Level.ERROR)),
            hasProperty("loggerName", equalTo(PortManager.class.getName())),
            hasProperty("formattedMessage", is(stringContainsInOrder("Port " + port, "state='LISTEN'")))
        )));
      }
    }
  }

  @SuppressWarnings("try")
  @Test
  public void testReleaseCheckDisabled() throws IOException {
    // This test MUST be run when PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE is false or not specified
    assertFalse(PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE + " environment variable must be false or not specified",
        Boolean.parseBoolean(System.getenv(PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE)));

    try (ConnectedListAppender appender = ConnectedListAppender.newInstance(LoggerFactory.getLogger(PortManager.class), "WARN")) {
      PortManager.PortRef portRef = portManager.reservePort();
      int port = portRef.port();
      try (ServerSocket ignored = new ServerSocket(port)) {
        String portReleaseCheck = System.setProperty(PortManager.DISABLE_PORT_RELEASE_CHECK_PROPERTY, "true");
        try {
          portRef.close();
        } finally {
          if (portReleaseCheck == null) {
            System.clearProperty(PortManager.DISABLE_PORT_RELEASE_CHECK_PROPERTY);
          } else if (!Boolean.parseBoolean(portReleaseCheck)) {
            System.setProperty(PortManager.DISABLE_PORT_RELEASE_CHECK_PROPERTY, portReleaseCheck);
          }
        }
        assertThat(appender.events(), not(hasItem(allOf(
            hasProperty("level", equalTo(Level.ERROR)),
            hasProperty("loggerName", equalTo(PortManager.class.getName())),
            hasProperty("formattedMessage", is(stringContainsInOrder("Port " + port, "state='LISTEN'")))
        ))));
      }
    }
  }

  @SuppressWarnings("try")
  @Test
  public void testReleaseCheckDisabledEnvironment() throws IOException {
    assumeTrue("Skipped unless " + PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE + " environment variable is true",
        Boolean.parseBoolean(System.getenv(PortManager.DISABLE_PORT_RELEASE_CHECK_ENV_VARIABLE)));

    try (ConnectedListAppender appender = ConnectedListAppender.newInstance(LoggerFactory.getLogger(PortManager.class), "WARN")) {
      PortManager.PortRef portRef = portManager.reservePort();
      int port = portRef.port();
      try (ServerSocket ignored = new ServerSocket(port)) {
        portRef.close();
        assertThat(appender.events(), not(hasItem(allOf(
            hasProperty("level", equalTo(Level.ERROR)),
            hasProperty("loggerName", equalTo(PortManager.class.getName())),
            hasProperty("formattedMessage", is(stringContainsInOrder("Port " + port, "state='LISTEN'")))
        ))));
      }
    }
  }

  /**
   * Demonstrates how {@code PortManager} might be used to obtain a set of sequential port
   * reservations.  The use of non-sequential reservations using {@link PortManager#reservePorts(int)}
   * is strongly recommended.
   */
  @Ignore("Less of a test and more of a demonstration of how one might allocate a number of sequential ports")
  @Test
  public void testSequentialAllocation() {
    int attemptLimit = 5;
    int targetPortCount = 8;
    List<PortManager.PortRef> ports = new ArrayList<>();
    int attempts = 1;
    boolean complete = false;
    while (!complete && attempts <= attemptLimit) {
      try {
        synchronized (portManager) {
          PortManager.PortRef firstPort = portManager.reservePort();
          ports.add(firstPort);
          for (int port = firstPort.port() + 1, i = 0; i < targetPortCount - 1; port++, i++) {
            ports.add(portManager.reserve(port).orElseThrow(IllegalArgumentException::new));
          }
        }
        complete = true;

      } catch (IllegalArgumentException e) {
        // try again with another first port allocation
      } finally {
        if (!complete) {
          attempts++;
          ports.forEach(portRef -> portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK)));
          ports.clear();
        }
      }
    }

    try {
      System.out.format("Attempts=%d, Reserved ports=%s%n", attempts,
          Arrays.toString(ports.stream().mapToInt(PortManager.PortRef::port).toArray()));
      assertThat(ports, hasSize(targetPortCount));
      assertThat(attempts, is(lessThan(attemptLimit)));
    } finally {
      ports.forEach(portRef -> portRef.close(EnumSet.of(PortManager.PortRef.CloseOption.NO_RELEASE_CHECK)));
      ports.clear();
    }
  }
}