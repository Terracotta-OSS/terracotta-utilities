/*
 * Copyright 2020-2022 Terracotta, Inc., a Software AG company.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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
import org.terracotta.utilities.exec.Shell;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assume.assumeTrue;
import static org.terracotta.utilities.exec.Shell.execute;

/**
 * Provides the foundation for testing {@link Files}.
 * <p>
 * This class is not designed for parallel execution of its subclasses.
 *
 * <h3>Testing Considerations</h3>
 *
 * For thorough testing, the following directory structures are needed:
 * <pre>{@code
 * [ditaa]
 * ----
 *
 * +----------------------------------------------------------------------------------------------------+
 * |                                                                                                    |
 * |{s}c0AF  Primary FileStore                                                                          |
 * |                                                /------\                                            |
 * |                                                |{o}   |                                            |
 * |                               /----------------| top  |------------\                               |
 * |                               |                |      |            |                               |
 * |                               |                \------/            |                               |
 * |                               |                    |               |                               |
 * |                               |                    |               |                               |
 * |                               V                    V               V                               |
 * |                           /------\             +------+         /------\                           |
 * |                           |{o}   |             | top  |         |{o}   |                           |
 * |                 /---------|  a   |-------\     | File |       /-|  b   |--\                        |
 * |                 |         |      |       |     |      |       | |      |  |                        |
 * |                 |         \------/       |     +------+       | \------/  |                        |
 * |                 |            |           |                    |           |                        |
 * |                 |            |           |                    |           |                        |
 * |                 V            V           V                    V           V                        |
 * |             /------\      +-------+   +------+            /------\     +------+                    |
 * |             |{o}   |      | {c}   |   | {c}  |            |{o}   |     | {c}  |                    |
 * |      /------|  c   |--\   |missing|   |  d   |=---------->|  e   |     | file |=-----------------\ |
 * |      |      |      |  |   | Link  |   |      |         /--|      |--\  | Link |                  | |
 * |      |      \------/  |   +-------+   +------+         |  \------/  |  +------+                  | |
 * |      |         |      \---\   |                    /---/      |     \---\                        | |
 * |      |         |          |   \--=---\             |          |         |                        | |
 * |      |         |          |          |             |          |         |                        | |
 * |      |         |          |          V             |          |         |                        | |
 * |      V         V          V       missing          V          V         V                        | |
 * |  +------+   +------+   +------+                +------+   /------\   +-------+        /------\   | |
 * |  | {c}  |   | {c}  |   | {c}  |                |      |   |{o}   |   | {c}   |        |{o}   |   | |
 * |  |  f   |   |cFile1|   |cFile2|=-------------->| eFile|   |empty |   |dirLink|=------>|common|   | |
 * |  |      |   |      |   |      |                |      |   | Dir  |   |       |        |      |   | |
 * |  +------+   +------+   +------+                +------+   \------/   +-------+        \------/   | |
 * |     :           :                                                                        |       | |
 * |     |           |                                                                        V       | |
 * |     |           |                                                                     +------+   | |
 * |     |           |                                                                     |common|   | |
 * |     |           |                                                                     | File |<--/ |
 * |     |           |                                                                     |      |     |
 * |     |           |                                                                     +------+     |
 * +----------------------------------------------------------------------------------------------------+
 *       :           :
 *       |           |
 *       |           |                                /----------------------------\
 *       |           \----------\                     |                            |
 *       |                      |                     |         LEGEND             |
 *       |    +-------------+   |                     |                            |
 *       |    | {s}cRED     |   |                     |  /------\                  |
 *       |    | Secondary   |   |                     |  |{o}   |                  |
 *       |    | FileStore   |   |                     |  |      | Directory        |
 *       |    |   /------\  |   |                     |  |      |                  |
 *       |    |   |{o}   |  |   |                     |  \------/                  |
 *       \----|=->| fgn  |  |   |                     |                            |
 *            |   |      |  |   |                     |  +------+                  |
 *            |   \------/  |   |                     |  | {c}  |                  |
 *            |      |      |   |                     |  |      | Symbolic Link    |
 *            |      |      |   |                     |  |      |                  |
 *            |      V      |   |                     |  +------+                  |
 *            |   +------+  |   |                     |                            |
 *            |   | fgn  |  |   |                     |  +------+                  |
 *            |   | File |<-|---/                     |  |      |                  |
 *            |   |      |  |                         |  |      | File             |
 *            |   +------+  |                         |  |      |                  |
 *            |             |                         |  +------+                  |
 *            +-------------+                         \----------------------------/
 * ----
 * }</pre>
 *
 * <ul>
 *   <li>{@code top}, {@code common}, and {@code fgn} are disjoint, top-level directories</li>
 *   <li>{@code top} and {@code common} are in the <i>same</i> {@code FileStore} instance</li>
 *   <li>{@code top} and {@code fgn} are in <i>different</i> {@code FileStore} instances</li>
 *   <li>Link {@code fileLink} -&gt; {@code commonFile} is a fully-qualified reference</li>
 *   <li>Link {@code cFile1} -&gt; {@code fgnFile} is a fully-qualified reference</li>
 *   <li>Link {@code cFile2} -&gt; {@code eFile} is a relative reference ({@code ../../d/eFile})</li>
 *   <li>Link {@code d} -&gt; {@code e} is a relative reference ({@code ../../b/e})</li>
 *   <li>Link {@code f} -&gt; {@code fgn} is a fully-qualified reference</li>
 *   <li>Link {@code dirLink} -&gt; {@code common} is a fully-qualified reference</li>
 *   <li>Link {@code missingLink} is <i>dead</i> -- a link to a non-existent object</li>
 *   <li>{@code emptyDir} is an <i>empty</i> directory</li>
 * </ul>
 *
 * The structure involves two (2) {@code java.nio.file.FileStore} instances.  Reliably identifying two
 * {@code FileStore} instances to use for testing is a challenge -- assuming they exist at all.  The <i>default</i>
 * {@code java.nio.file.FileSystem} is the only {@code FileSystem} that can be easily identified and, on
 * Windows and Linux, is likely to be the only "standard" {@code FileSystem} available.
 * <p>
 * On Windows, the {@code FileSystem.getFileStores} method will return multiple {@code FileStore} instances only
 * if more than one drive is online, e.g. a {@code C} and {@code D} drive are available.  And being able to
 * write in a directory that's not {@code user.home}, {@code user.dir}, or {@code java.io.tmpdir} is not guaranteed.
 * <p>
 * Even if multiple, <i>writable</i> {@code FileStore} instances are available, this structure <b>cannot</b> be
 * created on Windows <i>unless</i>:
 *
 * <ol>
 *   <li>Tests are run under an administrator account, or</li>
 *   <li>The <b>Create symbolic links</b> permission has been granted. (See below.)</li>
 * </ol>
 *
 * Under Linux, {@code FileSystem.getFileStores} returns only one {@code FileStore} -- the one for {@code /}.
 * There is no way, using {@code java.nio.files} classes, to determine the mount points in the Linux
 * file system -- neither {@code FileSystem.getFileStores} nor {@code FileSystem.getRootDirectories}
 * identifies mount points.  But, if you refer to a mounted directory and call
 * {@code Files.getFileStore(Path)}, you get a separate {@code FileStore} instance for that mount.
 * It is possible, likely even, that {@code java.io.tmpdir} refers to a mount point
 *
 * <h4>Symbolic Link Handling</h4>
 *
 * For complete testing of the handling of local tree, symbolic link, and {@code FileStore} considerations, the
 * following links are needed:
 *
 * <ul>
 * <li>Link to a file within the <i>source</i> directory tree  ({@code top/a/c/cFile2} -&gt; {@code ../../d/eFile}).</li>
 * <li>Link to a file outside the <i>source</i> directory tree but within the <i>source</i> {@code FileStore}
 *     ({@code top/b/fileLink} -&gt; {@code common/commonFile}).</li>
 * <li>Link to a file outside the <i>source</i> {@code FileStore}  ({@code top/a/c/cFile1} -&gt; {@code fgn/fgnFile}).</li>
 * <li>Link to a directory within the <i>source</i> directory tree  ({@code top/a/d} -&gt; {@code ../../b/e}).</li>
 * <li>Link to a directory outside the <i>source</i> directory tree but within the <i>source</i> {@code FileStore}
 *     ({@code top/b/e/dirLink} -&gt; {@code common}).</li>
 * <li>Link to a directory outside the <i>source</i> {@code FileStore}  ({@code top/a/c/f} -&gt; {@code fgn}).</li>
 * </ul>
 *
 * (<i>source</i> in the above list refers to the source {@code Path} provided to the {@code copy} method.)
 *
 * <h5>Windows</h5>
 *
 * Yes, Virginia, Windows <i>does</i> have symbolic link support!
 * <p>
 * By default, <b>administrator privileges</b> are needed to be able to create a symbolic link under Windows.
 * This permission can be set using the local security policy by granting <b>Create symbolic links</b>
 * permission to the test user under
 * <i>Security Settings / Local Policies / User Rights Assignment</i> reached using {@code secpol.msc} using elevated
 * rights.  (The procedure is described in
 * <a href="https://stackoverflow.com/a/24353758/1814086">How to create Soft symbolic Link using java.nio.Files</a>.)
 * Once permitted, a symbolic link can be created using the {@code MKLINK} command or through programmatic
 * means like {@link java.nio.file.Files#createSymbolicLink}.
 * <p>
 * Under Windows, there are different forms of symbolic link for a link to a directory versus a file.
 * The Java {@code Files.createSymbolicLink} function does the right thing if the link target exists when
 * the method call is made.  However, if the link target does not exist, {@code createSymbolicLink} always
 * creates a <i>file</i> symbolic link.  Attempting to access a directory through a file symbolic link
 * results in an {@code AccessDeniedException} with no further indication of the fault.  To get around this
 * problem while copying a directory tree, creating a directory link to a no-yet-copied directory
 * requires temporarily creating the missing directory path.
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
     *
     * Some Java path support methods don't operate as expected in the presence of
     * Windows 8dot3 short names so, under Windows, we try to get the test root
     * folder to be in 8dot3 form if it's not already.  Path.toRealPath(...)
     * returns a long file name.
     */
    root = null;
    if (isWindows) {
      Path testRoot = TEST_ROOT.getRoot().toPath();
      if (testRoot.equals(testRoot.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
        /*
         * TEST_ROOT does not have an 8dot3 component ... attempt to make one.
         * If the drive on which TEST_ROOT resides does not support 8dot3 names,
         * the 'FOR ... ECHO %~sF' statement returns the long name instead of the
         * 8dot3 name.
         */
        Path rootCandidate = TEST_ROOT.newFolder("rootWithLongName").toPath();
        LOGGER.debug("Attempting to produce an 8dot3 root from \"{}\"", rootCandidate);
        Shell.Result result = execute(Shell.Encoding.CHARSET, "cmd", "/C",
            "@FOR /F \"delims=\" %F IN (\"" + rootCandidate.toString() + "\") DO @ECHO %~sF");
        if (result.exitCode() == 0) {
          try {
            Path eightDot3Path = Paths.get(result.lines().get(0));
            if (eightDot3Path.equals(rootCandidate)) {
              LOGGER.warn("\n******************************************************************************\n" +
                      "8dot3 support is not available for drive {}\n" +
                      "******************************************************************************",
                  eightDot3Path.getRoot());
            } else {
              root = eightDot3Path;                                 // root
              LOGGER.info("Using 8dot3 root of \"{}\"", root);
            }
          } catch (Exception e) {
            LOGGER.warn("Failed to determine 8dot3 from \"{}\"", rootCandidate, e);
            // dealt with below
          }
        }
      } else {
        LOGGER.debug("Using 8dot3 base of \"{}\"", testRoot);
      }
    }
    if (root == null) {
      root = TEST_ROOT.newFolder("root").toPath();               // root
    }
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
      LOGGER.info("Creation of symbolic links is supported in this environment");
    } catch (UnsupportedOperationException | SecurityException | FileSystemException ex) {
      symlinksSupported = false;
      if ((ex instanceof FileSystemException) && ((FileSystemException)ex).getReason() == null) {
        /*
         * The failure if symbolic links aren't enabled in Windows is a non-specific FileSystemException;
         * non-specific FileSystemExceptions have a Windows-locale-derived reason.
         */
        throw ex;
      }
      LOGGER.warn("\n******************************************************************************\n" +
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
    LOGGER.debug("[foreignCheck] Checking foreign FileStore candidates...");
    Path foreignDirectory = null;
    for (Path foreignPath : foreignCandidates) {
      LOGGER.debug("[foreignCheck] Checking \"{}\"", foreignPath);
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
      LOGGER.warn("\n******************************************************************************\n" +
          "[foreignCheck] No suitable directory found for foreign FileStore testing; suppressed\n" +
          "******************************************************************************");
      foreignFileStoreAvailable = false;
    } else {
      LOGGER.info("[foreignCheck] Using \"{}\" for foreign FileStore testing", foreignDirectory);
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

  protected static Path makeFile(Path path, Iterable<String> lines) throws IOException {
    createFile(path);
    return write(path, lines, UTF_8);
  }

  protected static Path makeDirectory(Path path) throws IOException {
    return java.nio.file.Files.createDirectory(path);
  }

  protected static Path makeFileSymlink(Path filePath, Iterable<String> lines) throws IOException {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path linkTarget = TEST_ROOT.newFile().toPath();
    write(linkTarget, lines, UTF_8);
    return createSymbolicLink(filePath, linkTarget);
  }

  protected static Path makeDirectorySymlink(Path dirPath) throws IOException {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    return createSymbolicLink(dirPath, TEST_ROOT.newFolder().toPath());
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

    private final AtomicBoolean started = new AtomicBoolean(false);

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

    public Duration getHoldTime() {
      return holdTime;
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
    private final Set<String> attributeViews;
    private final String fileStoreType;
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
      FileStore fileStore = getFileStore((java.nio.file.Files.exists(path) ? path : root));
      this.fileStoreType = fileStore.type();
      this.attributeViews = supportedAttributeViews(path, fileStore);

      if (path.startsWith(root)) {
        this.path = root.relativize(path);
      } else {
        this.path = path;
      }
      LinkOption[] linkOptions = (followLinks ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS });

      Set<FileType> types = EnumSet.noneOf(FileType.class);

      BasicFileAttributes attrs = null;

      /*
       * While Linux may, in general, support the "dos" view, NFS mounted file stores do not.
       * Unfortunately, getFileAttributeView returns an in-effective "dos" view which, when accessed,
       * throws a FileSystemException "hiding" an ENOTSUP error code ("Operation not supported").
       * Checking the FileStore support for the "dos" view avoids this issue.
       */
      DosFileAttributeView dosFileAttributeView = getFileAttributeView(path, DosFileAttributeView.class, linkOptions);
      if (dosFileAttributeView != null && this.attributeViews.contains("dos")) {
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
      if (posixFileAttributeView != null && attributeViews.contains("posix")) {
        Set<PosixFilePermission> permissions;
        try {
          PosixFileAttributes posixFileAttributes = posixFileAttributeView.readAttributes();
          permissions = posixFileAttributes.permissions();
        } catch (NoSuchFileException e) {
          // A broken link can cause a NoSuchFileException
          permissions = Collections.emptySet();
          if (attrs == null) {
            BasicFileAttributes basicFileAttributes = readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (basicFileAttributes.isSymbolicLink()) {
              // Broken link ...
              types.add(FileType.LINK);
              attrs = basicFileAttributes;
            }
          }
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
       * If both are directories whether one or both are links, they're equal.
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

          // If both files reside in the same type of file store, compare the last modified time;
          // if in different file stores, can't make reliable assertions regarding time.
          boolean lastModifiedTimeEqual;
          if (this.fileStoreType.equals(that.fileStoreType)) {
            // Windows, at least, doesn't copy lastModifiedTime for non-files
            lastModifiedTimeEqual =
                !this.types.contains(FileType.FILE) || equalsWithinCommonResolution(this.lastModifiedTime, that.lastModifiedTime);
          } else {
            LOGGER.trace("Skipping time comparison: {} != {}", this.fileStoreType, that.fileStoreType);
            lastModifiedTimeEqual = true;   // Don't fail if file stores are different
          }

          // If both files reside in a file store supporting "dos", compare DOS attributed
          boolean dosAttributesEqual;
          if (this.attributeViews.contains("dos") && that.attributeViews.contains("dos")) {
            dosAttributesEqual = Objects.equals(this.isReadOnly, that.isReadOnly)
                && Objects.equals(this.isSystem, that.isSystem)
                && Objects.equals(this.isHidden, that.isHidden)
                && Objects.equals(this.isArchive, that.isArchive);
          } else {
            LOGGER.trace("Skipping 'dos' attributes comparison: {} / {}", this.attributeViews, that.attributeViews);
            dosAttributesEqual = true;    // Don't fail if file stores are different
          }

          // If both files reside in a file store supporting "posix", compare Posix attributes
          boolean posixAttributesEqual;
          if (this.attributeViews.contains("posix") && that.attributeViews.contains("posix")) {
            posixAttributesEqual = Objects.equals(this.permissions, that.permissions);
          } else {
            LOGGER.trace("Skipping 'posix' attributes comparison: {} / {}", this.attributeViews, that.attributeViews);
            posixAttributesEqual = true;    // Don't fail if file stores are different
          }

          return lastModifiedTimeEqual && dosAttributesEqual && posixAttributesEqual;
        }
      }

      return true;
    }

    /**
     * Tests the equality of two {@link FileTime} instances to the lowest resolution common between the two values.
     * @param a the first {@code FileTime} to compare
     * @param b the second {@code FileTime} to compare
     * @return {@code true} if {@code a} and {@code b} are equal within the lowest common resolution
     */
    private static boolean equalsWithinCommonResolution(FileTime a, FileTime b) {
      if (Objects.equals(a, b)) {
        return true;
      } else {
        TemporalUnit aResolution = calculateResolution(a);
        TemporalUnit bResolution = calculateResolution(b);
        Instant aInstant = a.toInstant();
        Instant bInstant = b.toInstant();
        int aVsB = Comparator.comparing(TemporalUnit::getDuration).compare(aResolution, bResolution);
        if (aVsB < 0) {
          // b has a lower resolution than a; truncate to b's resolution
          aInstant = aInstant.truncatedTo(bResolution);
        } else if (aVsB > 0) {
          // a has a lower resolution than b; truncate to a's resolution
          bInstant = bInstant.truncatedTo(aResolution);
        }
        return Objects.equals(aInstant, bInstant);
      }
    }

    /**
     * Timestamp resolution for NTFS.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/sysinfo/file-times">File Times</a>
     */
    private static final TemporalUnit NTFS_RESOLUTION = new TemporalUnit() {
      private final Duration duration = Duration.ofNanos(100L);

      @Override
      public Duration getDuration() {
        return duration;
      }

      @Override
      public boolean isDurationEstimated() {
        return false;
      }

      @Override
      public boolean isDateBased() {
        return false;
      }

      @Override
      public boolean isTimeBased() {
        return true;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <R extends Temporal> R addTo(R temporal, long amount) {
        return (R) temporal.plus(amount, this);
      }

      @Override
      public long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive) {
        return temporal1Inclusive.until(temporal2Exclusive, this);
      }

      @Override
      public String toString() {
        return "NTFS";
      }
    };

    /**
     * Ordered set of <i>supported</i> {@link FileTime} resolutions.
     */
    private static final Set<TemporalUnit> RESOLUTIONS;
    static {
      // Ordered as required for testing/discovery
      Set<TemporalUnit> resolutions = new LinkedHashSet<>();
      resolutions.add(ChronoUnit.SECONDS);
      resolutions.add(ChronoUnit.MILLIS);
      resolutions.add(ChronoUnit.MICROS);
      resolutions.add(NTFS_RESOLUTION);
      resolutions.add(ChronoUnit.NANOS);
      RESOLUTIONS = Collections.unmodifiableSet(resolutions);
    }

    /**
     * Calculates the resolution of the {@code FileTime} provided.
     * @param fileTime the {@code FileTime} for which the resolution is determined
     * @return the {@code TemporalUnit} describing the resolution of {@code fileTime}
     */
    private static TemporalUnit calculateResolution(FileTime fileTime) {
      Instant instant = fileTime.toInstant();
      for (TemporalUnit unit : RESOLUTIONS) {
        if (instant.equals(instant.truncatedTo(unit))) {
          return unit;
        }
      }
      return ChronoUnit.MINUTES;      // Use MINUTES if no other resolution fits
    }

    /**
     * Determine what {@code FileAttributeView} types are supported for a path.
     *
     * @param fullPath  the fully-qualified path to interrogate
     * @param fileStore the {@code FileStore} holding {@code fullPath}
     * @return the set of supported views
     */
    private Set<String> supportedAttributeViews(Path fullPath, FileStore fileStore) {
      return fullPath.getFileSystem().supportedFileAttributeViews().stream()
          .filter(fileStore::supportsFileAttributeView)
          .collect(collectingAndThen(toSet(), Collections::unmodifiableSet));
    }

    @Override
    public String toString() {
      return "PathDetails{" +
          "path=" + path +
          ", fileStoreType=" + fileStoreType +
          ", attributeViews=" + attributeViews +
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


