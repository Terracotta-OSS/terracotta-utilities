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

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isSameFile;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the {@link Files} {@code rename} methods.
 */
public class FilesRenameTest extends FilesTestBase {

  private Path target;

  @Before
  public void prepareTarget() throws IOException {
    target = createDirectory(root.resolve("target_" + testName.getMethodName()));  // root/target
  }


  @Test
  public void testNullPathOrigin() throws Exception {
    try {
      Files.rename(null, top.resolveSibling("other"));
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNullPathTarget() throws Exception {
    try {
      Files.rename(top, null);
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNonExistentPath() throws Exception {
    try {
      Files.rename(root.resolve("missing"), root.resolve("other"));
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
  }

  @Test
  public void testFileRenameWindows() throws Exception {
    assumeTrue(isWindows);
    int[] helperCalls = new int[1];
    Path newName = target.resolve("newName");
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      Files.rename(topFile, newName, Duration.ofMillis(100), () -> helperCalls[0]++);
      fail("Expecting FileSystemException");
    } catch (FileSystemException e) {
      // expected
    }
    assertFalse(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertThat(helperCalls[0], greaterThan(0));

    Path renamedPath = Files.rename(topFile, newName);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testFileRenameLinux() throws Exception {
    assumeFalse(isWindows);
    Path newName = target.resolve("newName");
    Path renamedPath = Files.rename(topFile, newName);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testFileRenameRelative() throws Exception {
    Path newName = Paths.get("newName");
    Path expectedNewName = topFile.resolveSibling("newName");
    try {
      Path renamedPath = Files.rename(topFile, newName);
      assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(renamedPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue((isSameFile(expectedNewName, renamedPath)));
    } finally {
      java.nio.file.Files.deleteIfExists(expectedNewName);
    }
  }

  @Test
  public void testFileRenameInTime() throws Exception {
    Path newName = target.resolve("newName");
    Path renamedPath;
    try (PathHolder holder = new PathHolder(topFile, Duration.ofMillis(100))) {
      holder.start();
      Thread.currentThread().interrupt();
      renamedPath = Files.rename(topFile, newName, holder.getHoldTime());
      assertTrue(Thread.interrupted());
    }
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testFileLinkRename() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path linkedFile = java.nio.file.Files.readSymbolicLink(fileLink);

    Path newName = target.resolve("newName");
    Path renamedPath;
    // A busy link target should not affect link deletion
    try (PathHolder holder = new PathHolder(linkedFile)) {
      holder.start();
      renamedPath = Files.rename(fileLink, newName, Duration.ZERO);
    }
    assertFalse(exists(fileLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, linkedFile));
    assertTrue(isSameFile(newName, renamedPath));
    assertTrue(exists(linkedFile, LinkOption.NOFOLLOW_LINKS));   // Assure the linked file still exists
  }

  @Test
  public void testBrokenLinkRename() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path newName = target.resolve("newName");
    Path renamedPath = Files.rename(missingLink, newName);
    assertFalse(exists(missingLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testNonEmptyDirectoryRenameWindows() throws Exception {
    assumeTrue(isWindows);
    Path newName = target.resolve("newName");
    List<Path> tree = walkedTree(top);
    try (PathHolder holder = new PathHolder(childFile(top))) {
      holder.start();
      Files.rename(top, newName, Duration.ofMillis(100));
      fail("Expecting FileSystemException");
    } catch (FileSystemException e) {
      // expected
    }
    tree.forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));

    Path renamedPath = Files.rename(top, newName);
    assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
    tree.stream()
        .map(p -> newName.resolve(p.relativize(top)))
        .forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));
    walkedTree(common).forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));
  }

  @Test
  public void testNonEmptyDirectoryRenameLinux() throws Exception {
    assumeFalse(isWindows);
    Path newName = target.resolve("newName");
    List<Path> tree = walkedTree(top);
    Path renamedPath = Files.rename(top, newName);
    assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
    tree.stream()
        .map(p -> newName.resolve(p.relativize(top)))
        .forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));
    walkedTree(common).forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));
  }

