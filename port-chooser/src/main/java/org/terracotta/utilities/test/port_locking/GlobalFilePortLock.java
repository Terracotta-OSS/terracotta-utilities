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
package org.terracotta.utilities.test.port_locking;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.FileLock;

public class GlobalFilePortLock implements PortLock {
  private final int port;
  private final FileLock lock;

  GlobalFilePortLock(int port, FileLock lock) {
    this.port = port;
    this.lock = lock;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public void close() {
    PortLockingException closeError = new PortLockingException("Failed to unlock during close");
    Channel channel = lock.acquiredBy();
    try {
      lock.release();
    } catch (IOException e) {
      closeError.addSuppressed(e);
    } finally {
      try {
        channel.close();
      } catch (IOException e) {
        closeError.addSuppressed(e);
      }
    }

    if (closeError.getSuppressed().length > 0) {
      throw closeError;
    }
  }
}