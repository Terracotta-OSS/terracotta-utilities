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

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.getFileAttributeView;
import static java.nio.file.Files.getFileStore;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.readAttributes;
import static java.nio.file.Files.readSymbolicLink;
import static java.nio.file.Files.write;
import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;

/**
 * Provides the foundation for testing {@link Files}.
 * <p>
 * This class is not designed for parallel execution of its subclasses.
 */
public abstract class FilesTestBase {

  private static final Logger LOGGER = LoggerFactory.getLogger(FilesTestBase.class);

  @ClassRule
  public static final TemporaryFolder TEST_ROOT = new TemporaryFolder();

  @Rule
  public final TestName testName = new TestName();

  protected static final boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  protected static boolean symlinksSupported;
  protected static boolean foreignFileStoreAvailable;
  protected static Path foreignDir;
  protected static Path foreignFile;

  protected static Path root;
  protected static Path common;
  protected static Path commonFile;
  protected static Path sourceTree;

  protected Path top;
  protected Path topFile;
  protected Path eFile;
  protected Path emptyDir;
  protected Path bSide;
  protected Path dirLink = null;
  protected Path fileLink = null;
  protected Path missingLink = null;
  protected Path foreignDirLink = null;
  protected Path foreignFileLink = null;


  /**
   * Creates test file system elements in common to all tests.
   */
  @BeforeClass
  public static void prepareCommon() throws IOException {
    /*
     * Create the root directory that contains both the source tree and the target for each test.
     */
    root = TEST_ROOT.newFolder("root").toPath();               // root
    sourceTree = createDirectory(root.resolve("source"));

    /*
     * A directory structure in the same FileStore as the test source but not in the
     * same top-level directory tree.
     */
    common = createDirectory(root.resolve("common"));               // root/same
    commonFile = createFile(common.resolve("commonFile"));          // root/same/sameFile
    write(commonFile, singleton("commonFile"), UTF_8);

    /*
     * Symbolic links aren't supported in all environments -- like Windows where standard
     * user accounts are involved.  Determine if the current execution environment has
     * symbolic link support.
     */
    symlinksSupported = true;
    try {
      Path testLink = createSymbolicLink(root.resolve("testLink"), root.resolve("phantom"));
      java.nio.file.Files.delete(testLink);
      LOGGER.info("******************************************************************************\n" +
          "Creation of symbolic links is supported in this environment\n" +
          "******************************************************************************");
    } catch (UnsupportedOperationException | SecurityException | FileSystemException ex) {
      symlinksSupported = false;
      if ((ex instanceof FileSystemException) && ((FileSystemException)ex).getReason() == null) {
        /*
         * The failure if symbolic links aren't enabled in Windows is a non-specific FileSystemException;
         * non-specific FileSystemExceptions have a Windows-locale-derived reason.
         */
        throw ex;
      }
      LOGGER.warn("******************************************************************************\n" +
          "Creation of symbolic links is NOT supported in this environment\n" +
          "******************************************************************************");
    }

    /*
     * Find a path in a "foreign" FileStore.
     *
     * Unfortunately, the FileStore instances in a FileSystem are not enumerable.
     * Neither does a FileStore identify its file system roots -- so we're left with a
     * guessing game to find a usable path in a "foreign" FileStore.  On some systems, the
     * 'java.io.tmpdir' is in a different FileStore than 'user.home' so we may have some luck
     * there.
     *
     * In general, we're looking for a directory from a different FileStore than 'root'.  Because
     * relying solely on the content of a FileSystem root directory can be problematic, we want to
     * be able to create a directory (and a file) in any candidate we probe.
     *
     * Failing to find a "foreign" FileStore does not fail testing -- we just skip a few.
     */
    foreignFile = null;
    foreignDir = null;
    FileStore rootFileStore = getFileStore(root);
    List<Path> foreignCandidates = new ArrayList<>(Arrays.asList(
        Paths.get(System.getProperty("java.io.tmpdir")),
        Paths.get(System.getProperty("user.dir")),
        Paths.get(System.getProperty("user.home"))));
    FileSystems.getDefault().getRootDirectories().forEach(foreignCandidates::add);
    LOGGER.info("******************************************************************************\n" +
        "[foreignCheck] Checking foreign FileStore candidates...\n" +
        "******************************************************************************");
    Path foreignDirectory = null;
    for (Path foreignPath : foreignCandidates) {
      LOGGER.info("[foreignCheck] Checking \"{}\"", foreignPath);
      try {
        FileStore foreignFileStore = getFileStore(foreignPath);
        if (!foreignFileStore.equals(rootFileStore)) {
          if (!foreignFileStore.isReadOnly()) {
            try {
              foreignDirectory = java.nio.file.Files.createTempDirectory(foreignPath, "delete_me_");
              foreignDirectory.toFile().deleteOnExit();

              Path testFile = createFile(foreignDirectory.resolve("foreignFile"));
              testFile.toFile().deleteOnExit();

              write(testFile, singleton("foreignFile"), UTF_8);
              foreignFile = testFile;
              break;
            } catch (IOException ex) {
              LOGGER.warn("[foreignCheck] Path \"{}\" failed", foreignPath, ex);
            }
          }
        }
      } catch (IOException e) {
        // Mountable devices throw FileSystemException when nothing is in the drive
        LOGGER.warn("[foreignCheck] FileSystem root \"{}\" not checked", foreignPath, e);
      }
    }

    if (foreignFile == null) {
      LOGGER.warn("******************************************************************************\n" +
          "[foreignCheck] No suitable directory found for foreign FileStore testing; suppressed\n" +
          "******************************************************************************");
      foreignFileStoreAvailable = false;
    } else {
      LOGGER.info("******************************************************************************\n" +
          "[foreignCheck] Using \"{}\" for foreign FileStore testing\n" +
          "******************************************************************************", foreignDirectory);
      foreignFileStoreAvailable = true;
      foreignDir = foreignFile.getParent();
    }
  }

