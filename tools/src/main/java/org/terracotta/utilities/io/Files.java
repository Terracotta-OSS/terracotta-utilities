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
package org.terracotta.utilities.io;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;

import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.LinkPermission;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.util.Objects.requireNonNull;

/**
 * Provides <i>sane</i> file management operations.
 * <p>
 * Under some operating and file systems -- Windows &amp; HPFS, we're looking at you -- file
 * operations such as delete and move/rename can be the victim of background or asynchronous
 * operations.  When, for example, a file is deleted and a directory immediately created in
 * its place, the directory creation operation can fail because deletion under Windows is
 * performed asynchronously -- the delete operation simply marks the file system node as
 * deleted and the actual removal is an asynchronous operation.  When a file is renamed,
 * that rename can fail because the anti-virus or indexing subsystems might have the file
 * temporarily open preventing the rename (or move/delete).
 * <p>
 * Generally speaking, simply retrying the delete or rename is sufficient to get around the
 * problem but there may be other complications -- for example, if the rename/move needs
 * to relocate the file/directory to another file system root a simple retry becomes
 * potentially burdensome.
 * <p>
 * Even though read-only operations, like file copy, should not be affected by background system
 * tasks, a mirror for {@link java.nio.file.Files#copy(Path, Path, CopyOption...)} is defined in
 * this class to localize copy code in the event this assertion proves inaccurate and to provide
 * additional capabilities -- like recursive copying.
 * <p>
 * This class does not define a {@code move} method.
 * Use {@link #relocate(Path, Path, CopyOption...)} relocate} instead.  The {@code relocate}
 * method performs a rename or copy/delete as necessary.  Recovery of a copy/delete failure
 * is left as an exercise for the caller.
 * <p>
 * Methods in this class retry some operations when presented with an {@link AccessDeniedException}
 * or one of another {@link FileSystemException} instances indicating potential short-term
 * interference from other processes.
 *
 * <h3>Caution</h3>
 * Windows, by default, restricts the creation of symbolic links to administrator accounts.
 * The use of the {@link LinkOption#NOFOLLOW_LINKS NOFOLLOW_LINKS} option will may result
 * in a {@code FileSystemException} and the copy will fail.  See
 * <a href="https://stackoverflow.com/a/24353758/1814086">How to create Soft symbolic Link using java.nio.Files</a>
 * for additional detail.
 *
 * @see ExtendedOption
 * @see <a href="https://docs.microsoft.com/en-us/archive/blogs/junfeng/kb-329065-access-denied-error-when-you-make-code-modifications-with-index-services-running">KB 329065 Access Denied Error When You Make Code Modifications with Index Services Running</a>
 * @see <a href="https://docs.microsoft.com/en-us/archive/blogs/junfeng/delete-pend-error_access_denied">DELETE PEND == ERROR_ACCESS_DENIED</a>
 *
 * @author Clifford W. Johnson
 */
public final class Files {
  private static Logger LOGGER = LoggerFactory.getLogger(Files.class);
  private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  private static final Duration OPERATION_REPEAT_DELAY = Duration.ofMillis(100);
  private static final int OPERATION_ATTEMPTS = 10;
  private static final Duration DEFAULT_RENAME_TIME_LIMIT = OPERATION_REPEAT_DELAY.multipliedBy(25L);

  /**
   * Specifies the minimum value for the delay in {@link #delete(Path, Duration)},
   * {@link #deleteTree(Path, Duration)}, and {@link #rename(Path, Path, Duration)}.
   */
  public static final Duration MINIMUM_TIME_LIMIT = DEFAULT_RENAME_TIME_LIMIT.dividedBy(4L);

  /**
   * Identifies the {@link FileSystemException#getReason()} messages for which file operations that
   * warrant a retry.
   * For example, a likely cause for an AccessDeniedException from a {@link java.nio.file.Files#move},
   * under Windows anyway, is that some other system process, like indexing or anti-virus, has the file
   * momentarily open.  A retry of a rename (or delete) is appropriate.  Failures reflected by other
   * {@link FileSystemException} instances can also warrant retry.
   * <p>
   * Ideally, the JDK would reflect such "errors of interference" as specific exceptions
   * that can be easily determined and a retry attempted.
   * Unfortunately, the JDK can manifest this interference in many ways:
   *  1) AccessDeniedException reflects a Windows errorCode of 0x05 (ERROR_ACCESS_DENIED) or
   *      Unix error code of EACCES.  This error could be persistent or temporary.
   *  2) FileSystemException with a _reason_ reflecting an error code like:
   *      * Windows ERROR_SHARING_VIOLATION (0x20)
   *      * Windows ERROR_LOCK_VIOLATION (0x21)
   *      * Unix EAGAIN
   * Compounding the programmatic handling of these errors, a FileSystemException does not include
   * the errorCode -- the reason text is obtained from the Windows 'FormatMessageW' function
   * specifying 0x0 as the 'dwLanguageId' parameter or the Unix 'strerror' function.  This means that
   * the reason text is, potentially, in the local language and not suitable for reliable parsing.
   */
  private static final Set<String> RETRY_REASONS;
  static {
    Set<String> reasons = new LinkedHashSet<>();
    for (String reason : calculateReasons()) {
      if (reason != null) {
        reasons.add(reason);
      }
    }
    RETRY_REASONS = Collections.unmodifiableSet(reasons);
    LOGGER.trace("Retry reason = {}", RETRY_REASONS);
  }

  /**
   * The {@code dos:attributes} value indicating the file object is a directory.
   * This is used to detect a <i>directory link</i> which Windows handles differently from a file
   * link.  (Java does not expose the distinction between the two.)
   *
   * @see <a href="https://docs.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants"><i>
   *     Windows / Local File Systems / File Management / File Management Reference / File Management Constants / File Attribute Constants</i></a>
   */
  private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

  /**
   * {@code CopyOption} values accepted by {@link #copy(Path, Path, CopyOption...)}.
   */
  private static final Set<CopyOption> ACCEPTED_OPTIONS_COPY;
  static {
    Set<CopyOption> acceptedOptions = new HashSet<>(EnumSet.allOf(ExtendedOption.class));
    acceptedOptions.add(COPY_ATTRIBUTES);
    acceptedOptions.add(StandardCopyOption.REPLACE_EXISTING);
    acceptedOptions.add(NOFOLLOW_LINKS);
    ACCEPTED_OPTIONS_COPY = Collections.unmodifiableSet(acceptedOptions);
  }

  /**
   * Executor service for background deletion tasks.  This executor service uses only one
   * daemon thread which is permitted to die when idle.
   */
  private static final ExecutorService DELETE_EXECUTOR;
  static {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            new ThreadFactory() {
              private final ThreadGroup group = new ThreadGroup("DeletionService");
              private final AtomicInteger threadNumber = new AtomicInteger(0);
              @SuppressWarnings("NullableProblems")
              @Override
              public Thread newThread(Runnable r) {
                Thread thread = new Thread(group, r, group.getName() + "-thread-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
              }
            });
    executor.allowCoreThreadTimeOut(true);
    DELETE_EXECUTOR = executor;
  }

  private Files() { }

  /**
   * Rename the file or directory with retry for {@code FileSystemException} instances
   * indicating interference from temporary access by other processes.
   * <p>
   * This method uses the default time limit for the renaming operation.
   *
   * @param origin the path of the file/directory to rename
   * @param target the new name for the file/directory; a relative path is resolved as a sibling
   *               of {@code origin}
   * @return the path of the target
   *
   * @throws AtomicMoveNotSupportedException if {@code target} is an absolute path or relative path not
   *      on the same device/root as {@code originalPath}; this exception indicates that a copy/delete
   *      needs to be done in place of a rename
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the rename or delete fails
   * @throws SecurityException if permission to access the files/directories involved is not sufficient
   */
  public static Path rename(Path origin, Path target) throws IOException {
    return rename(origin, target, DEFAULT_RENAME_TIME_LIMIT);
  }

