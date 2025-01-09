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
package org.terracotta.utilities.io;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/**
 * Support methods for the {@link Files} implementation.
 */
class FilesSupport {

  private static final Logger LOGGER = LoggerFactory.getLogger(FilesSupport.class);
  private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
  private static final Pattern SUBST_PAIR = Pattern.compile("(.*): => (.*)");

  /**
   * Identifies the {@link FileSystemException#getReason()} messages for which file operations that
   * warrant a retry.
   * <p>
   * For example, a likely cause for an AccessDeniedException from a {@link java.nio.file.Files#move},
   * under Windows anyway, is that some other system process, like indexing or anti-virus, has the file
   * momentarily open.  A retry of a rename (or delete) is appropriate.  Failures reflected by other
   * {@link FileSystemException} instances can also warrant retry.
   * <p>
   * Ideally, the JDK would reflect such "errors of interference" as specific exceptions
   * that can be easily determined and a retry attempted.
   * Unfortunately, the JDK can manifest this interference in many ways:
   * <ol>
   *   <li>{@code AccessDeniedException} reflects a Windows errorCode of 0x05 (ERROR_ACCESS_DENIED) or
   *       Unix error code of EACCES.  This error could be persistent or temporary.</li>
   *   <li>{@code FileSystemException} with a _reason_ reflecting an error code like:
   *      <ul>
   *        <li>Windows ERROR_SHARING_VIOLATION (0x20)</li>
   *        <li>Windows ERROR_LOCK_VIOLATION (0x21)</li>
   *        <li>Unix EAGAIN</li>
   *      </ul>
   *   </li>
   * </ol>
   * Compounding the programmatic handling of these errors, a {@code FileSystemException} does not include
   * the errorCode -- the reason text is obtained from the Windows {@code FormatMessageW} function
   * specifying 0x0 as the 'dwLanguageId' parameter or the Unix {@code strerror} function.  This means that
   * the reason text is, potentially, in the local language and not suitable for reliable parsing.
   *
   * @return the set of reason messages determined to be retryable
   */
  static Set<String> getRetryReasons() {
    Set<String> reasons = new LinkedHashSet<>();
    for (String reason : calculateReasons()) {
      if (reason != null) {
        reasons.add(reason);
      }
    }
    LOGGER.trace("Retry reason = {}", reasons);
    return Collections.unmodifiableSet(reasons);
  }

  /**
   * Determine the path type from {@link BasicFileAttributes}.
   * @param attributes the {@code BasicFileAttributes} to interpret
   * @return the list of path types; empty if the type cannot be determined
   */
  static List<String> pathType(BasicFileAttributes attributes) {
    List<String> attrs = new ArrayList<>();
    if (attributes.isDirectory()) attrs.add("dir");
    if (attributes.isRegularFile()) attrs.add("file");
    if (attributes.isSymbolicLink()) attrs.add("link");
    if (attributes.isOther()) attrs.add("other");
    return attrs;
  }

  /**
   * If a Windows platform, gets the current SUBST assignments, if any.  Because the SUBST
   * list can change without notice, this assignment map is not cached.  A failure in obtaining
   * the assignment list results in the return of an empty map.
   * @return the current SUBST assignments
   */
  static Map<Path, Path> getSubsts() {
    if (!IS_WINDOWS) {
      return Collections.emptyMap();
    }

    try {
      Map<Path, Path> substs = new LinkedHashMap<>();
      for (String subst : Shell.execute(Shell.Encoding.CHARSET, "subst")) {
        Matcher matcher = SUBST_PAIR.matcher(subst);
        if (matcher.matches()) {
          Path drive = Paths.get(matcher.group(1));
          String mappedPathName = matcher.group(2);
          Path mappedPath;
          try {
            mappedPath = Paths.get(mappedPathName);
          } catch (InvalidPathException e) {
            // Non-mappable characters are presented as '?' -- a character illegal in a Windows file path
            LOGGER.warn("Cannot determine mapping for drive {}: \"{}\" contains character not mapped in charset {}",
                drive, mappedPathName, Shell.Encoding.CHARSET, e);
            continue;
          }
          if (!java.nio.file.Files.exists(mappedPath)) {
            LOGGER.warn("Cannot determine mapping for drive {}: \"{}\" does not exist", drive, mappedPathName);
            continue;
          }
          substs.put(drive, mappedPath);
        }
      }
      return Collections.unmodifiableMap(substs);

    } catch (Exception e) {
      LOGGER.info("Failed to determine drive substitutions", e);
      return Collections.emptyMap();
    }
  }