  @Test
  public void testEmptyDirectoryRename() throws Exception {
    Path newName = target.resolve("newName");
    Path renamedPath = Files.rename(emptyDir, newName);
    assertFalse(exists(emptyDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testDirectoryLinkRename() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path newName = target.resolve("newName");
    Path linkedDir = java.nio.file.Files.readSymbolicLink(dirLink);
    List<Path> tree = walkedTree(linkedDir);

    Path renamedPath;
    // A busy link target should not affect link deletion
    try (PathHolder holder = new PathHolder(childFile(dirLink))) {
      holder.start();
      renamedPath = Files.rename(dirLink, newName, Duration.ZERO);
    }
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
    tree.forEach(p -> exists(p, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testForeignRename() throws Exception {
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);
    Path localForeignFile = foreignFile.resolveSibling("localForeignFile");
    java.nio.file.Files.createFile(localForeignFile);
    localForeignFile.toFile().deleteOnExit();

    Path newName = target.resolve("newName");
    try {
      Files.rename(localForeignFile, newName);
      fail("Expecting AtomicMoveNotSupportedException");
    } catch (AtomicMoveNotSupportedException expected) {
    } finally {
      Files.deleteIfExists(localForeignFile);
    }
  }

  @Test
  public void testRenameFileToExistingFile() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeFile(existingPath, singletonList("existingPath"));

    List<String> expectedContent = java.nio.file.Files.readAllLines(topFile);
    Path renamedPath = Files.rename(topFile, existingPath);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
    assertThat(java.nio.file.Files.readAllLines(existingPath), is(expectedContent));
  }

  @Test
  public void testRenameFileToExistingDirectory() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeDirectory(existingPath);

    try {
      Files.rename(topFile, existingPath, Duration.ZERO);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Is a directory"));
    }
  }

  @Test
  public void testRenameFileToExistingFileSymlink() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeFileSymlink(existingPath, singletonList("existingFile"));

    Path renamedPath = Files.rename(topFile, existingPath);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
  }

  @Test
  public void testRenameFileToExistingDirectorySymlink() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeDirectorySymlink(existingPath);

    try {
      Path renamedPath = Files.rename(topFile, existingPath, Duration.ZERO);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    }
  }

  @Test
  public void testRenameDirectoryToExistingFile() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeFile(existingPath, singletonList("existingPath"));

    try {
      Path renamedPath = Files.rename(top, existingPath);
      if (!isWindows) {
        fail("Expecting FileSystemException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Not a directory"));
    }
  }

  @Test
  public void testRenameDirectoryToExistingDirectory() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeDirectory(existingPath);

    try {
      Path renamedPath = Files.rename(top, existingPath, Duration.ZERO);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    }
  }

  @Test
  public void testRenameDirectoryToExistingFileSymlink() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeFileSymlink(existingPath, singletonList("existingPath"));

    try {
      Path renamedPath = Files.rename(top, existingPath);
      if (!isWindows) {
        fail("Expecting FileSystemException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Not a directory"));
    }
  }

  @Test
  public void testRenameDirectoryToExistingDirectorySymlink() throws Exception {
    Path existingPath = target.resolve("existingPath");
    makeDirectorySymlink(existingPath);

    try {
      Files.rename(top, existingPath, Duration.ZERO);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Not a directory"));
    }
  }

  @Test
  public void testRenameFileSymlinkToExistingFile() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeFile(existingPath, singletonList("existingPath"));

    List<String> expectedContent = java.nio.file.Files.readAllLines(fileLink);
    Path renamedPath = Files.rename(fileLink, existingPath);
    assertFalse(exists(fileLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
    assertThat(java.nio.file.Files.readAllLines(existingPath), is(expectedContent));
  }

  @Test
  public void testRenameFileSymlinkToExistingDirectory() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeDirectory(existingPath);

    try {
      Files.rename(fileLink, existingPath, Duration.ZERO);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Is a directory"));
    }
  }

  @Test
  public void testRenameFileSymlinkToExistingFileSymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeFileSymlink(existingPath, singletonList("existingPath"));

    List<String> expectedContent = java.nio.file.Files.readAllLines(fileLink);
    Path renamedPath = Files.rename(fileLink, existingPath);
    assertFalse(exists(fileLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
    assertThat(java.nio.file.Files.readAllLines(existingPath), is(expectedContent));
  }

  @Test
  public void testRenameFileSymlinkToExistingDirectorySymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeDirectorySymlink(existingPath);

    try {
      Path renamedPath = Files.rename(fileLink, existingPath, Duration.ZERO);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(fileLink, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    }
  }

  @Test
  public void testRenameDirectorySymlinkToExistingFile() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeFile(existingPath, singletonList("existingPath"));

    Path renamedPath = Files.rename(dirLink, existingPath);
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
  }

  @Test
  public void testRenameDirectorySymlinkToExistingDirectory() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeDirectory(existingPath);

    try {
      Files.rename(dirLink, existingPath, Duration.ZERO);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      }
      assertThat(e.getReason(), is("Is a directory"));
    }
  }

  @Test
  public void testRenameDirectorySymlinkToExistingFileSymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeFileSymlink(existingPath, singletonList("existingPath"));

    Path renamedPath = Files.rename(dirLink, existingPath);
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(existingPath, renamedPath));
  }

  @Test
  public void testRenameDirectorySymlinkToExistingDirectorySymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    Path existingPath = target.resolve("existingPath");
    makeDirectorySymlink(existingPath);

    try {
      Path renamedPath = Files.rename(dirLink, existingPath, Duration.ZERO);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(existingPath, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(existingPath, renamedPath));
    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    }
  }
}