  /**
   * Rename the file or directory with retry for {@code FileSystemException} instances
   * indicating interference from temporary access by other processes.
   *
   * @param origin the path of the file/directory to rename
   * @param target the new name for the file/directory; a relative path is resolved as a sibling
   *               of {@code origin}
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   * @return the path of the target
   *
   * @throws AtomicMoveNotSupportedException if {@code target} is an absolute path or relative path not
   *      on the same device/root as {@code originalPath}; this exception indicates that a copy/delete
   *      needs to be done in place of a rename
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice the operation repeat delay
   * @throws IOException if the rename or delete fails
   * @throws NullPointerException if {@code origin}, {@code target}, or {@code renameTimeLimit} is null
   * @throws SecurityException if permission to access the files/directories involved is not sufficient
   */
  public static Path rename(Path origin, Path target, Duration renameTimeLimit) throws IOException {
    Objects.requireNonNull(origin, "origin must be non-null");
    Objects.requireNonNull(target, "target must be non-null");
    Objects.requireNonNull(renameTimeLimit, "renameTimeLimit must be non-null");

    Path realOrigin = origin.toRealPath(NOFOLLOW_LINKS);
    Path resolvedTarget = origin.resolveSibling(target);

    retryingRenamePath(realOrigin, () -> resolvedTarget, renameTimeLimit);
    return target;
  }

