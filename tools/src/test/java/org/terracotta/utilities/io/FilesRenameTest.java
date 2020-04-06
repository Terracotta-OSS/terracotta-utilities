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

import org.junit.Test;

import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.isSameFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the {@link Files} {@code rename} methods.
 */
public class FilesRenameTest extends FilesTestBase {

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
    Path newName = topFile.resolveSibling("newName");
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
    Path newName = topFile.resolveSibling("newName");
    Path renamedPath = Files.rename(topFile, newName);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testFileRenameInTime() throws Exception {
    Path newName = topFile.resolveSibling("newName");
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

    Path newName = fileLink.resolveSibling("newName");
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

    Path newName = missingLink.resolveSibling("newName");
    Path renamedPath = Files.rename(missingLink, newName);
    assertFalse(exists(missingLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testNonEmptyDirectoryRenameWindows() throws Exception {
    assumeTrue(isWindows);
    Path newName = top.resolveSibling("newName");
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
    Path newName = top.resolveSibling("newName");
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
    Path newName = emptyDir.resolveSibling("newName");
    Path renamedPath = Files.rename(emptyDir, newName);
    assertFalse(exists(emptyDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(newName, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(newName, renamedPath));
  }

  @Test
  public void testDirectoryLinkRename() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path newName = dirLink.resolveSibling("newName");
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
}
