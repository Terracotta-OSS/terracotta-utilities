/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.nio.file.Files.exists;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the {@link Files} {@code relocate} method with a <i>file symbolic link</i> source.
 *
 * @see FilesEnvironmentTest
 */
public class FilesRelocateDirectorySymlinkTest extends FilesRelocateTestBase {

  @BeforeClass
  public static void checkSymlinkSupport() {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
  }

  @Test
  public void testFileToExistingFileWithoutReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeFile(targetPath, singletonList("targetFile"));

    try {
      Files.relocate(dirLink, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testFileToExistingDirectoryWithoutReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeDirectory(targetPath);

    try {
      Files.relocate(dirLink, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testFileToExistingFileSymlinkWithoutReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeFileSymlink(targetPath, singletonList("linkTarget"));

    try {
      Files.relocate(dirLink, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testFileToExistingDirectorySymlinkWithoutReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeDirectorySymlink(targetPath);

    try {
      Files.relocate(dirLink, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testFileToExistingFileWithReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeFile(targetPath, singletonList("targetFile"));

    Files.relocate(dirLink, targetPath, StandardCopyOption.REPLACE_EXISTING);
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));
  }

  // On Windows, this test retries the relocate/rename until retries are exhausted
  @Test
  public void testFileToExistingDirectoryWithReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeDirectory(targetPath);

    try {
      Files.relocate(dirLink, targetPath, StandardCopyOption.REPLACE_EXISTING);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      // When moving a file onto an existing directory, Windows throws AccessDeniedException
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      // When moving a file onto an existing directory, Unix throws FileSystemException
      if (isWindows) {
        throw e;
      } else {
        assertThat(e.getReason(), is("Is a directory"));
      }
    }
    // So much for write once, run anywhere ...
  }

  @Test
  public void testFileToExistingFileSymlinkWithReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeFileSymlink(targetPath, singletonList("targetFile"));

    Files.relocate(dirLink, targetPath, StandardCopyOption.REPLACE_EXISTING);
    assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
    assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));
  }

  // On Windows, this test retries the relocate/rename until retries are exhausted
  @Test
  public void testFileToExistingDirectorySymlinkWithReplace() throws Exception {
    Path targetPath = target.resolve(dirLink.getFileName());
    makeDirectorySymlink(targetPath);

    try {
      Files.relocate(dirLink, targetPath, StandardCopyOption.REPLACE_EXISTING);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(dirLink, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));
    } catch (AccessDeniedException e) {
      // When moving a file onto an existing directory symlink, Windows throws AccessDeniedException
      if (!isWindows) {
        throw e;
      }
    }
    // So much for write once, run anywhere ...
  }
}
