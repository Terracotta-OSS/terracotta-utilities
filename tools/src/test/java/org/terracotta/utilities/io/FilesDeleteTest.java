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

import java.lang.reflect.Method;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static java.nio.file.Files.exists;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.terracotta.utilities.test.WaitForAssert.assertThatEventually;

/**
 * Tests the {@link Files} {@code delete} methods.
 */
public class FilesDeleteTest extends FilesTestBase {

  @Test
  public void testNullPath() throws Exception {
    try {
      Files.delete(null);
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNonExistentPath() throws Exception {
    try {
      Files.delete(root.resolve("missing"));
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
  }

  @Test
  public void testIfExistsNullPath() throws Exception {
    try {
      Files.deleteIfExists(null);
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testIfExistsNonExistentPath() throws Exception {
    assertFalse(Files.deleteIfExists(root.resolve("missing")));
  }

  @Test
  public void testFileDeleteWindows() throws Exception {
    assumeTrue(isWindows);
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      Files.delete(topFile, Files.MINIMUM_TIME_LIMIT);
      fail("Expecting FileSystemException");
    } catch (FileSystemException e) {
      // expected
    }

    Files.delete(topFile);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testIfExistsFileDeleteWindows() throws Exception {
    assumeTrue(isWindows);
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      Files.deleteIfExists(topFile);
      fail("Expecting FileSystemException");
    } catch (FileSystemException e) {
      // expected
    }

    assertTrue(Files.deleteIfExists(topFile));
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testFileDeleteLinux() throws Exception {
    assumeFalse(isWindows);
    Files.delete(topFile);
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testIfExistsFileDeleteLinux() throws Exception {
    assumeFalse(isWindows);
    assertTrue(Files.deleteIfExists(topFile));
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testFileDeleteInTime() throws Exception {
    try (PathHolder holder = new PathHolder(topFile, Files.MINIMUM_TIME_LIMIT.dividedBy(2L))) {
      holder.start();
      Files.delete(topFile, Files.MINIMUM_TIME_LIMIT);
    }
    assertFalse(exists(topFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testFileLinkDelete() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path linkedFile = java.nio.file.Files.readSymbolicLink(fileLink);

    // A busy link target should not affect link deletion
    try (PathHolder holder = new PathHolder(linkedFile)) {
      holder.start();
      Files.delete(fileLink, Files.MINIMUM_TIME_LIMIT);
    }
    assertFalse(exists(fileLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(linkedFile, LinkOption.NOFOLLOW_LINKS));   // Assure the linked file still exists
  }

  @Test
  public void testBrokenLinkDelete() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Files.delete(missingLink);
    assertFalse(exists(missingLink, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testNonEmptyDirectoryDelete() throws Exception {
    List<Path> tree = walkedTree(top);
    try {
      Files.delete(top);
      fail("DirectoryNotEmptyException expected");
    } catch (DirectoryNotEmptyException e) {
      // expected
    }
    tree.forEach(p -> assertTrue(exists(p, LinkOption.NOFOLLOW_LINKS)));
  }

  @Test
  public void testEmptyDirectoryDelete() throws Exception {
    Files.delete(emptyDir);
    assertFalse(exists(emptyDir, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testDirectoryLinkDelete() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path linkedDir = java.nio.file.Files.readSymbolicLink(dirLink);
    List<Path> tree = walkedTree(linkedDir);

    // A busy link target should not affect link deletion
    try (PathHolder holder = new PathHolder(childFile(dirLink))) {
      holder.start();
      Files.delete(dirLink, Files.MINIMUM_TIME_LIMIT);
    }
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    tree.forEach(p -> exists(p, LinkOption.NOFOLLOW_LINKS));
  }

  /**
   * Tests the "background" delete that cannot be guaranteed using solely the public methods.
   */
  @Test
  public void testBackgroundDelete() throws Exception {
    assumeTrue(isWindows);
    Method deleteTreeWithBackgroundRetry =
        Files.class.getDeclaredMethod("deleteTreeWithBackgroundRetry", Path.class, boolean.class);
    deleteTreeWithBackgroundRetry.setAccessible(true);

    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      deleteTreeWithBackgroundRetry.invoke(null, topFile, false);
      assertTrue(exists(topFile));    // File not yet deleted
    }

    assertThatEventually(() -> exists(topFile, LinkOption.NOFOLLOW_LINKS), is(false)).within(Duration.ofSeconds(5L));
  }
}