  @AfterClass
  public static void deleteForeign() {
    if (foreignFile != null) {
      try {
        java.nio.file.Files.deleteIfExists(foreignFile);
      } catch (IOException e) {
        // Ignore
      }
      foreignFile = null;
    }
    if (foreignDir != null) {
      try {
        java.nio.file.Files.deleteIfExists(foreignDir);
      } catch (IOException e) {
        // Ignore
      }
      foreignDir = null;
    }

    symlinksSupported = false;
    foreignFileStoreAvailable = false;

    sourceTree = null;
    commonFile = null;
    common = null;
    root = null;
  }

  /**
   * Prepares the test source tree.
   *
   * @see "common/utilities/src/main/java/com/terracottatech/utilities/Files#copy.adoc"
   */
  @Before
  public void prepareSourceTree() throws IOException {

    /*
     * Create the source tree -- at least the "non-foreign" parts of it.
     */
    top = createDirectory(sourceTree.resolve(testName.getMethodName()));  // root/top
    Path a = createDirectory(top.resolve("a"));                     // root/top/a
    bSide = createDirectory(top.resolve("b"));                     // root/top/b

    topFile = createFile(top.resolve("topFile"));                   // root/top/topFile
    write(topFile, singleton("topFile"), UTF_8);

    Path e = createDirectory(bSide.resolve("e"));                   // root/top/b/e
    eFile = createFile(e.resolve("eFile"));                         // root/top/b/e/eFile
    write(eFile, singleton("eFile"), UTF_8);
    emptyDir = createDirectory(e.resolve("emptyDir"));              // root/top/b/e/emptyDir

    Path c = createDirectory(a.resolve("c"));                       // root/top/a/c

    /*
     * Create the symbolic links in the tree.  In some environments (Windows default user accounts),
     * this can't be done.  It'll limit the effectiveness of some of the tests but a failure here
     * doesn't mean test failures.
     */
    if (symlinksSupported) {
      dirLink = createSymbolicLink(e.resolve("dirLink"), common);         // root/top/b/e/dirLink -> root/common
      fileLink = createSymbolicLink(bSide.resolve("fileLink"), commonFile);   // root/top/b/fileLink -> root/same/commonFile
      missingLink = createSymbolicLink(a.resolve("missingLink"), top.resolve("missing"));   // root/top/a/h -> /root/top/missing
      createSymbolicLink(a.resolve("d"), a.relativize(e));            // root/top/a/d -> ../b/e
      createSymbolicLink(c.resolve("cFile2"), c.relativize(eFile));   // root/top/a/c/cFile2 -> ../../../b/e/eFile

      if (foreignFileStoreAvailable) {
        /*
         * Create links to content outside the FileStore of root/top.
         */
        foreignDirLink = createSymbolicLink(c.resolve("f"), foreignDir);            // root/top/a/c/f -> foreignDir
        foreignFileLink = createSymbolicLink(c.resolve("cFile1"), foreignFile);     // root/top/a/c/cFile1 -> foreignFile
      }
    }
  }