  /**
   * Dynamically determine reasons for file operation retry.
   * @return a {@code Set} of {@link FileSystemException#getReason()} values warranting retry
   */
  private static Set<String> calculateReasons() {
    try {
      return calculateReasons(java.nio.file.Files.createTempDirectory("top"));
    } catch (IOException e) {
      LOGGER.trace("Unexpected I/O error constructing failure reasons", e);
      return Collections.emptySet();
    }
  }

  /**
   * Testing seam.
   *
   * @see #calculateReasons()
   */
  //Better a dead store than a missed update to a file path
  @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
  static Set<String> calculateReasons(Path top) throws IOException {
    /*
     * First, set up a directory tree to use for test file operations.
     *
     *                           top
     *                          /
     *                         dir
     *                        /   \
     *                     file1  file2
     */
    try {
      Path dir = java.nio.file.Files.createDirectory(top.resolve("dir"));
      Path file1 = java.nio.file.Files.createFile(dir.resolve("file1"));
      java.nio.file.Files.write(file1, Collections.singleton("file1"), StandardCharsets.UTF_8);
      Path file2 = java.nio.file.Files.createFile(dir.resolve("file2"));
      java.nio.file.Files.write(file1, Collections.singleton("file2"), StandardCharsets.UTF_8);

      Set<String> reasons = new LinkedHashSet<>();

      /*
       * File is open for read versus move/rename.
       */
      try (PathHolder holder = new PathHolder(file1, false)) {
        holder.start();
        file1 = java.nio.file.Files.move(file1, file1.resolveSibling("file1_renamed1"), StandardCopyOption.ATOMIC_MOVE);
      } catch (FileSystemException e) {
        reasons.add(e.getReason());
        LOGGER.trace("Observed for file/file OPEN rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
      } catch (Exception e) {
        // Nothing we can do about this ...
        LOGGER.trace("Unexpected IOException renaming {}", file1, e);
      }

      /*
       * File is open for read versus directory move/rename.
       */
      try (PathHolder holder = new PathHolder(file1, false)) {
        holder.start();
        dir = java.nio.file.Files.move(dir, dir.resolveSibling("dir_renamed1"), StandardCopyOption.ATOMIC_MOVE);
        file1 = dir.resolve(file1.getFileName());
        file2 = dir.resolve(file2.getFileName());
      } catch (FileSystemException e) {
        reasons.add(e.getReason());
        LOGGER.trace("Observed for file/dir OPEN rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
      } catch (Exception e) {
        // Nothing we can do about this ...
        LOGGER.trace("Unexpected IOException renaming {}", dir, e);
      }

      /*
       * File locked versus move/rename.
       */
      try (PathHolder holder = new PathHolder(file1, true)) {
        holder.start();
        file1 = java.nio.file.Files.move(file1, file1.resolveSibling("file1_renamed2"), StandardCopyOption.ATOMIC_MOVE);
      } catch (FileSystemException e) {
        reasons.add(e.getReason());
        LOGGER.trace("Observed for file/file LOCKED rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
      } catch (Exception e) {
        // Nothing we can do about this ...
        LOGGER.trace("Unexpected IOException renaming {}", file1, e);
      }

      /*
       * File locked versus directory move/rename.
       */
      try (PathHolder holder = new PathHolder(file1, true)) {
        holder.start();
        dir = java.nio.file.Files.move(dir, dir.resolveSibling("dir_renamed2"), StandardCopyOption.ATOMIC_MOVE);
        file1 = dir.resolve(file1.getFileName());
        file2 = dir.resolve(file2.getFileName());
      } catch (FileSystemException e) {
        reasons.add(e.getReason());
        LOGGER.trace("Observed for file/dir LOCKED rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
      } catch (Exception e) {
        // Nothing we can do about this ...
        LOGGER.trace("Unexpected IOException renaming {}", dir, e);
      }

      /*
       * Directory is open for read versus file move/rename.
       */
      try (PathHolder holder = new PathHolder(dir, false)) {
        holder.start();
        file1 = java.nio.file.Files.move(file1, file1.resolveSibling("file1_renamed3"), StandardCopyOption.ATOMIC_MOVE);
        LOGGER.trace("Succeeded dir/file rename");
      } catch (FileSystemException e) {
        reasons.add(e.getReason());
        LOGGER.trace("Observed for dir/file rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
      } catch (IOException e) {
        // Nothing we can do about this ...
        LOGGER.trace("Unexpected IOException renaming {}", file1, e);
      }
      return reasons;
    } finally {
      try {
        java.nio.file.Files.walkFileTree(top, new SimpleFileVisitor<Path>() {

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            delete(dir);
            return FileVisitResult.CONTINUE;
          }

          private void delete(Path path) {
            try {
              java.nio.file.Files.delete(path);
            } catch (IOException e) {
              LOGGER.trace("Cannot delete {}", path, e);
              path.toFile().deleteOnExit();
            }
          }
        });
      } catch (IOException f) {
        LOGGER.trace("Cannot delete {}", top, f);
      }
    }
  }

  /**
   * Support class to open or lock a file for file operation impact assessment.
   * <p>
   * Although this class has "support" for opening a directory, there appears to be no reliable
   * means in Java of opening a directory and observing the impact on other file operations.
   */
  private static class PathHolder implements AutoCloseable {
    // Needs a local LOGGER to prevent issues with initialization of Files class.
    private static final Logger LOGGER = LoggerFactory.getLogger(PathHolder.class);
    private final Thread thread;
    private final Phaser barrier = new Phaser(2);

    private final AtomicBoolean started = new AtomicBoolean(false);

    private PathHolder(Path path, boolean lock) throws IOException {
      requireNonNull(path, "path");

      BasicFileAttributes attr = java.nio.file.Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
      Runnable holder;
      if (attr.isRegularFile()) {
        if (lock) {
          holder = () -> lockFile(path);
        } else {
          holder = () -> holdFile(path);
        }
      } else if (attr.isDirectory()) {
        holder = () -> holdDirectory(path);
      } else {
        throw new AssertionError("Cannot handle path of type " + pathType(attr) + " - " + path);
      }

      this.thread = new Thread(holder, "Files$PathHolder - " + path);
    }

    /**
     * Holds open a file for read.
     * @param file the file path to hold open
     */
    private void holdFile(Path file) {
      LOGGER.trace("Hold on \"{}\" beginning", file);
      try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "r")) {
        randomAccessFile.read();
        barrier.arriveAndAwaitAdvance();
        // Hold path open so others can observe the state
        barrier.arriveAndAwaitAdvance();
      } catch (Exception e) {
        LOGGER.warn("Error attempting to hold \"{}\"", file, e);
      } finally {
        barrier.arriveAndDeregister();
      }
      LOGGER.trace("Hold ended on \"{}\"", file);
    }

    /**
     * Holds a lock on a file.
     * @param file the file path to lock
     */
    @SuppressWarnings("try")
    private void lockFile(Path file) {
      LOGGER.trace("Lock on \"{}\" beginning", file);
      try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "rw")) {
        FileChannel channel = randomAccessFile.getChannel();
        try (FileLock ignored = channel.lock()) {
          barrier.arriveAndAwaitAdvance();
          // Hold path open so others can observe the state
          barrier.arriveAndAwaitAdvance();
        }
      } catch (Exception e) {
        LOGGER.warn("Error attempting to lock \"{}\"", file, e);
      } finally {
        barrier.arriveAndDeregister();
      }
      LOGGER.trace("Lock ended on \"{}\"", file);
    }

    @SuppressFBWarnings({"DLS_DEAD_LOCAL_STORE", "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE"})
    private void holdDirectory(Path dir) {
      LOGGER.trace("Hold on \"{}\" beginning", dir);
      try (DirectoryStream<Path> directoryStream = java.nio.file.Files.newDirectoryStream(dir)) {
        for (Path ignored : directoryStream) {
          barrier.arriveAndAwaitAdvance();
          // Hold path open so others can observe the state
          barrier.arriveAndAwaitAdvance();
          break;
        }
      } catch (Exception e) {
        LOGGER.warn("Error attempting to hold \"{}\"", dir, e);
      } finally {
        barrier.arriveAndDeregister();
      }
      LOGGER.trace("Hold ended on \"{}\"", dir);
    }

    /**
     * Open or lock the file and return. The file remains opened or locked until
     * {@link #close()} is called.
     */
    public void start() {
      thread.setDaemon(true);
      thread.start();
      started.set(true);
      barrier.arriveAndAwaitAdvance();
      // path is now 'open' ...
    }

    /**
     * Closes and unlocks the file.
     */
    @Override
    public void close() {
      if (started.compareAndSet(true, false)) {
        barrier.arriveAndDeregister();
        try {
          thread.join();
        } catch (InterruptedException e) {
          // Ignored
        }
      }
    }
  }
}
