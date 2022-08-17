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

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class NetStatTest {

  @Test
  public void testInfo() throws IOException {
    // This test requires a properly functioning 'lsof ...' on Linux
    assumeTrue("'lsof -iTCP not available", TestSupport.lsofWorks());

    try (PortManager.PortRef portRef = PortManager.getInstance().reservePort()) {
      try (ServerSocket openSocket = new ServerSocket(portRef.port())) {
        List<NetStat.BusyPort> busyPorts = NetStat.info();
        assertTrue(busyPorts.stream().mapToInt(p -> p.localEndpoint().getPort()).anyMatch(p -> p == openSocket.getLocalPort()));
      }
    }
  }

  @Test
  public void testInfoPort() throws IOException {
    // This test requires a properly functioning 'lsof ...' on Linux
    assumeTrue("'lsof -iTCP not available", TestSupport.lsofWorks());

    try (PortManager.PortRef portRef = PortManager.getInstance().reservePort()) {
      try (ServerSocket openSocket = new ServerSocket(portRef.port())) {
        List<NetStat.BusyPort> busyPorts = NetStat.info(openSocket.getLocalPort());
        assertTrue(busyPorts.stream().mapToInt(p -> p.localEndpoint().getPort()).anyMatch(p -> p == openSocket.getLocalPort()));
      }
    }
  }
}