  /**
   * Returns a depth-first traversal of the directory path provided.  By default, this is a
   * no-follow-links traversal.
   * @param dir the directory to traverse
   * @param options {@code FileVisitOption}s to modify the traversal
   * @return a list of the paths encountered
   * @throws IOException if an error is encountered while traversing the tree
   * @see java.nio.file.Files#walk(Path, FileVisitOption...)
   */
  protected static List<Path> walkedTree(Path dir, FileVisitOption... options) throws IOException {
    try (Stream<Path> stream = java.nio.file.Files.walk(dir, options)) {
      return stream.collect(Collectors.toList());
    }
  }

  /**
   * Gets the path of a file directly contained in the specified directory.
   * @param dir the directory to search for a file
   * @return the file found
   * @throws IOException if an error is encountered while reading the directory
   * @throws AssertionError if no file is found
   */
  protected static Path childFile(Path dir) throws IOException {
    try (Stream<Path> dirStream = java.nio.file.Files.list(dir)) {
      return dirStream.filter(p -> isRegularFile(p)).limit(1).findAny().orElseThrow(AssertionError::new);
    }
  }

  protected static List<String> pathType(BasicFileAttributes attributes) {
    List<String> attrs = new ArrayList<>();
    if (attributes.isDirectory()) attrs.add("dir");
    if (attributes.isRegularFile()) attrs.add("file");
    if (attributes.isSymbolicLink()) attrs.add("link");
    if (attributes.isOther()) attrs.add("other");
    return attrs;
  }

  /**
   * Holds a file open for a fixed period of time or until the {@code PathHolder} is closed.
   */
  protected static final class PathHolder implements AutoCloseable {
    // Needs a local LOGGER to prevent issues with initialization of Files class.
    private static final Logger LOGGER = LoggerFactory.getLogger(Files.class);
    private static final Duration NONE = Duration.ofSeconds(Long.MAX_VALUE, 999999999);
    private final Thread thread;
    private final Duration holdTime;
    private final Phaser barrier = new Phaser(2);

    private AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Creates a {@code PathHolder} to hold a file open until the {@code PathHolder} instance is closed.
     * @param path the file to hold open
     * @throws IOException if unable to read the attributes of the file
     */
    public PathHolder(Path path) throws IOException {
      this(path, NONE);
    }

    /**
     * Creates a {@code PathHolder} to hold a file open until the {@code PathHolder} instance is closed or a
     * fixed time period elapses.
     * @param path the file to hold open
     * @throws IOException if unable to read the attributes of the file
     */
    public PathHolder(Path path, Duration holdTime) throws IOException {
      requireNonNull(path, "path");
      this.holdTime = requireNonNull(holdTime, "holdTime");

      BasicFileAttributes attr = java.nio.file.Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      Runnable holder;
      if (attr.isRegularFile()) {
        holder = () -> holdFile(path);
      } else if (attr.isDirectory()) {
        holder = () -> holdDirectory(path);
      } else {
        throw new AssertionError("Cannot handle path of type " + pathType(attr) + " - " + path);
      }

      this.thread = new Thread(holder, "FilesTestBase$PathHolder - " + path);
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
        if (holdTime.equals(NONE)) {
          barrier.arriveAndAwaitAdvance();
        } else {
          barrier.awaitAdvanceInterruptibly(barrier.arrive(), holdTime.toNanos(), TimeUnit.NANOSECONDS);
        }
      } catch (TimeoutException e) {
        // Done waiting
      } catch (IOException | InterruptedException e) {
        LOGGER.warn("Error attempting to hold \"{}\"", file, e);
        throw new AssertionError(e);
      } finally {
        barrier.arriveAndDeregister();
      }
      LOGGER.trace("Hold ended on {}", file);
    }

