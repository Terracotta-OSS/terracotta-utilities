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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.test.io.CommonFiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class GlobalFilePortLocker implements PortLocker {
  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalFilePortLocker.class);

  private final Path portLockFile;

  public GlobalFilePortLocker() {

    Path portLockRelative = Paths.get("terracotta", "tc-port-lock");
    try {
      /*
       * Create or reuse the lock file in a system-dependent, world-writable location.
       * The permissions on the file are updated, if possible, at creation time
       * and when reused so all users have the same permissions as the creator/owner.
       */
      portLockFile = CommonFiles.createCommonAppFile(portLockRelative);

      /*
       * If the file returned is an existing file, it _should_ be readable and writable
       * but we need to check ...
       */
      if (!Files.isReadable(portLockFile)) {
        throw new PortLockingException("File is not readable: " + portLockFile);
      }
      if (!Files.isWritable(portLockFile)) {
        throw new PortLockingException("File is not writable: " + portLockFile);
      }

      LOGGER.info("Using port lock file: {}", portLockFile);
    } catch (IOException e) {
      throw new PortLockingException("Failed to create " + portLockRelative + " in system common directory", e);
    }
  }

  @Override
  public PortLock tryLockPort(int port) {
    // TODO: Open channel once and close only when last port is returned
    try {
      FileChannel channel = FileChannel.open(portLockFile, StandardOpenOption.WRITE, StandardOpenOption.READ);
      FileLock fileLock = channel.tryLock(port, 1, false);

      if (fileLock == null) {
        channel.close();
        return null;
      }

      return new GlobalFilePortLock(port, fileLock);
    } catch (IOException e) {
      throw new PortLockingException("Error while trying to lock port", e);
    }
  }
}