  /**
   * Deletes the file system tree beginning at the specified path.  If {@code path} is a file or a
   * symbolic link, only that file/link is deleted; if {@code path} is a directory, the directory
   * tree, without following links, is deleted.
   * <p>
   * The deletion is accomplished by first renaming the file/directory and then performing the delete.
   * The rename is performed in a manner accommodating temporary access by other processes (indexing,
   * anti-virus) and, after renaming, the file is deleted.  Because the file was first renamed, at
   * completion of this method, the path is immediately available for re-creation.
   * <p>
   * This method uses the default time limit for the renaming operation.
   *
   * @param path the file/directory path to delete
   *
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the tree deletion fails
   * @throws NullPointerException if {@code path} is null
   * @throws SecurityException if permission to access the file/directory to delete is not sufficient
   *
   * @see java.nio.file.Files#delete(Path)
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  public static void deleteTree(Path path) throws IOException {
    deleteTree(path, DEFAULT_RENAME_TIME_LIMIT);
  }

  /**
   * Deletes the file system tree beginning at the specified path.  If {@code path} is a file or a
   * symbolic link, only that file/link is deleted; if {@code path} is a directory, the directory
   * tree, without following links, is deleted.
   * <p>
   * The deletion is accomplished by first renaming the file/directory and then performing the delete.
   * The rename is performed in a manner accommodating temporary access by other processes (indexing,
   * anti-virus) and, after renaming, the file is deleted.  Because the file was first renamed, at
   * completion of this method, the path is immediately available for re-creation.
   *
   * @param path the file/directory path to delete
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   *
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice the operation repeat delay
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the tree deletion fails
   * @throws NullPointerException if {@code path} or {@code renameTimeLimit} is null
   * @throws SecurityException if permission to access the file/directory to delete is not sufficient
   *
   * @see java.nio.file.Files#delete(Path)
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  public static void deleteTree(Path path, Duration renameTimeLimit) throws IOException {
    Objects.requireNonNull(path, "path must be non-null");
    Objects.requireNonNull(renameTimeLimit, "renameTimeLimit must be non-null");

    // Get the permission checks out of the way ...
    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager != null) {
      securityManager.checkPermission(new FilePermission(path.toString(), "read,write,delete"));
    }
    Path realPath = path.toRealPath(NOFOLLOW_LINKS);

    /*
     * Delete the file/directory using rename/delete scheme.  The delete fails iff the rename fails.
     */
    deleteTreeWithRetry(realPath, true, renameTimeLimit);
  }

  /**
   * Deletes the file system path specified by first renaming the file then performing the delete.
   * The rename is performed in a manner to accommodate temporary access by other processes (indexing,
   * anti-virus) and, after renaming, the file is deleted.  Because the file was first renamed, at
   * completion of this method, the path is immediately available for re-creation.
   * <p>
   * When deleting a directory, the directory must be empty.
   * <p>
   * This method uses the default time limit for the renaming operation.
   *
   * @param path the path of the file to delete
   *
   * @throws DirectoryNotEmptyException if the directory to delete is not empty
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if deletion failed
   * @throws NullPointerException if {@code path} is null
   * @throws SecurityException if permission to access the file/directory to delete is not sufficient
   *
   * @see java.nio.file.Files#delete(Path)
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  public static void delete(Path path) throws IOException {
    delete(path, DEFAULT_RENAME_TIME_LIMIT);
  }

  /**
   * Deletes the file system path specified by first renaming the file then performing the delete.
   * The rename is performed in a manner to accommodate temporary access by other processes (indexing,
   * anti-virus) and, after renaming, the file is deleted.  Because the file was first renamed, at
   * completion of this method, the path is immediately available for re-creation.
   * <p>
   * When deleting a directory, the directory must be empty.
   *
   * @param path the path of the file to delete
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   *
   * @throws DirectoryNotEmptyException if the directory to delete is not empty
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice the operation repeat delay
   * @throws IOException if deletion failed
   * @throws NullPointerException if {@code path} or {@code renameTimeLimit} is null
   * @throws SecurityException if permission to access the file/directory to delete is not sufficient
   *
   * @see java.nio.file.Files#delete(Path)
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  public static void delete(Path path, Duration renameTimeLimit) throws IOException {
    Objects.requireNonNull(path, "path must be non-null");
    Objects.requireNonNull(renameTimeLimit, "renameTimeLimit must be non-null");

    // Get the permission checks out of the way ...
    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager != null) {
      securityManager.checkPermission(new FilePermission(path.toString(), "read,write,delete"));
    }
    Path realPath = path.toRealPath(NOFOLLOW_LINKS);

    /*
     * If path is a directory and contains items, a delete will fail where a rename
     * will not.  And, of course, it's a race to check for content before doing the
     * rename.  One way to handle this is to revert the rename if the directory
     * delete fails with a DirectoryNotEmptyException.  Another approach is to
     * continue the deletion but using a file walker to delete the directory tree.
     * The latter approach is a problem when a file/directory within the deleted
     * directory is fails deletion.
     *
     * For the time being, we're settling on a pre-check.
     */
    if (java.nio.file.Files.readAttributes(realPath, BasicFileAttributes.class, NOFOLLOW_LINKS).isDirectory()) {
      try (DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(realPath)) {
        if (stream.iterator().hasNext()) {
          LOGGER.debug("Failing to delete \"{}\"; directory not empty", realPath);
          throw new DirectoryNotEmptyException(realPath.toString());
        }
      }
    }

    /*
     * Delete the file/directory using rename/delete scheme.  The delete fails iff the rename fails.
     */
    deleteTreeWithRetry(realPath, false, renameTimeLimit);
  }

  /**
   * Deletes the file system path specified if it exists.  This method calls
   * {@link #delete(Path)} and handles the {@link NoSuchFileException} thrown if the
   * file does not exist.
   * @param path the path of the file to delete
   * @return {@code true} if the file was deleted as the result of this call; {@code false} otherwise
   * @throws IOException if deletion failed
   */
  public static boolean deleteIfExists(Path path) throws IOException {
    try {
      delete(path);
      return true;
    } catch (NoSuchFileException e) {
      return false;
    }
  }

  /**
   * Copies a file or directory.
   * <p>
   * Use the {@code copy} method instead of {@link java.nio.file.Files#move} for improved handling
   * of {@link AccessDeniedException} and other select {@link FileSystemException} thrown due to
   * interference from system tasks.
   * <p>
   * The following options are supported:
   * <table border=1 cellpadding=5 summary="">
   * <tr> <th>Option</th> <th>Description</th> </tr>
   * <tr>
   *   <td>{@link StandardCopyOption#REPLACE_EXISTING REPLACE_EXISTING}</td>
   *   <td> The target is removed at the beginning of the copy operation.
   *      Unlike {@link java.nio.file.Files#copy(Path, Path, CopyOption...)}, when
   *      {@link StandardCopyOption#REPLACE_EXISTING REPLACE_EXISTING} is specified,
   *      this method deletes the directory tree by calling {@link #deleteTree(Path)}
   *      when replacing a directory instead of failing when the target directory is
   *      not empty. If a rename time limit other than the default is wanted, call
   *      {@link #delete(Path, Duration)} before calling this method.</td>
   * </tr>
   * <tr>
   *   <td>{@link StandardCopyOption#COPY_ATTRIBUTES COPY_ATTRIBUTES}</td>
   *   <td>Attempts to copy the file attributes associated with each copied file/directory the
   *      target. The exact file attributes that are copied is governed by
   *      {@link java.nio.file.Files#copy(InputStream, Path, CopyOption...)}.</td>
   * </tr>
   * <tr>
   *   <td>{@link LinkOption#NOFOLLOW_LINKS NOFOLLOW_LINKS}</td>
   *   <td>Symbolic links are not followed. If the file is a symbolic link,
   *      then the symbolic link itself, not the target of the link, is copied.
   *      See {@link java.nio.file.Files#copy(Path, Path, CopyOption...)}.</td>
   * </tr>
   * <tr>
   *   <td>{@link ExtendedOption#RECURSIVE RECURSIVE}</td>
   *   <td>Copies the directory tree using a pre-order, depth-first traversal
   *      If {@code options} does not contain {@link LinkOption#NOFOLLOW_LINKS NOFOLLOW_LINKS},
   *      then links are followed when performing the copy -- <b>link structures are not
   *      replicated</b>.  If a directory tree contains linked files, these files may be
   *      duplicated.  If {@code NOFOLLOW_LINKS} is specified, link structures are replicated.
   *      If a link target is outside of the source directory tree, the a link to the original
   *      target content is created.
   *      <p>
   *      If omitted, {@code copy} operates as
   *      {@link java.nio.file.Files#copy(Path, Path, CopyOption...) Files.copy} copying only
   *      the directory and not its content.
   *    </td>
   * </tr>
   * <tr>
   *   <td>{@link ExtendedOption#NOSPAN_FILESTORES NOSPAN_FILESTORES}</td>
   *   <td>Constrains the copy operation files contained with the {@link FileStore} of
   *      the {@code source} path.  An attempt to copy a file/directory from a {@code FileStore}
   *      that is not the source {@code FileStore} results in a {@link FileStoreConstraintException}.
   *      Copying of links to files and directories is <i>not</i> restricted.
   *    </td>
   * </tr>
   * <tr>
   *   <td>{@link ExtendedOption#DEEP_COPY DEEP_COPY}</td>
   *   <td>Changes the linking behavior described in {@code RECURSIVE} to copy file content that is
   *      outside the source tree instead of linking it.
   *   </td>
   * </tr>
   * </table>
   *
   * <h2>Caution</h2>
   * Windows, by default, restricts the creation of symbolic links to administrator accounts.
   * The use of the {@link LinkOption#NOFOLLOW_LINKS NOFOLLOW_LINKS} option by accounts not
   * having permissions to create symbolic links may result in a {@code FileSystemException}
   * and the copy failing.  See
   * <a href="https://stackoverflow.com/a/24353758/1814086">How to create Soft symbolic Link using java.nio.Files</a>
   * for additional detail.
   *
   * @param source the file/directory from which the copy is made
   * @param target the file/directory to which the copy is made; must not exist unless
   *               {@link StandardCopyOption#REPLACE_EXISTING} is specified in
   * @param options options governing the copy operation
   * @return the path of the target
   *
   * @throws DirectoryNotEmptyException if {@code target} is a non-empty directory
   * @throws FileAlreadyExistsException if {@code target} exists and
   *          {@link StandardCopyOption#REPLACE_EXISTING REPLACE_EXISTING} is not specified
   * @throws FileStoreConstraintException if {@code NOSPAN_FILESTORES} is specified and an
   *          attempt to copy a non-local file is made
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the copy operation fails
   * @throws NullPointerException if {@code source} or {@code target} is null
   * @throws UnsupportedOperationException if {@code options} contains an unsupported value
   * @throws SecurityException if permission to access the source and target file/directory is not sufficient
   *
   * @see ExtendedOption
   * @see StandardCopyOption
   * @see LinkOption
   * @see java.nio.file.Files#copy(Path, Path, CopyOption...)
   */
  public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
    Objects.requireNonNull(source, "source must be non-null");
    Objects.requireNonNull(target, "target must be non-null");

    try {
      return copyInternal(source, target, options);
    } finally {
      DRIVE_SUBSTITUTIONS.remove();
      FILE_STORE_CACHE.remove();
    }
  }

  /**
   * Recursively, internally-called copy method.
   * @param source the file/directory from which the copy is made
   * @param target the file/directory to which the copy is made; must not exist unless
   *               {@link StandardCopyOption#REPLACE_EXISTING} is specified in
   * @param options options governing the copy operation
   * @return the path of the target
   *
   * @throws IOException if the copy operation fails
   * @throws NullPointerException if {@code source} or {@code target} is null
   * @throws UnsupportedOperationException if {@code options} contains an unsupported value
   * @throws SecurityException if permission to access the source and target file/directory is not sufficient
   */
  private static Path copyInternal(Path source, Path target, CopyOption[] options) throws IOException {
    Set<CopyOption> copyOptions = new LinkedHashSet<>(Arrays.asList(options));

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("copy({}, {}, {})", source, target, Arrays.toString(options));
    }

    if (!ACCEPTED_OPTIONS_COPY.containsAll(copyOptions)) {
      copyOptions.removeAll(ACCEPTED_OPTIONS_COPY);
      throw new UnsupportedOperationException("Unsupported option(s) specified - " + copyOptions);
    }

    boolean noFollowLinks = copyOptions.contains(NOFOLLOW_LINKS);
    boolean recursive = copyOptions.contains(ExtendedOption.RECURSIVE);
    LinkOption[] linkOptions = (noFollowLinks ? new LinkOption[] { NOFOLLOW_LINKS } : new LinkOption[0]);

    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager != null) {
      securityManager.checkPermission(new FilePermission(source.toString(), "read"));
      if (noFollowLinks) {
        securityManager.checkPermission(new FilePermission(source.toString(), "readlink"));
        securityManager.checkPermission(new LinkPermission(source.toString(), "symbolic"));
      }
      securityManager.checkPermission(new FilePermission(target.toString(), "read,write,delete"));
    }

    /*
     * Resolve the caller's source -- without following links.  This is used as the
     * point against which FileStore span checks are performed.
     */
    Path specifiedSource = source.toRealPath(NOFOLLOW_LINKS);

    /*
     * Resolve the target path ...
     */
    Path resolvedTarget = target.toAbsolutePath();

    /*
     * If the target exists, check if the source and target are the same -- copy is a no-op.
     * If REPLACE_EXISTING is specified and the file/directory exists, delete the file/directory.
     * This is obviously a race but there's little that can be done about it.
     */
    if (java.nio.file.Files.exists(resolvedTarget, NOFOLLOW_LINKS)) {
      if (java.nio.file.Files.isSameFile(specifiedSource.toRealPath(linkOptions), resolvedTarget)) {
        return target;
      }

      if (copyOptions.contains(StandardCopyOption.REPLACE_EXISTING)) {
        delete(resolvedTarget);
      } else {
        throw new FileAlreadyExistsException(resolvedTarget.toString());
      }
    }

    /*
     * Create the parent of the target directory, if necessary.
     */
    // TODO: Do we really want to do this?
    // BEHAVIOR CHANGE: java.nio.file.Files.copy would throw NoSuchFileException if parent is missing
    Path resolvedTargetParent = resolvedTarget.getParent();
    if (resolvedTargetParent != null) {
      java.nio.file.Files.createDirectories(resolvedTargetParent);
    }

    /*
     * Now file copying actually begins.  It is **not** expected to see an AccessDeniedException
     * due to a background system process here so no special handling for the exception is used
     * during copying.  If the exception is thrown, then it is reflected to the caller.
     *
     * If the source is a link to an _outside_ directory and NOFOLLOW_LINKS & DEEP_COPY are
     * specified, the copy needs to be processed recursively rather than as a simple link copy.
     */
    if (recursive && (
        java.nio.file.Files.isDirectory(specifiedSource, linkOptions) || (
            noFollowLinks && copyOptions.contains(ExtendedOption.DEEP_COPY)
                && isSymbolicLink(specifiedSource)
                && isDirectory(specifiedSource)))) {
      /*
       * Now walk the directory/file tree and copy everything; copying attributes if requested.
       */
      Set<FileVisitOption> fileVisitOptions =
          (noFollowLinks ? EnumSet.noneOf(FileVisitOption.class) : EnumSet.of(FileVisitOption.FOLLOW_LINKS));
      java.nio.file.Files.walkFileTree(specifiedSource, fileVisitOptions, Integer.MAX_VALUE,
          new CopyingFileVisitor(specifiedSource, resolvedTarget, copyOptions, linkOptions));

    } else {
      /*
       * Copy a single file/directory/link.
       */
      CopyOption[] effectiveCopyOptions = effectiveCopyOptions(copyOptions);
      if (copyOptions.contains(ExtendedOption.DEEP_COPY)) {
        List<CopyOption> x = new ArrayList<>(Arrays.asList(effectiveCopyOptions));
        x.remove(NOFOLLOW_LINKS);
        effectiveCopyOptions = x.toArray(new CopyOption[0]);
      }
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Files.copy({}, {}, {})", specifiedSource, resolvedTarget, Arrays.toString(effectiveCopyOptions));
      }
      if (copyOptions.contains(ExtendedOption.NOSPAN_FILESTORES) && !copyOptions.contains(NOFOLLOW_LINKS)) {
        Path effectiveSource = isSymbolicLink(specifiedSource) ? specifiedSource.getParent() : specifiedSource;
        Path resolvedSource = specifiedSource.toRealPath(linkOptions);
        if (!getFileStore(effectiveSource).equals(getFileStore(resolvedSource))) {
          throw new FileStoreConstraintException(specifiedSource.toString(), resolvedSource.toString(), "not in same FileStore");
        }
      }
      java.nio.file.Files.copy(specifiedSource, resolvedTarget, effectiveCopyOptions);
    }

    return target;
  }

  /**
   * Moves (relocates) the source to the target location.  This method should be used
   * instead of the {@link java.nio.file.Files#move(Path, Path, CopyOption...)} method
   * for improved handling of file system interference like {@link AccessDeniedException}.
   * <p>
   * This method first attempts a {@link #rename(Path, Path) rename} and, if that fails due to
   * an {@link AtomicMoveNotSupportedException}, a {@link #copy} and {@link #deleteTree} are
   * used.
   * <p>
   * The following copy options are defaulted and cannot be overridden:
   * <ul>
   *   <li>{@link LinkOption#NOFOLLOW_LINKS NOFOLLOW_LINKS}</li>
   *   <li>{@link StandardCopyOption#COPY_ATTRIBUTES COPY_ATTRIBUTES}</li>
   *   <li>{@link ExtendedOption#RECURSIVE RECURSIVE}</li>
   * </ul>
   *
   * @param source the file/directory from which the copy is made
   * @param target the file/directory to which the copy is made; must not exist unless
   *               {@link StandardCopyOption#REPLACE_EXISTING} is specified in
   * @param options options governing the copy operation
   * @return the path of the target
   *
   * @throws DirectoryNotEmptyException if {@code target} is a non-empty directory
   * @throws FileAlreadyExistsException if {@code target} exists and
   *          {@link StandardCopyOption#REPLACE_EXISTING REPLACE_EXISTING} is not specified
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the copy operation fails
   * @throws NullPointerException if {@code source} or {@code target} is null
   * @throws UnsupportedOperationException if {@code options} contains an unsupported value
   * @throws SecurityException if permission to access the source and target file/directory is not sufficient
   *
   * @see ExtendedOption
   * @see StandardCopyOption
   * @see LinkOption
   * @see java.nio.file.Files#copy(Path, Path, CopyOption...)
   */
  public static Path relocate(Path source, Path target, CopyOption... options) throws IOException {
    Objects.requireNonNull(source, "source must be non-null");
    Objects.requireNonNull(target, "target must be non-null");
    Set<CopyOption> copyOptions = new LinkedHashSet<>(Arrays.asList(options));
    copyOptions.add(NOFOLLOW_LINKS);
    copyOptions.add(COPY_ATTRIBUTES);
    copyOptions.add(ExtendedOption.RECURSIVE);

    try {
      return rename(source, target);      // If rename was successful, we're done
    } catch (AtomicMoveNotSupportedException e) {
      // Handled below
    }

    copy(source, target, copyOptions.toArray(new CopyOption[0]));
    deleteTree(source);
    return target;
  }

  /**
   * Delete the file or directory tree using a rename/delete scheme with retry for
   * {@code FileSystemException} instances indicating interference from temporary access by other processes.
   *
   * @param path the file or root of the directory to delete
   * @param retryDirNotEmpty enable retry for {@link DirectoryNotEmptyException}
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   *
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice the operation repeat delay
   * @throws IOException if the rename or delete fails
   * @throws SecurityException if permission to access the file/directory to rename/delete is not sufficient
   */
  private static void deleteTreeWithRetry(Path path, boolean retryDirNotEmpty, Duration renameTimeLimit)
      throws IOException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[deleteTreeWithRetry] Deleting \"{}\"", path);
    }

    /*
     * First, attempt a rename of the file/directory. The expectation with this move is that it's a
     * "simple" rename -- we're not moving across file system roots -- so an "atomic" operation is
     * what we want. If this fails for an AtomicMoveNotSupportedException, it gets reflected to the
     * caller as a delete failure.
     *
     * If the rename succeeds, the "delete" is successful and the caller's following operations can
     * proceed,  The remainder of the delete work can be handled in the background if warranted.
     */
    Path renamedPath = retryingRename(path, () -> randomName("del"), renameTimeLimit);

    /*
     * Deleting a file or a directory and its contents.  In this, links are not followed -- only
     * direct tree content is removed.
     */
    LOGGER.debug("Deleting \"{}\" renamed from \"{}\"", renamedPath, path);
    deleteTreeWithBackgroundRetry(renamedPath, retryDirNotEmpty);
  }

  /**
   * Attempts to delete a file/directory retrying in background in the event of a retryable failure.
   *
   * @param path the {@code Path} to delete
   * @param retryDirNotEmpty enable retry for {@link DirectoryNotEmptyException}
   */
  private static void deleteTreeWithBackgroundRetry(Path path, boolean retryDirNotEmpty) {
    RetryingDeleteTask deleteTask = new RetryingDeleteTask(DELETE_EXECUTOR, () -> {
      treeDelete(path, retryDirNotEmpty, OPERATION_ATTEMPTS, OPERATION_REPEAT_DELAY);
      return null;
    }, path, retryDirNotEmpty);
    deleteTask.run();
  }

  /**
   * Deletes the file or directory tree retrying for select {@link FileSystemException} and, optionally,
   * a {@link DirectoryNotEmptyException}.  The tree is walked <i>without</i> following links.
   * @param path the file or root of the directory to delete
   * @param retryDirNotEmpty enable retry for {@link DirectoryNotEmptyException}
   * @param attempts the number of delete attempts permitted
   * @param retryDelay the duration of the pause between delete retries
   *
   * @throws DirectoryNotEmptyException if the number of deletion attempts for
   *      {@code DirectoryNotEmptyException} is exhausted or the retries are interrupted
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code attempts} is not positive or {@code retryDelay} is negative
   * @throws IOException if the delete failed
   */
  private static void treeDelete(Path path, boolean retryDirNotEmpty, int attempts, Duration retryDelay)
      throws IOException {
    if (attempts <= 0) {
      throw new IllegalArgumentException("attempts must be positive");
    }
    if (retryDelay.isNegative()) {
      throw new IllegalArgumentException("retryDelay duration must be non-negative");
    }

    java.nio.file.Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        retryingDelete(file, retryDirNotEmpty, attempts, retryDelay);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) {
          throw exc;
        } else {
          retryingDelete(dir, retryDirNotEmpty, attempts, retryDelay);
          return FileVisitResult.CONTINUE;
        }
      }
    });
  }

  /**
   * Attempts to delete the specified path.  If {@link java.nio.file.Files#delete(Path) Files.delete}
   * throws a select {@link FileSystemException} or {@link DirectoryNotEmptyException}, the delete is
   * retried up to the {@code attempts} limit. If {@code path} does not exist, this method does not throw.
   *
   * @param path the path to delete
   * @param retryDirNotEmpty enable retry for {@link DirectoryNotEmptyException}
   * @param attempts the number of delete attempts permitted
   * @param retryDelay the duration of the pause between delete retries
   *
   * @throws DirectoryNotEmptyException if the number of deletion attempts for
   *      {@code DirectoryNotEmptyException} is exhausted or the retries are interrupted
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IOException if the delete failed
   *
   * @see java.nio.file.Files#delete(Path)
   */
  private static void retryingDelete(Path path, boolean retryDirNotEmpty, int attempts, Duration retryDelay)
      throws IOException {
    assert attempts > 0;
    assert !retryDelay.isNegative();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[retryingDelete] Deleting \"{}\"", path);
    }

    IOException last = null;
    int tries;
    for (tries = 0; tries < attempts; tries++) {
      try {
        // We're deleting -- if the file/directory is already deleted, we don't care
        java.nio.file.Files.deleteIfExists(path);
        return;
      } catch (DirectoryNotEmptyException e) {
        last = e;
        if (retryDirNotEmpty) {
          try {
            TimeUnit.NANOSECONDS.sleep(retryDelay.toNanos());
          } catch (InterruptedException ex) {
            last.addSuppressed(ex);
            break;
          }
        } else {
          break;
        }
      } catch (FileSystemException e) {
        /*
         * Retry the retryable FileSystemExceptions.
         */
        last = e;
        if ((e instanceof AccessDeniedException) || RETRY_REASONS.contains(e.getReason())) {
          try {
            TimeUnit.NANOSECONDS.sleep(retryDelay.toNanos());
          } catch (InterruptedException ex) {
            last.addSuppressed(ex);
            break;
          }
        } else {
          /*
           * A FileSystemException for which retry is not enabled...
           */
          break;
        }
      } catch (IOException e) {
        last = e;
        break;
      }
    }
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Delete for \"{}\" failed; tries={}", path, tries, last);
    }
    throw last;
  }

  /**
   * Attempts to rename the specified path to a new name relative to the parent directory.  If
   * {@link java.nio.file.Files#move(Path, Path, CopyOption...)}  Files.move} throws a select
   * {@link FileSystemException}, the rename is retried up to the {@code attempts} limit.
   *
   * @param originalPath the file/directory path to rename
   * @param targetNameSupplier the {@code Supplier} from which the new name is obtained
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   * @return the name to which {@code originalPath} was renamed
   *
   * @throws AtomicMoveNotSupportedException if {@code targetNameSupplier} provides an
   *      absolute path not on the same device/root as {@code originalPath}; this exception
   *      indicates that a copy/delete needs to be done in place of a rename
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice {@link #OPERATION_REPEAT_DELAY}
   * @throws InvalidPathException if {@code targetNameSupplier} returns a bad path
   * @throws IOException if the rename failed
   *
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  private static Path retryingRename(Path originalPath, Supplier<String> targetNameSupplier, Duration renameTimeLimit)
      throws IOException {
    Supplier<Path> renamePathSupplier =
        () -> originalPath.resolveSibling(originalPath.getFileSystem().getPath(targetNameSupplier.get()));
    return retryingRenamePath(originalPath, renamePathSupplier, renameTimeLimit);
  }

  /**
   * Attempts to rename the specified path to a new path.  If
   * {@link java.nio.file.Files#move(Path, Path, CopyOption...)}  Files.move} throws a select
   * {@link FileSystemException}, the rename is retried up to the {@code attempts} limit.
   *
   * @param originalPath the file/directory path to rename
   * @param renamePathSupplier the {@code Supplier} from which the new path is obtained
   * @param renameTimeLimit the time limit to apply to renaming the file/directory before deletion
   * @return the name to which {@code originalPath} was renamed
   *
   * @throws AtomicMoveNotSupportedException if {@code renamePathSupplier} provides an
   *      absolute path not on the same device/root as {@code originalPath}; this exception
   *      indicates that a copy/delete needs to be done in place of a rename
   * @throws FileSystemException for a retryable exception if the time permitted for rename attempts
   *          and retryable exception handling is exhausted or interrupted
   * @throws IllegalArgumentException if {@code renameTimeLimit} is not at least twice {@link #OPERATION_REPEAT_DELAY}
   * @throws IOException if the rename failed
   *
   * @see java.nio.file.Files#move(Path, Path, CopyOption...)
   */
  private static Path retryingRenamePath(Path originalPath, Supplier<Path> renamePathSupplier, Duration renameTimeLimit)
      throws IOException {

    if (renameTimeLimit.compareTo(MINIMUM_TIME_LIMIT) < 0) {
      throw new IllegalArgumentException("renameTimeLimit must be greater than " + MINIMUM_TIME_LIMIT);
    }

    IOException last;
    Path renamePath;
    boolean unexpected = false;
    long startTime = System.nanoTime();
    long deadline = startTime + renameTimeLimit.toNanos();
    do {
      renamePath = renamePathSupplier.get();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Renaming \"{}\" to \"{}\"", originalPath, renamePath);
      }
      try {
        java.nio.file.Files.move(originalPath, renamePath, StandardCopyOption.ATOMIC_MOVE);
        return renamePath;
      } catch (AtomicMoveNotSupportedException e) {
        // Unrecoverable -- indicates a copy/delete is needed.
        throw e;
      } catch (FileSystemException e) {
        /*
         * Retry the retryable FileSystemExceptions.
         */
        last = e;
        if ((e instanceof AccessDeniedException) || RETRY_REASONS.contains(e.getReason())) {
          try {
            TimeUnit.NANOSECONDS.sleep(OPERATION_REPEAT_DELAY.toNanos());
          } catch (InterruptedException ex) {
            last.addSuppressed(ex);
            break;
          }
          if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Retrying rename of \"{}\"; elapsedTime={}",
                originalPath, Duration.ofNanos(System.nanoTime() - startTime));
          }
        } else {
          /*
           * A FileSystemException for which retry is not enabled...
           */
          unexpected = true;
          break;
        }
      } catch (IOException e) {
        last = e;
        unexpected = true;
        break;
      }
    } while (deadline - System.nanoTime() >= 0);

    /*
     * At this point, the number of rename attempts has been exhausted.
     */
    LOGGER.warn("{} IOException renaming \"{}\" to \"{}\"; elapsedTime={}",
        (unexpected ? "Unexpected" : "Persistent"), originalPath, renamePath, Duration.ofNanos(System.nanoTime() - startTime), last);
    throw last;
  }

  private static CopyOption[] effectiveCopyOptions(Set<CopyOption> copyOptions) {
    EnumSet<ExtendedOption> privateOptions = EnumSet.allOf(ExtendedOption.class);
    return copyOptions.stream()
        .filter(o -> !privateOptions.contains(o))
        .toArray(CopyOption[]::new);
  }

  private static String format(BasicFileAttributes attributes) {
    return pathType(attributes).toString();
  }

  private static final SecureRandom random = new SecureRandom();
  @SuppressWarnings("SameParameterValue")
  private static String randomName(String prefix) {
    long r = random.nextLong();
    return prefix + (r == Long.MIN_VALUE ? 0 : Math.abs(r));
  }

  /**
   * Task to perform a background deletion.  This task resubmits itself if the deletion, after retry, fails
   * delete the file/directory.
   */
  private static class RetryingDeleteTask extends FutureTask<Void> {
    private final ExecutorService executor;
    private final Callable<Void> task;
    private final Path deletionPath;
    private final boolean retryDirNotEmpty;

    private RetryingDeleteTask(ExecutorService executor, Callable<Void> deletionTask, Path deletionPath, boolean retryDirNotEmpty) {
      super(deletionTask);
      this.executor = executor;
      this.task = deletionTask;
      this.deletionPath = deletionPath;
      this.retryDirNotEmpty = retryDirNotEmpty;
    }

    @Override
    public void run() {
      boolean retry = true;
      try {
        task.call();
        retry = false;
      } catch (DirectoryNotEmptyException e) {
        if (retryDirNotEmpty) {
          LOGGER.warn("Failed to delete \"{}\" - retrying", deletionPath, e);
        } else {
          LOGGER.warn("Background deletion of \"{}\" failed - manual cleanup needed", deletionPath, e);
          retry = false;
        }
      } catch (FileSystemException e) {
        if ((e instanceof AccessDeniedException) || RETRY_REASONS.contains(e.getReason())) {
          LOGGER.warn("Failed to delete \"{}\" - retrying", deletionPath, e);
        } else {
          retry = false;
        }

      } catch (Exception e) {
        LOGGER.warn("Background deletion of \"{}\" failed - manual cleanup needed", deletionPath, e);
        retry = false;
      }

      if (retry) {
        try {
          executor.execute(this);
          LOGGER.info("Submitted background deletion task for \"{}\"", deletionPath);
        } catch (RejectedExecutionException e) {
          LOGGER.warn("Background deletion for \"{}\" failed - manual cleanup needed", deletionPath, e);
        }
      } else {
        LOGGER.debug("Deletion complete for \"{}\"", deletionPath);
        set(null);
      }
    }
  }

  /**
   * A {@link FileVisitor} implementation that copies a directory tree.
   */
  private static class CopyingFileVisitor extends SimpleFileVisitor<Path> {

    private final FileStore sourceFileStore;
    private final Path target;
    private final Set<CopyOption> copyOptions;
    private final LinkOption[] linkOptions;
    private final Path source;
    private final boolean noSpan;
    private final boolean deepCopy;
    private final CopyOption[] effectiveCopyOptions;

    public CopyingFileVisitor(Path source, Path target, Set<CopyOption> copyOptions, LinkOption[] linkOptions)
        throws IOException {
      this.source = source;
      this.target = target;
      this.copyOptions = copyOptions;
      this.linkOptions = linkOptions;
      this.sourceFileStore = isSymbolicLink(source) ? getFileStore(source.getParent()) : getFileStore(source);
      this.noSpan = copyOptions.contains(ExtendedOption.NOSPAN_FILESTORES);
      this.deepCopy = copyOptions.contains(ExtendedOption.DEEP_COPY);
      this.effectiveCopyOptions = Files.effectiveCopyOptions(copyOptions);
    }

    /**
     * Make the source path provided refer to the same place in the target.
     * @param path the source path to relativize/resolve
     * @return the resolved target path
     */
    private Path relocate(Path path) {
      return target.resolve(source.relativize(path));
    }

    /**
     * Throws a {@link FileStoreConstraintException} if the path provided, with links resolved
     * according to {@code linkOptions}, is not in the same {@code FileStore} as the specified
     * copy source.
     * @param path the {@code Path} for which the {@code FileStore} is to be determined
     * @param linkOptions {@code LinkOption} values used for interpreting {@code path}
     * @throws IOException if an error is raised in determining the {@code FileStore}
     */
    private void checkFileStore(Path path, LinkOption... linkOptions) throws IOException {
      if (noSpan && !sourceFileStore.equals(getFileStore(path.toRealPath(linkOptions)))) {
        throw new FileStoreConstraintException(source.toString(), path.toString(), "not in same FileStore");
      }
    }

    /**
     * Create the target directories using {@link java.nio.file.Files#copy(Path, Path, CopyOption...)}
     * to in order to copy the directory attributes if requested.
     * @param dir the source directory to copy
     * @param attrs the attributes of the source directory
     * @return {@link FileVisitResult#CONTINUE}
     * @throws IOException if the copy fails
     */
    // This method **does not** get called with directory symlinks when NOFOLLOW_LINKS is in effect;
    // these are presented to visitFile as a link (not a file or directory).
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
      Path targetDir = relocate(dir);
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Files.copy({}, {}, {}) attrs={}",
            dir, targetDir, Arrays.toString(effectiveCopyOptions), format(attrs));
      }
      checkFileStore(dir, linkOptions);
      java.nio.file.Files.copy(dir, targetDir, effectiveCopyOptions);
      return FileVisitResult.CONTINUE;
    }

    /**
     * Copy the source file using {@link java.nio.file.Files#copy(Path, Path, CopyOption...)} to
     * the like location in the target copying attributes as requested.  Links presented here
     * are either replicated -- if the link target is within the source tree -- or copied --
     * if the link target resides outside of the source tree.
     *
     * @param file the source file/link to copy
     * @param attrs the attributes of the source file
     * @return {@link FileVisitResult#CONTINUE}
     * @throws IOException if the copy fails
     */
    // This method is called with any file structure other than a directory -- file, link, other
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      Path targetFile = relocate(file);
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Files.copy({}, {}, {}) attrs={}",
            file, targetFile, Arrays.toString(effectiveCopyOptions), format(attrs));
      }

      /*
       * When NOFOLLOW_LINKS is specified, the visitFile method gets called with files having
       * the 'link' attribute for a symbolic link to a file or a directory.  If the symbolic
       * link target is _within_ the copy source, we want to create a new symlink to the new
       * location within target.  If the symbolic link target is outside of the source
       * directory, we create a to the same content.
       */
      if (attrs.isSymbolicLink()) {
        boolean foreignContent = false;
        Path foreignLink = null;
        Path linkTarget = java.nio.file.Files.readSymbolicLink(file);

        /*
         * Windows differentiates file and directory links and, by default, Windows creates
         * file links when the link referent is not available -- as can happen when copying
         * a directory structure.  Even though a directory link can be detected by this code
         * (dos:attributes), the Java API presently does not permit the creation of a directory
         * link if the link referent does not exist -- something special needs to be done for
         * Windows :-(.
         */
        boolean isWindowsDirectoryLink;
        try {
          Integer fileAttributes = (Integer) java.nio.file.Files.getAttribute(file, "dos:attributes", NOFOLLOW_LINKS);
          isWindowsDirectoryLink = (fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
          isWindowsDirectoryLink = false;
        }

        if (linkTarget.isAbsolute()) {
          /*
           * The target is absolute.  If it's within the source tree, create a
           * relocated link.  Otherwise, process as "foreign" content.
           */
          if (linkTarget.startsWith(source)) {
            // Create link to new spot within target
            Path relocatedLinkTarget = relocate(linkTarget);
            if (isWindowsDirectoryLink) {
              createWindowsDirectorySymbolicLink(targetFile, relocatedLinkTarget);
            } else {
              LOGGER.trace("Files.createSymbolicLink({}, {})", targetFile, relocatedLinkTarget);
              java.nio.file.Files.createSymbolicLink(targetFile, relocatedLinkTarget);
            }
          } else {
            foreignContent = true;
            foreignLink = linkTarget;
          }
        } else {
          /*
           * The target is a relative reference -- resolve it against the current file's
           * parent and, if the result is within the source tree, create a relocated
           * link.  Otherwise, process as "foreign" content.
           */
          Path resolvedLinkTarget = file.resolveSibling(linkTarget).toAbsolutePath();
          if (resolvedLinkTarget.startsWith(source)) {
            // Create relative link to new spot within target
            if (isWindowsDirectoryLink) {
              createWindowsDirectorySymbolicLink(targetFile, linkTarget);
            } else {
              LOGGER.trace("Files.createSymbolicLink({}, {})", targetFile, linkTarget);
              java.nio.file.Files.createSymbolicLink(targetFile, linkTarget);
            }
          } else {
            foreignContent = true;
            foreignLink = resolvedLinkTarget;
          }
        }

        /*
         * The link target is outside the source tree.  Perform a deep copy (copying content) or
         * create a link replicating the original link.
         */
        if (foreignContent) {
          if (!deepCopy) {
            // Shallow copy -- link to the original content
            if (linkTarget.isAbsolute()) {
              if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[symlink] SHALLOW copy({}, {}, {}) attrs={}",
                    file, targetFile, Arrays.toString(effectiveCopyOptions), format(attrs));
              }
              java.nio.file.Files.copy(file, targetFile, effectiveCopyOptions);
            } else {
              if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[symlink] createSymbolicLink({}, {}) attrs={}",
                    foreignLink, targetFile, format(attrs));
              }
              java.nio.file.Files.createSymbolicLink(foreignLink, targetFile);
            }
          } else {
            // Copy the linked target content -- remove NOFOLLOW_LINKS from the copy options
            CopyOption[] symlinkOptions = copyOptions.stream()
                .filter(o -> !o.equals(NOFOLLOW_LINKS))
                .toArray(CopyOption[]::new);
            if (LOGGER.isTraceEnabled()) {
              LOGGER.trace("[symlink] DEEP copy({}, {}, {}) attrs={}",
                  file, targetFile, Arrays.toString(symlinkOptions), format(attrs));
            }
            checkFileStore(file);
            copyInternal(file, targetFile, symlinkOptions);
          }
        }

      } else {
        checkFileStore(file, linkOptions);
        java.nio.file.Files.copy(file, targetFile, effectiveCopyOptions);
      }

      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * Creates a Windows directory link.  Since the JDK support for Windows symbolic linking cannot
   * create a <i>directory</i> symbolic link without having an existing target, this method temporarily
   * creates, if necessary, the directory target before creating the symbolic link.  Once the link is
   * created, the temporary directory is deleted by calling {@link #delete(Path)}.
   * @param   link the path of the symbolic link to create
   * @param   target the target of the symbolic link
   * @param   attrs the array of attributes to set atomically when creating the symbolic link
   * @throws IOException if an error occurs creating the link
   */
  private static void createWindowsDirectorySymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
    LOGGER.trace("createWindowsDirectorySymbolicLink({}, {})", link, target);

    Path resolvedTarget = link.resolveSibling(target).normalize();
    Path deletionPoint = null;
    if (!java.nio.file.Files.exists(resolvedTarget, NOFOLLOW_LINKS)) {
      /*
       * Determine at what point the resolvedTarget doesn't exist and create the directories from there.
       */
      LOGGER.debug("[createWindowsDirectorySymbolicLink] Creating temporary directory \"{}\" for linking", resolvedTarget);
      Path constructedPath = resolvedTarget.getRoot();
      for (Path path : resolvedTarget) {
        constructedPath = constructedPath.resolve(path);
        if (!java.nio.file.Files.exists(constructedPath, NOFOLLOW_LINKS)) {
          if (deletionPoint == null) {
            deletionPoint = constructedPath;
          }
          java.nio.file.Files.createDirectory(constructedPath);
        }
      }
    }

    LOGGER.trace("Files.createSymbolicLink({}, {})", link, target);
    java.nio.file.Files.createSymbolicLink(link, target, attrs);

    if (deletionPoint != null) {
      LOGGER.debug("[createWindowsDirectorySymbolicLink] Deleting temporary directory \"{}\"", deletionPoint);
      deleteTree(deletionPoint);
    }
  }

  /**
   * Gets the {@link FileStore} for the path provided.  This method handles obtaining the
   * {@code FileStore} for a Windows SUBST assignment by invoking the {@code subst} command
   * and determining the path to which the drive is assigned.  The result of the {@code FileStore}
   * determination is cached until {@code path} become weakly referenced.
   *
   * @param path the path for which the {@code FileStore} is determined
   * @return the {@code FileStore} for {@code path}; if the {@code FileStore} cannot be
   *          determined due to a {@link FileSystemException}, a {@code FileStore} instance
   *          unique to {@code path} is returned
   * @throws IOException if an I/O error occurs while determining the {@code FileStore}
   */
  private static FileStore getFileStore(Path path) throws IOException {
    Map<Path, FileStore> storeMap = FILE_STORE_CACHE.get();
    FileStore fileStore = storeMap.get(path);
    if (fileStore != null) {
      return fileStore;
    }

    try {
      fileStore = java.nio.file.Files.getFileStore(path);
    } catch (FileSystemException e) {
      if (e.getReason() == null) {
        // A specialized exception gets re-thrown.
        throw e;
      }

      /*
       * A path on a Windows SUBST drive is poorly handled by the JDK due to odd handling the
       * Windows GetVolumePathNameW -- it fails with an ERROR_INVALID_PARAMETER (which we cannot
       * directly observe). There has been at least one problem report,
       * <a href="https://bugs.openjdk.java.net/browse/JDK-8034057">Files.getFileStore and Files.isWritable do not work with SUBST'ed drives (win)</a>,
       * that is not properly repaired.
       *
       * This code attempts to use the Windows SUBST to determine the path root's anchor.
       */
      Path root = path.getRoot();
      if (root != null) {
        Path substitutePath = DRIVE_SUBSTITUTIONS.get().get(root);
        if (substitutePath != null) {
          substitutePath = substitutePath.resolve(root.relativize(path));
          try {
            fileStore = java.nio.file.Files.getFileStore(substitutePath);
          } catch (FileSystemException ex) {
            // Use the "proxy" one below
          }
        }
      }

      if (fileStore == null) {
        fileStore = new UnknownFileStore(path);
      }
    }

    storeMap.put(path, fileStore);
    return fileStore;
  }
  private static final ThreadLocal<Map<Path, FileStore>> FILE_STORE_CACHE = ThreadLocal.withInitial(WeakHashMap::new);

  /**
   * If a Windows platform, gets the current SUBST assignments, if any.  Because the SUBST
   * list can change without notice, this assignment map is not cached.  A failure in obtaining
   * the assignment list results in the return of an empty map.
   * @return the current SUBST assignments
   */
  private static Map<Path, Path> getSubsts() {
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
  private static final Pattern SUBST_PAIR = Pattern.compile("(.*): => (.*)");
  private static final ThreadLocal<Map<Path, Path>> DRIVE_SUBSTITUTIONS = ThreadLocal.withInitial(Files::getSubsts);

  /**
   * Dynamically determine reasons for file operation retry.
   * @return a {@code Set} of {@link FileSystemException#getReason()} values warranting retry
   */
  private static Set<String> calculateReasons() {
    /*
     * First, set up a directory tree to use for test file operations.
     *
     *                           top
     *                          /
     *                         dir
     *                        /   \
     *                     file1  file2
     */
    Path file2;
    Path file1;
    Path dir;
    Path top;
    try {
      top = java.nio.file.Files.createTempDirectory("top");
      top.toFile().deleteOnExit();

      dir = java.nio.file.Files.createDirectory(top.resolve("dir"));
      dir.toFile().deleteOnExit();

      file1 = java.nio.file.Files.createFile(dir.resolve("file1"));
      file1.toFile().deleteOnExit();
      java.nio.file.Files.write(file1, Collections.singleton("file1"), StandardCharsets.UTF_8);

      file2 = java.nio.file.Files.createFile(dir.resolve("file2"));
      file2.toFile().deleteOnExit();
      java.nio.file.Files.write(file1, Collections.singleton("file2"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOGGER.trace("Unexpected I/O error constructing failure reasons", e);
      return Collections.emptySet();
    }

    Set<String> reasons = new LinkedHashSet<>();

    /*
     * File is open for read versus move/rename.
     */
    try (PathHolder holder = new PathHolder(file1, false)) {
      holder.start();
      file1 = java.nio.file.Files.move(file1, file1.resolveSibling("renamed"), StandardCopyOption.ATOMIC_MOVE);
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
      dir = java.nio.file.Files.move(dir, dir.resolveSibling("renamed"), StandardCopyOption.ATOMIC_MOVE);
    } catch (FileSystemException e) {
      reasons.add(e.getReason());
      LOGGER.trace("Observed for file/dir OPEN rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
    } catch (Exception e) {
      // Nothing we can do about this ...
      LOGGER.trace("Unexpected IOException renaming {}", file1, e);
    }

    /*
     * File locked versus move/rename.
     */
    try (PathHolder holder = new PathHolder(file1, true)) {
      holder.start();
      file1 = java.nio.file.Files.move(file1, file1.resolveSibling("renamed"), StandardCopyOption.ATOMIC_MOVE);
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
      dir = java.nio.file.Files.move(dir, dir.resolveSibling("renamed"), StandardCopyOption.ATOMIC_MOVE);
    } catch (FileSystemException e) {
      reasons.add(e.getReason());
      LOGGER.trace("Observed for file/dir LOCKED rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
    } catch (Exception e) {
      // Nothing we can do about this ...
      LOGGER.trace("Unexpected IOException renaming {}", file1, e);
    }

    /*
     * Directory is open for read versus file move/rename.
     */
    try (PathHolder holder = new PathHolder(dir, false)) {
      holder.start();
      file1 = java.nio.file.Files.move(file1, file1.resolveSibling("renamed"), StandardCopyOption.ATOMIC_MOVE);
      LOGGER.trace("Succeeded dir/file rename");
    } catch (FileSystemException e) {
      reasons.add(e.getReason());
      LOGGER.trace("Observed for dir/file rename {} '{}'", e.getClass().getSimpleName(), e.getReason());
    } catch (IOException e) {
      // Nothing we can do about this ...
      LOGGER.trace("Unexpected IOException renaming {}", file1, e);
    }

    for (Path path : Arrays.asList(file2, file1, dir, top)) {
      try {
        java.nio.file.Files.deleteIfExists(path);
      } catch (IOException e) {
        // Ignore ... will delete at shutdown if possible
        LOGGER.trace("Cannot delete {}", path, e);
      }
    }

    return reasons;
  }

  /**
   * Determine the path type from {@link BasicFileAttributes}.
   * @param attributes the {@code BasicFileAttributes} to interpret
   * @return the list of path types; empty if the type cannot be determined
   */
  private static List<String> pathType(BasicFileAttributes attributes) {
    List<String> attrs = new ArrayList<>();
    if (attributes.isDirectory()) attrs.add("dir");
    if (attributes.isRegularFile()) attrs.add("file");
    if (attributes.isSymbolicLink()) attrs.add("link");
    if (attributes.isOther()) attrs.add("other");
    return attrs;
  }

  /**
   * Support class to open or lock a file for file operation impact assessment.
   * <p>
   * Although this class has "support" for opening a directory, there appears to be no reliable
   * means in Java of opening a directory and observing the impact on other file operations.
   */
  private static class PathHolder implements AutoCloseable {
    // Needs a local LOGGER to prevent issues with initialization of Files class.
    private static final Logger LOGGER = LoggerFactory.getLogger(Files.class);
    private final Thread thread;
    private final Phaser barrier = new Phaser(2);

    private AtomicBoolean started = new AtomicBoolean(false);

    public PathHolder(Path path, boolean lock) throws IOException {
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

    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
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

  /**
   * Thrown to indicate that a file copy was attempted outside of the
   * origin {@link FileStore}.
   */
  public static class FileStoreConstraintException extends FileSystemException {
    private static final long serialVersionUID = 1639103038640071760L;

    public FileStoreConstraintException(String file, String other, String reason) {
      super(file, other, reason);
    }
  }

  /**
   * {@code CopyOption} values accepted by {@link Files}.  Use of these options on
   * {@link java.nio.file.Files} method may cause those methods to fail.
   */
  public enum ExtendedOption implements CopyOption {
    /**
     * Recursively copy a directory and its content.
     */
    RECURSIVE,
    /**
     * Indicates that copying source files from different {@code FileStore} instances
     * is not permitted.
     */
    NOSPAN_FILESTORES,
    /**
     * Indicates that, during recursive copy, linked content outside of the source tree should be copied
     * and not linked.
     */
    DEEP_COPY
  }

  /**
   * {@code FileStore} implementation used internally for paths for which a {@code FileStore} cannot
   * be obtained.  On Windows, attempting to get the {@code FileStore} for a path in a SUBST drive
   * fails.
   */
  private static final class UnknownFileStore extends FileStore {

    private final Path root;

    private UnknownFileStore(Path root) {
      this.root = root;
    }

    @Override
    public String name() {
      return "Unknown";
    }

    @Override
    public String type() {
      return "Unknown";
    }

    @Override
    public boolean isReadOnly() {
      /*
       * For a Windows SUBST-based path, Path.isWritable fails because it depends on
       * obtaining the FileStore for the path -- which got us into this substitute FileStore;
       * just make the assumption that it's writable.
       */
      return false;
    }

    @Override
    public long getTotalSpace() {
      return 0L;
    }

    @Override
    public long getUsableSpace() {
      return 0L;
    }

    @Override
    public long getUnallocatedSpace() {
      return 0L;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
      return false;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
      return false;
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
      return null;
    }

    @Override
    public Object getAttribute(String attribute) {
      return null;
    }

    @Override
    public String toString() {
      return "Unknown (" + root + ")";
    }
  }
}
