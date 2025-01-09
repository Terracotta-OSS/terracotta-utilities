/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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
import org.terracotta.utilities.test.io.CommonFiles;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static java.util.Objects.requireNonNull;

/**
 * Supplies a system-level locking mechanism supporting port management.
 * An instance of this class may be used to coordinate port reservations among
 * multiple JVM instances in a single system.
 */
class SystemLevelLocker {
  private static final Logger LOGGER = LoggerFactory.getLogger(SystemLevelLocker.class);
  private static final Path RELATIVE_LOCK_FILE_PATH =
      Paths.get(SystemLevelLocker.class.getPackage().getName(), "portLockFile");

  private final Path lockFilePath;

  private FileChannel openChannel;
  private int outstandingLocks;

  /**
   * Creates a new instance of {@code SystemLevelLocker}.  This constructor
   * creates or gains access to the common file used for port locking.
   * <p>
   * The common locking file is <b>not</b> deleted when a
   * {@code SystemLevelLocker} instance is discarded.  The common locking file
   * persists across uses and is re-used if available.
   *
   * @throws IllegalStateException if the common locking file cannot be
   *      created or is otherwise not accessible
   */
  SystemLevelLocker() {
    try {
      /*
       * Create or reuse the lock file in a system-dependent, world-writable location.
       * The permissions on the file are updated, if possible, at creation time
       * and when reused so all users have the same permissions as the creator/owner.
       */
      lockFilePath = CommonFiles.createCommonAppFile(RELATIVE_LOCK_FILE_PATH);

      /*
       * If the file returned is an existing file, it _should_ be readable and writable,
       * but we need to check ...
       */
      if (!Files.isReadable(lockFilePath)) {
        throw new IllegalStateException("File is not readable: " + lockFilePath);
      }
      if (!Files.isWritable(lockFilePath)) {
        throw new IllegalStateException("File is not writable: " + lockFilePath);
      }

      LOGGER.info("Using system-level port lock file: {}", lockFilePath);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create " + RELATIVE_LOCK_FILE_PATH + " in system common directory", e);
    }
  }

  /**
   * Obtains a system-level lock against the lock file for the identified port.
   * @param portRef the {@code PortRef} identifying the port.
   * @return {@code true} if the system-level lock was obtained; {@code false} otherwise
   * @throws IllegalStateException if the lock cannot be obtained because of an {@code IOException}
   */
  synchronized boolean lock(PortManager.PortRef portRef) {
    int port = requireNonNull(portRef, "portRef").port();
    try {
      if (openChannel == null) {
        LOGGER.info("Opening system-level port lock file {}", lockFilePath);
        openChannel = FileChannel.open(lockFilePath, StandardOpenOption.WRITE, StandardOpenOption.READ);
      }

      FileLock fileLock = openChannel.tryLock(port, 1, false);
      if (fileLock != null) {
        outstandingLocks++;
        portRef.onClose((p, o) -> {
          assert p == port;
          release(p, fileLock);
        });
        LOGGER.info("Port {} reserved (system-level)", port);
        return true;
      } else {
        // Locked by another process
        LOGGER.trace("Port {} locked by another process", port);
        return false;
      }

    } catch (IOException e) {
      throw new IllegalStateException(String.format("Failed to obtain lock against \"%s\" for port %d",
          lockFilePath, port), e);

    } finally {
      closeChannelIfUnused();
    }
  }

  /**
   * Release the system-level lock for the specified {@code PortRef} instance.
   * @param port the {@code PortRef} instance to unlock
   * @param lock the existing {@code FileLock} instance
   */
  private synchronized void release(int port, FileLock lock) {
    requireNonNull(lock, "lock");

    try {
      lock.release();
      LOGGER.info("Port {} released (system-level)", port);
    } catch (ClosedChannelException ignored) {
      LOGGER.info("Port {} already released (system-level): channel closed", port);
    } catch (IOException e) {
      LOGGER.warn(String.format("Error while releasing lock against \"%s\" for port %s",
          lockFilePath, port), e);
    } finally {
      outstandingLocks--;
      closeChannelIfUnused();
    }
  }

  /**
   * Closes {@link #openChannel} if there are no outstanding locks.
   */
  private void closeChannelIfUnused() {
    if (openChannel != null && outstandingLocks == 0) {
      try {
        LOGGER.info("Closing system-level port lock file {}", lockFilePath);
        openChannel.close();
      } catch (IOException e) {
        LOGGER.warn(String.format("Failed to close \"%s\"", lockFilePath), e);
      } finally {
        openChannel = null;
      }
    }
  }
}