    /**
     * Attempts to hold a directory open for read.  This is not yet reliably accomplished.
     * @param dir the directory path to hold open
     */
    // TODO: Make this work ...
    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    private void holdDirectory(Path dir) {
      LOGGER.trace("Hold on \"{}\" beginning", dir);
      try (DirectoryStream<Path> directoryStream = java.nio.file.Files.newDirectoryStream(dir)) {
        for (Path ignored : directoryStream) {
          barrier.arriveAndAwaitAdvance();
          // Hold path open so others can observe the state
          if (holdTime.equals(NONE)) {
            barrier.arriveAndAwaitAdvance();
          } else {
            barrier.awaitAdvanceInterruptibly(barrier.arrive(), holdTime.toNanos(), TimeUnit.NANOSECONDS);
          }
          break;
        }
      } catch (TimeoutException e) {
        // Done waiting
      } catch (IOException | InterruptedException e) {
        LOGGER.warn("Error attempting to hold \"{}\"", dir, e);
        throw new AssertionError(e);
      } finally {
        barrier.arriveAndDeregister();
      }
      LOGGER.trace("Hold ended on {}", dir);
    }

    /**
     * Starts the file hold.
     */
    public void start() {
      thread.setDaemon(true);
      thread.start();
      started.set(true);
      barrier.arriveAndAwaitAdvance();
      // path is now 'open' ...
    }

