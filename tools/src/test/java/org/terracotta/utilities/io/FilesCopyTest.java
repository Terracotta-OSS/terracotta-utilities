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
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;

import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isSameFile;
import static java.nio.file.Files.readAttributes;
import static java.nio.file.Files.readSymbolicLink;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.terracotta.utilities.exec.Shell.execute;

/**
 * Tests the {@link Files} {@code copy} methods.
 * <p>
 * {@link Files#copy(Path, Path, CopyOption...)} supports a number of options that interact with each other.
 * Ideally, all option combinations would be tested -- but this is impractical.  The following options are
 * to be tested:
 *
 * <table style="border-collapse:collapse;border-spacing:0;">
 *     <tr>
 *         <th style="border-collapse: collapse; border-spacing: 0; font-weight: normal; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;"></th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             NOFOLLOW_LINKS
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             REPLACE_EXISTING
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             COPY_ATTRIBUTES
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             NOSPAN_FILESTORES
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             RECURSIVE
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             DEEP_COPY
 *         </th>
 *         <th style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: left;">
 *             Comments
 *         </th>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             1
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory using linked content
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             2
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             --
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory/link
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             3
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory using linked content <i>replacing</i> target, if necessary
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             4
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory <i>including attributes</i> using linked content
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             5
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory using linked content <i>unless</i> content is in "foreign" FileStore
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             6
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Copies file/directory <i>recursively</i> using linked content
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             7
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Same as #1; DEEP_COPY is meaningful only with RECURSIVE and NOFOLLOW_LINKS
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             8
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             --
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             Expected use case #1 <br>
 *             * Recursively copies directory tree <br>
 *             * <i>Relocates</i> links within the tree <br>
 *             * <i>Copies</i> links outside the tree
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             9
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             * Recursively copies directory tree<br>
 *             * Relocates links within the tree<br>
 *             * Copies linked content outside the tree
 *         </td>
 *     </tr>
 *     <tr>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; font-weight: bold; text-align: center;">
 *             10
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &nbsp;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: center;">
 *             &#x2714;
 *         </td>
 *         <td style="border-collapse: collapse; border-spacing: 0; padding: 10px 5px; border-style: solid; border-width: 1px; text-align: left;">
 *             * Recursively copies directory tree<br>
 *             * Relocates links within the tree<br>
 *             * Copies linked content outside the tree iff content is in source FileStore
 *         </td>
 *     </tr>
 * </table>
 */
public class FilesCopyTest extends FilesTestBase {

  private Path target;

  @Before
  public void prepareTarget() throws IOException {
    target = createDirectory(root.resolve("target_" + testName.getMethodName()));  // root/target
  }

