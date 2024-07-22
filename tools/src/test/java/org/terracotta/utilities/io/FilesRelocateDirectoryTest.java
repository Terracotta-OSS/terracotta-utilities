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

/**
 * Tests the {@link Files} {@code relocate} method with a <i>directory</i> source.
 *
 * @see FilesEnvironmentTest
 */
public class FilesRelocateDirectoryTest extends FilesRelocateTestBase {

  @Test
  public void testDirectoryToExistingFileWithoutReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeFile(targetPath, singletonList("targetFile"));

    try {
      Files.relocate(top, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testDirectoryToExistingDirectoryWithoutReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeDirectory(targetPath);

    try {
      Files.relocate(top, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testDirectoryToExistingFileSymlinkWithoutReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeFileSymlink(targetPath, singletonList("linkTarget"));

    try {
      Files.relocate(top, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testDirectoryToExistingDirectorySymlinkWithoutReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeDirectorySymlink(targetPath);

    try {
      Files.relocate(top, targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testDirectoryToExistingFileWithReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeFile(targetPath, singletonList("targetFile"));

    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);

    try {
      Files.relocate(top, targetPath, StandardCopyOption.REPLACE_EXISTING);
      if (!isWindows) {
        fail("Expecting FileSystemException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));

      PathDetails targetDetail = new PathDetails(targetPath.getParent(), targetPath, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      } else {
        assertThat(e.getReason(), is("Not a directory"));
      }
    }
  }

  // On Windows, this test retries the relocate/rename until retries are exhausted
  @Test
  public void testDirectoryToExistingDirectoryWithReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeDirectory(targetPath);

    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);

    try {
      Files.relocate(top, targetPath, StandardCopyOption.REPLACE_EXISTING);
      if (isWindows) {
        fail("Expecting AccessDeniedException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));

      PathDetails targetDetail = new PathDetails(targetPath.getParent(), targetPath, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    } catch (AccessDeniedException e) {
      if (!isWindows) {
        throw e;
      }
    }
  }

  @Test
  public void testDirectoryToExistingFileSymlinkWithReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeFileSymlink(targetPath, singletonList("targetFile"));

    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);

    try {
      Files.relocate(top, targetPath, StandardCopyOption.REPLACE_EXISTING);
      if (!isWindows) {
        fail("Expecting FileSystemException");
      }
      assertFalse(exists(top, LinkOption.NOFOLLOW_LINKS));
      assertTrue(exists(targetPath, LinkOption.NOFOLLOW_LINKS));

      PathDetails targetDetail = new PathDetails(targetPath.getParent(), targetPath, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    } catch (FileSystemException e) {
      if (isWindows) {
        throw e;
      } else {
        assertThat(e.getReason(), is("Not a directory"));
      }
    }
  }

  @Test
  public void testDirectoryToExistingDirectorySymlinkWithReplace() throws Exception {
    Path targetPath = target.resolve(top.getFileName());
    makeDirectorySymlink(targetPath);

    try {
      Files.relocate(top, targetPath, StandardCopyOption.REPLACE_EXISTING);
      fail("Expecting " + (isWindows ? "AccessDeniedException" : "FileSystemException"));
    } catch (AccessDeniedException e) {
      // When moving a file onto an existing directory symlink, Windows throws AccessDeniedException
      if (!isWindows) {
        throw e;
      }
    } catch (FileSystemException e) {
      // When moving a file onto an existing directory symlink, Unix throws FileSystemException
      if (isWindows) {
        throw e;
      } else {
        assertThat(e.getReason(), is("Not a directory"));
      }
    }
  }
}