    /**
     * Terminates the file hold.
     */
    @Override
    public void close() {
      if (started.compareAndSet(true, false)) {
        barrier.arriveAndDeregister();
        try {
          thread.join();
        } catch (InterruptedException e) {
          throw new AssertionError("Interrupted awaiting completion of " + thread.getName(), e);
        }
      }
    }
  }

  static class PathDetails {
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    protected enum FileType {
      FILE,
      DIRECTORY,
      LINK,
      OTHER
    }
    private final Path path;
    private final Set<FileType> types;
    private final long size;
    private final FileTime creationTime;
    private final FileTime lastAccessTime;
    private final FileTime lastModifiedTime;

    // DOS properties
    private final Boolean isReadOnly;
    private final Boolean isSystem;
    private final Boolean isHidden;
    private final Boolean isArchive;

    // POSIX properties
    private final Set<PosixFilePermission> permissions;

    // Symbolic link properties
    private final Path linkTarget;

    public PathDetails(Path root, Path path, boolean followLinks) throws IOException {
      if (path.startsWith(root)) {
        this.path = root.relativize(path);
      } else {
        this.path = path;
      }
      LinkOption[] linkOptions = (followLinks ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS });

      Set<FileType> types = EnumSet.noneOf(FileType.class);

      BasicFileAttributes attrs = null;

      DosFileAttributeView dosFileAttributeView = getFileAttributeView(path, DosFileAttributeView.class, linkOptions);
      if (dosFileAttributeView != null) {
        Boolean isReadOnly, isSystem, isHidden, isArchive;
        try {
          DosFileAttributes dosAttrs = dosFileAttributeView.readAttributes();
          isReadOnly = dosAttrs.isReadOnly();
          isSystem = dosAttrs.isSystem();
          isHidden = dosAttrs.isHidden();
          isArchive = dosAttrs.isArchive();
          attrs = dosAttrs;
        } catch (FileSystemException e) {
          // A "broken" link can cause a NoSuchFileException.
          // While Linux supports DosFileAttributes for files, it does not support reading those attributes of a symlink.
          BasicFileAttributes basicFileAttributes = readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (basicFileAttributes.isSymbolicLink()) {
            // Broken link ...
            types.add(FileType.LINK);
            isReadOnly = null;
            isSystem = null;
            isHidden = null;
            isArchive = null;
            attrs = basicFileAttributes;
          } else {
            throw e;
          }
        }
        this.isReadOnly = isReadOnly;
        this.isSystem = isSystem;
        this.isHidden = isHidden;
        this.isArchive = isArchive;

        if (!followLinks) {
          /*
           * Windows directory links are special.
           */
          try {
            Integer fileAttributes = (Integer) java.nio.file.Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            if ((fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
              types.add(FileType.DIRECTORY);    // Windows directory links are special
            }
          } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            LOGGER.debug("Unable to read 'dos:attributes' for \"{}\"", path, e);
          }
        }
      } else {
        this.isReadOnly = null;
        this.isSystem = null;
        this.isHidden = null;
        this.isArchive = null;
      }

      PosixFileAttributeView posixFileAttributeView = getFileAttributeView(path, PosixFileAttributeView.class, linkOptions);
      if (posixFileAttributeView != null) {
        Set<PosixFilePermission> permissions;
        try {
          PosixFileAttributes posixFileAttributes = posixFileAttributeView.readAttributes();
          permissions = posixFileAttributes.permissions();
        } catch (NoSuchFileException e) {
          // A broken link can cause a NoSuchFileException
          permissions = Collections.emptySet();
        }
        this.permissions = permissions;
      } else {
        this.permissions = Collections.emptySet();
      }

      if (attrs == null) {
        attrs = readAttributes(path, BasicFileAttributes.class, linkOptions);
      }

      if (attrs.isRegularFile()) {
        types.add(FileType.FILE);
      }
      if (attrs.isDirectory()) {
        types.add(FileType.DIRECTORY);
      }
      if (attrs.isSymbolicLink()) {
        types.add(FileType.LINK);
      }
      if (attrs.isOther()) {
        types.add(FileType.OTHER);
      }

      if (attrs.isRegularFile()) {
        this.size = attrs.size();
      } else {
        this.size = 0L;
      }

      this.types = types;
      this.creationTime = attrs.creationTime();
      this.lastAccessTime = attrs.lastAccessTime();
      this.lastModifiedTime = attrs.lastModifiedTime();

      if (types.contains(FileType.LINK)) {
        Path linkTarget = readSymbolicLink(path);
        if (linkTarget.startsWith(root)) {
          linkTarget = root.relativize(linkTarget);
        }
        this.linkTarget = linkTarget;
      } else {
        this.linkTarget = null;
      }
    }

    /**
     * Compares {@code this} {@code PathDetails} instance with another.
     * @param that the {@code PathDetails} instance with which a comparision is made
     * @param compareAttributes if {@code true}, last modified time, permissions (if any)
     *          and file attributes (if any) are included in the comparison
     * @return {@code true} if {@code that} and {@code this} are mirror of each other
     */
    public boolean mirrors(PathDetails that, boolean compareAttributes) {
      if (that == null) {
        return false;
      } else if (that == this) {
        return true;
      }

      LOGGER.trace("Comparing {}\n    to {}", this, that);

      if (this.path.compareTo(that.path) != 0) {
        return false;
      }

      /*
       * If both are directories whether or not one or both are links, they're equal.
       * Otherwise, check the full complement.
       */
      if (!this.types.contains(FileType.DIRECTORY) || !that.types.contains(FileType.DIRECTORY)) {
        if (!this.types.equals(that.types)) {
          return false;
        }
      }

      if (this.types.contains(FileType.LINK)) {
        return this.linkTarget.compareTo(that.linkTarget) == 0;
      } else {
        if (this.size != that.size) {
          return false;
        }
        if (compareAttributes) {
          // Windows, at least, doesn't copy lastModifiedTime for non-files
          return (!this.types.contains(FileType.FILE) || Objects.equals(this.lastModifiedTime, that.lastModifiedTime))
              && Objects.equals(this.isReadOnly, that.isReadOnly)
              && Objects.equals(this.isSystem, that.isSystem)
              && Objects.equals(this.isHidden, that.isHidden)
              && Objects.equals(this.isArchive, that.isArchive)
              && Objects.equals(this.permissions, that.permissions);
        }
      }

      return true;
    }

    @Override
    public String toString() {
      return "PathDetails{" +
          "path=" + path +
          ", types=" + types +
          ", size=" + size +
          ", isReadOnly=" + isReadOnly +
          ", isSystem=" + isSystem +
          ", isHidden=" + isHidden +
          ", isArchive=" + isArchive +
          ", permissions=" + permissions +
          ", linkTarget=" + linkTarget +
          ", creationTime=" + creationTime +
          ", lastAccessTime=" + lastAccessTime +
          ", lastModifiedTime=" + lastModifiedTime +
          '}';
    }
  }
}


