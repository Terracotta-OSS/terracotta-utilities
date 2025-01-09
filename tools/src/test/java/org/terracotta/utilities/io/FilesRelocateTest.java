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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.file.Files.createFile;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isSameFile;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the {@link Files} {@code relocate} method.
 * @see FilesRelocateFileTest
 * @see FilesRelocateFileTest
 */
public class FilesRelocateTest extends FilesRelocateTestBase {
  // Files.relocate is a combination of Files.deleteTree and Files.copy each of which is
  // covered in some detail.  This class will focus on behaviors unique to Files.relocate.

  @Test
  public void testNullPathOrigin() throws Exception {
    try {
      Files.relocate(null, top.resolveSibling("other"));
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNullPathTarget() throws Exception {
    try {
      Files.relocate(top, null);
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNonExistentPath() throws Exception {
    try {
      Files.relocate(root.resolve("missing"), root.resolve("other"));
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
  }

  /**
   * This test ensures that relative path targets are handled properly.
   */
  @Test
  public void testRelativeTarget() throws Exception {
    Path userDir = Paths.get(System.getProperty("user.dir"));
    Path tempDir = java.nio.file.Files.createTempDirectory(
        Paths.get(""), "delete_me-" + testName.getMethodName() + "-");
    Path targetDir = tempDir.resolve(top.getFileName());
    try {
      assertTrue(exists(tempDir));
      assertFalse(tempDir.isAbsolute());
      Path resolvedDir = userDir.resolve(tempDir);
      assertTrue(resolvedDir.isAbsolute());
      assertTrue(isSameFile(tempDir, resolvedDir));
      assertThat(userDir.relativize(resolvedDir), is(tempDir));

      Path relocatedPath = Files.relocate(top, targetDir);
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(userDir.resolve(targetDir)));
      assertTrue(isSameFile(relocatedPath, resolvedDir.resolve(top.getFileName())));
    } finally {
      Files.deleteTree(tempDir);
    }
  }

  @Test
  public void testLocalFileStore() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    Path relocatedPath = Files.relocate(top, targetDir);
    assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, relocatedPath));
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  @Test
  public void testForeignFileStore() throws Exception {
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    /*
     * Create a relocate source in a non-local FileStore.
     */
    Path sourceRoot = foreignDir.getParent();
    Path sourceDir = java.nio.file.Files.createTempDirectory(sourceRoot, "delete_me_");
    sourceDir.toFile().deleteOnExit();
    Path testFile = createFile(sourceDir.resolve("file"));
    testFile.toFile().deleteOnExit();

    List<PathDetails> expectedDetails = walkedTree(sourceDir).stream().map(p -> {
      try {
        return new PathDetails(sourceRoot, p, false);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }).collect(Collectors.toList());

    Path targetDir = target.resolve(sourceDir.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    Path relocatedPath = Files.relocate(sourceDir, targetDir);
    assertFalse(exists(sourceDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, relocatedPath));

    List<PathDetails> actualDetails = walkedTree(targetDir).stream().map(p -> {
      try {
        return new PathDetails(target, p, false);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }).collect(Collectors.toList());

    Iterator<PathDetails> expectedIterator = expectedDetails.iterator();
    Iterator<PathDetails> actualIterator = actualDetails.iterator();
    while (expectedIterator.hasNext()) {
      assertTrue("actualTree is smaller than expectedTree:\n    actual=" + actualDetails + "\n    expected=" + expectedDetails, actualIterator.hasNext());
      PathDetails expectedDetail = expectedIterator.next();
      PathDetails actualDetail = actualIterator.next();
      assertTrue("Target " + actualDetail + " does not mirror\n    source " + expectedDetail, actualDetail.mirrors(expectedDetail, true));
    }
    assertFalse("actualTree is larger than expectedTree:\n    actual=" + actualDetails + "\n    expected=" + expectedDetails, actualIterator.hasNext());
  }
}
