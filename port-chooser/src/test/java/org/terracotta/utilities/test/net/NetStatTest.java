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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

public class NetStatTest {

  private final PortManager portManager = PortManager.getInstance();

  private PortManager.PortRef portRef;
  private ServerSocket openSocket;

  @Before
  public void setListener() throws IOException {
    portRef = portManager.reservePort();
    try {
      openSocket = new ServerSocket(portRef.port());
    } catch (IOException e) {
      portRef.close();
      throw e;
    }
  }

  @After
  public void closeListener() throws IOException {
    try {
      if (openSocket != null) {
        openSocket.close();
        openSocket = null;
      }
    } finally {
      if (portRef != null) {
        portRef.close();
        portRef = null;
      }
    }
  }

  @Test
  public void testInfo() {
    List<NetStat.BusyPort> busyPorts = NetStat.info();
    assertThat(busyPorts, is(not(empty())));
    assertTrue(busyPorts.stream().mapToInt(p -> p.localEndpoint().getPort()).anyMatch(p -> p == portRef.port()));
  }
}