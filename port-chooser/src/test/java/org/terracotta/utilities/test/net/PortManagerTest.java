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

import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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

  @Test(timeout = 5000)
  public void testReserveSinglePort() {
    PortManager.PortRef portRef = portManager.reservePort();
    assertNotNull(portRef);
    int reservedPort = portRef.port();
    try {
      assertThat(portManager.reserve(reservedPort), is(Optional.empty()));
    } finally {
      portRef.close();
    }

    // Attempt to directly allocate the just released port
    Optional<PortManager.PortRef> reservation = portManager.reserve(reservedPort);
    assertTrue(reservation.isPresent());
    portRef = reservation.get();
    try {
      assertThat(portRef.port(), is(reservedPort));
    } finally {
      portRef.close();
    }
  }

  @Test(timeout = 5000)
  public void testMultipleClose() {
    PortManager.PortRef portRef = portManager.reservePort();
    portRef.close();
    portRef.close();
  }

  @Test(timeout = 20000)
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
        newRef[0].close();
      }
    }
  }

  @Test(timeout = 20000)
  public void testReserveCount() {
    for (int count : Arrays.asList(1, 2, 4, 8)) {
      List<PortManager.PortRef> portList = portManager.reservePorts(count);
      assertThat(portList, hasSize(count));
      portList.forEach(PortManager.PortRef::close);
    }
  }

  /**
   * This test artificially constrains the ports available for reservation by
   * altering the {@code portMap} using reflection.
   */
  @Test(timeout = 5000)
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
        portRef.close();
      }
      portMap.and(portMapCopy);
    }
  }

  @Test(timeout = 5000)
  @SuppressWarnings("try")
  public void testExistingServerPort() throws Exception {
    PortManager.PortRef portRef = portManager.reservePort();
    int port = portRef.port();
    try (ServerSocket ignored = new ServerSocket(port)) {
      portRef.close();
      assertThat(portManager.reserve(port), is(Optional.empty()));
    }
    Optional<PortManager.PortRef> optional = portManager.reserve(port);
    assertTrue(optional.isPresent());
    optional.get().close();
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
          ports.forEach(PortManager.PortRef::close);
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
      ports.forEach(PortManager.PortRef::close);
      ports.clear();
    }
  }
}