  @Test
  public void testNullPathSource() throws Exception {
    try {
      Files.copy((Path)null, top.resolveSibling("other"));
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNullPathTarget() throws Exception {
    try {
      Files.copy(top, null);
      fail("Expecting NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void testNonExistentPath() throws Exception {
    try {
      Files.copy(root.resolve("missing"), root.resolve("other"));
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
  }

  @Test
  public void testUnsupportedOption() throws Exception {
    try {
      Files.copy(top, target, StandardCopyOption.ATOMIC_MOVE);
      fail("Expecting UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testSameFile() throws Exception {
    FileTime beforeTime = readAttributes(topFile, BasicFileAttributes.class).lastModifiedTime();
    Path copyPath = Files.copy(topFile, topFile);
    assertTrue(isSameFile(topFile, copyPath));
    FileTime afterTime = readAttributes(topFile, BasicFileAttributes.class).lastModifiedTime();
    assertEquals(beforeTime, afterTime);
  }

  @Test
  public void testSameDirectory() throws Exception {
    FileTime beforeTime = readAttributes(top, BasicFileAttributes.class).lastModifiedTime();
    Path copyPath = Files.copy(top, top);
    assertTrue(isSameFile(top, copyPath));
    FileTime afterTime = readAttributes(top, BasicFileAttributes.class).lastModifiedTime();
    assertEquals(beforeTime, afterTime);
  }

  //
  // Default options (none specified) performs a single-level copy of the target
  // without copying attributes; links are followed.
  //

  @Test
  public void testCopyFileDefault() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(topFile, targetFile);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyFileExistingDefault() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    java.nio.file.Files.createFile(targetFile);

    try {
      Files.copy(topFile, targetFile);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testCopyFileLinkDefault() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(fileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(fileLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(linkTarget)) {
      holder.start();
      copyPath = Files.copy(fileLink, targetFile);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(fileLink.getParent(), fileLink, true);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyMissingLinkDefault() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(missingLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    try {
      Files.copy(missingLink, targetFile);
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testCopyDirectoryDefault() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(top, targetDir);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    assertThat(walkedTree(targetDir), hasSize(1));    // contains only itself -- no children
  }

  @Test
  public void testCopyDirectoryExistingDefault() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    java.nio.file.Files.createDirectory(targetDir);

    try {
      Files.copy(top, targetDir);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testCopyDirectoryLinkDefault() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(dirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(dirLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(linkTarget))) {
      holder.start();
      copyPath = Files.copy(dirLink, targetDir);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(dirLink.getParent(), dirLink, true);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    assertThat(walkedTree(targetDir), hasSize(1));    // contains only itself -- no children
  }


  //
  // When NOFOLLOW_LINKS is specified, a single-level copy is performed (like using the default options)
  // but, instead of copying the content of a link, the link itself is copied.  Again, attributes are not
  // copied.
  //

  @Test
  public void testCopyFileLinkNoFollow() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(fileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(fileLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(linkTarget)) {
      holder.start();
      copyPath = Files.copy(fileLink, targetFile, LinkOption.NOFOLLOW_LINKS);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(fileLink.getParent(), fileLink, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyMissingLinkNoFollow() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(missingLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    Path copyPath = Files.copy(missingLink, targetFile, LinkOption.NOFOLLOW_LINKS);
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(missingLink.getParent(), missingLink, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyDirectoryLinkNoFollow() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(dirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(dirLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(linkTarget))) {
      holder.start();
      copyPath = Files.copy(dirLink, targetDir, LinkOption.NOFOLLOW_LINKS);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(dirLink.getParent(), dirLink, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }


  //
  // When REPLACE_EXISTING is specified, operation proceeds as with the default options except that,
  // if a file/directory exists, it is first deleted then the copy is performed.
  //

  @Test
  public void testCopyFileExistingReplace() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    java.nio.file.Files.createFile(targetFile);

    Path copyPath;
    try (PathHolder holder = new PathHolder(targetFile, Duration.ofMillis(100))) {
      holder.start();
      copyPath = Files.copy(topFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }

    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyDirectoryExistingReplace() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    java.nio.file.Files.createDirectory(targetDir);

    Path copyPath = Files.copy(top, targetDir, StandardCopyOption.REPLACE_EXISTING);

    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    assertThat(walkedTree(targetDir), hasSize(1));    // contains only itself -- no children
  }


  //
  // When COPY_ATTRIBUTES is specified, operation proceeds as with the default options except that,
  // certain filesystem-specific attributes are copied.  The only one guaranteed is the file
  // last-modified-time.  (Symbolic links aren't expected to support attributes.)
  //

  @Test
  public void testCopyFileAttributes() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(topFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, true));
  }

  @Test
  public void testCopyDirectoryAttributes() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(top, targetDir, StandardCopyOption.COPY_ATTRIBUTES);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(top.getParent(), top, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, true));

    assertThat(walkedTree(targetDir), hasSize(1));    // contains only itself -- no children
  }


  //
  // When NOSPAN_FILESTORES is specified, operation proceeds as with the default options except that,
  // linked content is only copied if it is within the FileStore of the source.
  //

  @Test
  public void testCopyFileLinkNoSpan() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path targetFile = target.resolve(foreignFileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    try {
      Files.copy(foreignFileLink, targetFile, Files.ExtendedOption.NOSPAN_FILESTORES);
      fail("expecting FileStoreConstraintException");
    } catch (Files.FileStoreConstraintException e) {
      // expected
    }
  }

  @Test
  public void testCopyDirectoryLinkNoSpan() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path targetDir = target.resolve(foreignDirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    try {
      Files.copy(foreignDirLink, targetDir, Files.ExtendedOption.NOSPAN_FILESTORES);
      fail("Expecting FileStoreConstraintException");
    } catch (Files.FileStoreConstraintException e) {
      // expected
    }
  }


  //
  // When both NOSPAN_FILESTORES and NOFOLLOW_LINKS are specified, operation proceeds
  // as as if NOSPAN_FILESTORES was not specified -- symbolic links to a "foreign"
  // FileStore are copied.
  //

  @Test
  public void testCopyFileLinkNoFollowNoSpan() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path targetFile = target.resolve(foreignFileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(foreignFileLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(linkTarget)) {
      holder.start();
      copyPath = Files.copy(foreignFileLink, targetFile, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.NOSPAN_FILESTORES);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(foreignFileLink.getParent(), foreignFileLink, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyDirectoryLinkNoFollowNoSpan() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path targetDir = target.resolve(foreignDirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(foreignDirLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(linkTarget))) {
      holder.start();
      copyPath = Files.copy(foreignDirLink, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.NOSPAN_FILESTORES);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(foreignDirLink.getParent(), foreignDirLink, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }


  //
  // When RECURSIVE is specified, directory trees are copied recursively -- as each
  // directory is visited, the directory is created and its children are processed
  // with subdirectories being processed first ... a depth-first traversal.  As with
  // the default processing, linked content is copied.  (File processing is unaffected.)
  //

  @Test
  public void testCopyFileRecursive() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(topFile, targetFile, Files.ExtendedOption.RECURSIVE);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyFileExistingRecursive() throws Exception {
    Path targetFile = target.resolve(topFile.getFileName());
    java.nio.file.Files.createFile(targetFile);

    try {
      Files.copy(topFile, targetFile, Files.ExtendedOption.RECURSIVE);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testCopyFileLinkRecursive() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(fileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    Path linkTarget = readSymbolicLink(fileLink);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(linkTarget)) {
      holder.start();
      copyPath = Files.copy(fileLink, targetFile, Files.ExtendedOption.RECURSIVE);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));
    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(fileLink.getParent(), fileLink, true);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyMissingLinkRecursive() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(missingLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    try {
      Files.copy(missingLink, targetFile, Files.ExtendedOption.RECURSIVE);
      fail("Expecting NoSuchFileException");
    } catch (NoSuchFileException e) {
      // expected
    }
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void testCopyDirectoryRecursive() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    List<Path> expectedTree = walkedTree(top, FileVisitOption.FOLLOW_LINKS);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(topFile)) {
      holder.start();
      copyPath = Files.copy(top, targetDir, Files.ExtendedOption.RECURSIVE);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    List<Path> actualTree = walkedTree(targetDir);
    compareTrees(false, top.getParent(), expectedTree, true, emptyList(), target, actualTree);
  }

  @Test
  public void testCopyDirectoryExistingRecursive() throws Exception {
    Path targetDir = target.resolve(top.getFileName());
    java.nio.file.Files.createDirectory(targetDir);

    try {
      Files.copy(top, targetDir, Files.ExtendedOption.RECURSIVE);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException e) {
      // expected
    }
  }

  @Test
  public void testCopyDirectoryLinkRecursive() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(dirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    List<Path> expectedTree = walkedTree(dirLink, FileVisitOption.FOLLOW_LINKS);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(readSymbolicLink(dirLink)))) {
      holder.start();
      copyPath = Files.copy(dirLink, targetDir, Files.ExtendedOption.RECURSIVE);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    List<Path> actualTree = walkedTree(targetDir);
    compareTrees(false, dirLink.getParent(), expectedTree, true, emptyList(), target, actualTree);
  }


  //
  // DEEP_COPY is only meaningful when NOFOLLOW_LINKS (and RECURSIVE, if a directory) are
  // specified and symbolic links are present -- otherwise the conditions under which DEEP_COPY
  // is meaningful are not present.
  //

  @Test
  public void testCopyFileLinkOutsideNoFollowDeep() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetFile = target.resolve(fileLink.getFileName());
    assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(readSymbolicLink(fileLink))) {
      holder.start();
      copyPath = Files.copy(fileLink, targetFile, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.DEEP_COPY);
    }
    assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetFile, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetFile, false);
    PathDetails sourceDetail = new PathDetails(fileLink.getParent(), fileLink, true);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
  }

  @Test
  public void testCopyDirectoryLinkOutsideNoFollowDeep() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(dirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(readSymbolicLink(dirLink)))) {
      holder.start();
      copyPath = Files.copy(dirLink, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.DEEP_COPY);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    PathDetails targetDetail = new PathDetails(target, targetDir, false);
    PathDetails sourceDetail = new PathDetails(dirLink.getParent(), dirLink, true);
    assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));

    assertThat(walkedTree(targetDir), hasSize(1));    // contains only itself -- no children
  }

  @Test
  public void testCopyDirectoryLinkNoFollowRecursiveDeep() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(dirLink.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    List<Path> expectedTree = walkedTree(dirLink, FileVisitOption.FOLLOW_LINKS);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(readSymbolicLink(dirLink)))) {
      holder.start();
      copyPath = Files.copy(dirLink, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, Files.ExtendedOption.DEEP_COPY);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    List<Path> actualTree = walkedTree(targetDir);
    compareTrees(false, dirLink.getParent(), expectedTree, true, emptyList(), target, actualTree);
  }


  //
  // The most expected scenario is NOFOLLOW_LINKS + COPY_ATTRIBUTES + RECURSIVE.  This
  // should
  //  1) Recursively copy the directory tree
  //  2) Relocate links _within_ the source tree
  //  3) Copy links _outside_ the source tree

  @Test
  public void testCopyDirectoryNoFollowRecursiveCopyAttrs() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    List<Path> expectedTree = walkedTree(top);

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(top))) {
      holder.start();
      copyPath = Files.copy(top, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, StandardCopyOption.COPY_ATTRIBUTES);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    List<Path> actualTree = walkedTree(targetDir);
    compareTrees(true, top.getParent(), expectedTree, false, emptyList(), target, actualTree);
  }


  //
  // For our next test, we're using NOFOLLOW_LINKS + COPY_ATTRIBUTES + RECURSIVE + DEEP_COPY.  This
  // should
  //  1) Recursively copy the directory tree
  //  2) Relocate links _within_ the source tree
  //  3) Copy _content_ for links outside the source tree

  @Test
  public void testCopyDirectoryNoFollowRecursiveCopyAttrsDeep() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    // Exceptions -- _outside_ links are followed
    List<Path> expectedTree = walkSpecial(top, Arrays.asList(dirLink, foreignDirLink));

    // File open for read should not interfere with copy
    Path copyPath;
    try (PathHolder holder = new PathHolder(childFile(top))) {
      holder.start();
      copyPath = Files.copy(top, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, StandardCopyOption.COPY_ATTRIBUTES, Files.ExtendedOption.DEEP_COPY);
    }
    assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
    assertTrue(isSameFile(targetDir, copyPath));

    List<Path> actualTree = walkedTree(targetDir);
    compareTrees(true, top.getParent(), expectedTree, false,
        Arrays.asList(dirLink, foreignDirLink, fileLink, foreignFileLink), target, actualTree);
  }


  //
  // For our next test, we're using NOFOLLOW_LINKS + COPY_ATTRIBUTES + RECURSIVE + DEEP_COPY + NOSPAN_FILESTORES.
  // This should
  //  1) Recursively copy the directory tree
  //  2) Relocate links _within_ the source tree
  //  3) Copy _content_ for links outside the source tree
  //  4) Throw if linked content is not in the source FileStore

  @Test
  public void testCopyDirectoryNoFollowRecursiveNoSpanCopyAttrsDeep() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path targetDir = target.resolve(top.getFileName());
    assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

    // File open for read should not interfere with copy
    try (PathHolder holder = new PathHolder(childFile(top))) {
      holder.start();
      Files.copy(top, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, StandardCopyOption.COPY_ATTRIBUTES, Files.ExtendedOption.DEEP_COPY, Files.ExtendedOption.NOSPAN_FILESTORES);
      fail("Expecting FileStoreConstraintException");
    } catch (Files.FileStoreConstraintException e) {
      // expected
    }
  }


  //
  // Windows offers some special environmental considerations related to SUBST drive assignments --
  // java.nio.files.Files.getFileStore throws when presented with a SUBST drive path. Code is added
  // to Files.copy to account for this so we need to test that situation.
  //


  /**
   * Tests Windows copying from a non-SUBST drive to a SUBST drive.
   */
  @Test
  public void testWindowsCopyFileNormalSubst() throws Exception {
    assumeTrue(isWindows);
    Path substTarget = subst(target);
    try {
      Path targetFile = substTarget.resolve(topFile.getFileName());
      assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

      // File open for read should not interfere with copy
      Path copyPath;
      try (PathHolder holder = new PathHolder(topFile)) {
        holder.start();
        copyPath = Files.copy(topFile, targetFile, Files.ExtendedOption.NOSPAN_FILESTORES);
      }
      assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(targetFile, copyPath));
      // Test result against the _real_ target
      PathDetails targetDetail = new PathDetails(target, target.resolve(topFile.getFileName()), false);
      PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
    } finally {
      unSubst(substTarget);
    }
  }

  /**
   * Tests Windows copying from a SUBST drive to a non-SUBST drive.
   */
  @Test
  public void testWindowsCopyFileSubstNormal() throws Exception {
    assumeTrue(isWindows);
    Path substTop = subst(topFile.getParent());
    Path substSource = substTop.resolve(topFile.getFileName());
    try {
      Path targetFile = target.resolve(topFile.getFileName());
      assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

      // File open for read should not interfere with copy
      Path copyPath;
      try (PathHolder holder = new PathHolder(topFile)) {
        holder.start();
        copyPath = Files.copy(substSource, targetFile, Files.ExtendedOption.NOSPAN_FILESTORES);
      }
      assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(targetFile, copyPath));
      PathDetails targetDetail = new PathDetails(target, targetFile, false);
      // Test result against the _real_ source
      PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
    } finally {
      unSubst(substSource);
    }
  }

  /**
   * Tests Windows copying from a SUBST drive to a non-SUBST drive.
   */
  @Test
  public void testWindowsCopyFileSubstSubst() throws Exception {
    assumeTrue(isWindows);
    Path substTop = subst(topFile.getParent());
    Path substSource = substTop.resolve(topFile.getFileName());
    Path substTarget = subst(target);
    try {
      Path targetFile = substTarget.resolve(topFile.getFileName());
      assertFalse(exists(targetFile, LinkOption.NOFOLLOW_LINKS));

      // File open for read should not interfere with copy
      Path copyPath;
      try (PathHolder holder = new PathHolder(topFile)) {
        holder.start();
        copyPath = Files.copy(substSource, targetFile, Files.ExtendedOption.NOSPAN_FILESTORES);
      }
      assertTrue(exists(targetFile, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(targetFile, copyPath));
      // Test result against the _real_ source & target
      PathDetails targetDetail = new PathDetails(target, target.resolve(topFile.getFileName()), false);
      PathDetails sourceDetail = new PathDetails(topFile.getParent(), topFile, false);
      assertTrue("Target " + targetDetail + " does not mirror\n    source " + sourceDetail, targetDetail.mirrors(sourceDetail, false));
    } finally {
      unSubst(substSource);
      unSubst(substTarget);
    }
  }

  @Test
  public void testWindowsCopyDirectorySubstSubstNoFollowRecursiveNoSpanCopyAttrsDeepWithForeign() throws Exception {
    assumeTrue(isWindows);
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assumeTrue("Skipped because a foreign FileStore is not available", foreignFileStoreAvailable);

    Path substTop = subst(topFile.getParent());
    Path substTarget = subst(target);
    try {
      Path targetDir = substTarget.resolve(top.getFileName());
      assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

      // File open for read should not interfere with copy
      try (PathHolder holder = new PathHolder(childFile(top))) {
        holder.start();
        Files.copy(substTop, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, StandardCopyOption.COPY_ATTRIBUTES, Files.ExtendedOption.DEEP_COPY, Files.ExtendedOption.NOSPAN_FILESTORES);
        fail("Expecting FileStoreConstraintException");
      } catch (Files.FileStoreConstraintException e) {
        // expected
      }
    } finally {
      unSubst(substTarget);
      unSubst(substTop);
    }
  }

  @Test
  public void testWindowsCopyDirectorySubstSubstNoFollowRecursiveNoSpanCopyAttrsDeepWithoutForeign() throws Exception {
    assumeTrue(isWindows);

    Path substTop = subst(bSide);
    Path substTarget = subst(target);
    try {
      List<Path> expectedTree = walkedTree(bSide, FileVisitOption.FOLLOW_LINKS);
      Path targetDir = substTarget.resolve(bSide.getFileName());
      assertFalse(exists(targetDir, LinkOption.NOFOLLOW_LINKS));

      Path copyPath = Files.copy(substTop, targetDir, LinkOption.NOFOLLOW_LINKS, Files.ExtendedOption.RECURSIVE, StandardCopyOption.COPY_ATTRIBUTES, Files.ExtendedOption.DEEP_COPY, Files.ExtendedOption.NOSPAN_FILESTORES);
      assertTrue(exists(targetDir, LinkOption.NOFOLLOW_LINKS));
      assertTrue(isSameFile(targetDir, copyPath));

      List<Path> actualTree = walkedTree(targetDir);
      compareTrees(false, bSide.getParent(), expectedTree, true, emptyList(), substTarget, actualTree);
    } finally {
      unSubst(substTarget);
      unSubst(substTop);
    }
  }


  private Path subst(Path dir) throws IOException {
    assertTrue("SUBST is a Windows-only capability", isWindows);
    Path realDir = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
    for (char driveLetter : "ZYWVUTSRQPONML".toCharArray()) {
      String substDrive = driveLetter + ":";
      if (execute(Shell.Encoding.CHARSET, "subst", substDrive, "\"" + realDir + "\"").exitCode() == 0) {
        return Paths.get(substDrive).resolve(File.separator);     // Return a non-DRIVE_RELATIVE path
      }
    }
    throw new FileSystemException(realDir.toString(), null, "Unable to SUBST path");
  }

  private void unSubst(Path subst) {
    assertTrue("SUBST is a Windows-only capability", isWindows);
    Path root = subst.getRoot();
    if (root != null) {
      String drive = root.toString();
      drive = drive.substring(0, drive.length() - 1);
      try {
        int exitCode = execute(Shell.Encoding.CHARSET, "subst", drive, "/D").exitCode();
        if (exitCode != 0) {
          LoggerFactory.getLogger(FilesCopyTest.class).warn("Failed to release SUBST drive {}; rc={}", subst, exitCode);
        }
      } catch (IOException e) {
        LoggerFactory.getLogger(FilesCopyTest.class).warn("Failed to release SUBST drive {}", subst, e);
      }
    }
  }

  private void compareTrees(boolean compareAttributes, Path expectedTreeParent, List<Path> expectedTree, boolean expectedFollowLinks, List<Path> followLinkExceptions, Path actualTreeParent, List<Path> actualTree)
      throws IOException {

    Iterator<Path> expectedIterator = expectedTree.iterator();
    Iterator<Path> actualIterator = actualTree.iterator();
    while (expectedIterator.hasNext()) {
      assertTrue("actualTree is smaller than expectedTree:\n    actual=" + actualTree + "\n    expected=" + expectedTree, actualIterator.hasNext());
      Path expectedPath = expectedIterator.next();
      boolean effectiveFollowLinks = followLinkExceptions.contains(expectedPath) != expectedFollowLinks;
      PathDetails expectedDetail = new PathDetails(expectedTreeParent, expectedPath, effectiveFollowLinks);
      PathDetails actualDetails = new PathDetails(actualTreeParent, actualIterator.next(), false);
      assertTrue("Target " + actualDetails + " does not mirror\n    source " + expectedDetail, actualDetails.mirrors(expectedDetail, compareAttributes));
    }
    assertFalse("actualTree is larger than expectedTree:\n    actual=" + actualTree + "\n    expected=" + expectedTree, actualIterator.hasNext());
  }

  /**
   * A tree walker that accepts exceptions for link traversal.
   * @param path the top-level directory to traverse
   * @param exceptions the directory links to traverse
   * @return the tree in traversal order
   * @throws IOException if an error is raised while traversing the tree
   */
  private List<Path> walkSpecial(Path path, List<Path> exceptions) throws IOException {
    List<Path> tree = new ArrayList<>();
    java.nio.file.Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        tree.add(dir);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.isRegularFile()) {
          tree.add(file);
        } else if (attrs.isSymbolicLink()) {
          BasicFileAttributes targetAttrs;
          try {
            targetAttrs = readAttributes(file, BasicFileAttributes.class);
          } catch (NoSuchFileException e) {
            // Link with missing referent
            tree.add(file);
            return FileVisitResult.CONTINUE;
          }
          if (targetAttrs.isRegularFile()) {
            tree.add(file);
          } else if (targetAttrs.isDirectory()) {
            if (exceptions.contains(file)) {
              try (Stream<Path> pathStream = java.nio.file.Files.walk(file, FileVisitOption.FOLLOW_LINKS)) {
                tree.addAll(pathStream.collect(Collectors.toList()));
              }
            } else {
              tree.add(file);
            }
          } else {
            throw new IOException("Unexpected target type for link \"" + file + "\" : " + pathType(targetAttrs));
          }
        } else {
          throw new IOException("Unexpected type for file \"" + file + "\" : " + pathType(attrs));
        }
        return FileVisitResult.CONTINUE;
      }
    });
    return tree;
  }
}
