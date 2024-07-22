/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Tests for the {@link Files#copy(InputStream, Path, CopyOption...)} method.
 */
public class FilesStreamCopyTest extends FilesTestBase {

  private static final String TEST_RESOURCE_NAME = "/De_finibus_bonorum_et_malorum_Liber_Primus.txt";
  private static byte[] TEST_STREAM_CONTENT;

  private Path targetDir;

  @BeforeClass
  public static void extractTestStream() throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      try (InputStream inputStream = getTestStream()) {
        byte[] buffer = new byte[8196];
        int readCount;
        while ((readCount = inputStream.read(buffer)) > 0) {
          baos.write(buffer, 0, readCount);
        }
      }
      baos.flush();
      TEST_STREAM_CONTENT = baos.toByteArray();
    }
  }

  @Before
  public void prepareTarget() throws IOException {
    targetDir = createDirectory(root.resolve("target_" + testName.getMethodName()));  // root/target
  }

  @Test
  public void testNullStream() {
    assertThat(() -> Files.copy((InputStream)null, targetDir), threw(instanceOf(NullPointerException.class)));
  }

  @Test
  public void testNullTarget() throws IOException {
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, null), threw(instanceOf(NullPointerException.class)));
    }
  }

  @Test
  public void testNullCopyOption() throws IOException {
    Path targetFile = targetDir.resolve("file");
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, targetFile, (CopyOption)null), threw(instanceOf(UnsupportedOperationException.class)));
    }
  }

  @Test
  public void testBadCopyOption() throws IOException {
    Path targetFile = targetDir.resolve("file");
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, targetFile, StandardCopyOption.COPY_ATTRIBUTES), threw(instanceOf(UnsupportedOperationException.class)));
    }
  }

  @Test
  public void testCopyNew() throws Exception {
    Path targetFile = targetDir.resolve("file");
    assertTrue(java.nio.file.Files.notExists(targetFile));
    try (InputStream inputStream = getTestStream()) {
      Files.copy(inputStream, targetFile);
    }
    assertThat(readAllBytes(targetFile), is(TEST_STREAM_CONTENT));
  }

  @Test
  public void testCopyExistsNoReplace() throws Exception {
    assertThat(topFile.toFile(), is(anExistingFile()));
    byte[] topFileContent = readAllBytes(topFile);
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, topFile), threw(instanceOf(FileAlreadyExistsException.class)));
    }
    assertThat(readAllBytes(topFile), is(topFileContent));
  }

  @Test
  public void testCopyExistsReplace() throws Exception {
    assertThat(topFile.toFile(), is(anExistingFile()));
    try (PathHolder holder = new PathHolder(topFile, Duration.ofMillis(100))) {
      holder.start();
      try (InputStream inputStream = getTestStream()) {
        Files.copy(inputStream, topFile, REPLACE_EXISTING);
      }
    }
    assertThat(readAllBytes(topFile), is(TEST_STREAM_CONTENT));
  }

  @Test
  public void testCopyLinkMissingNoReplace() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path missingFileLink = createSymbolicLink(top.resolve("missingLink"), top.resolve("missing"));
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, missingFileLink),
          threw(Matchers.<Throwable>both(instanceOf(FileAlreadyExistsException.class))
              .and(hasMessage(containsString(missingFileLink.toString())))));
    }
  }

  @Test
  public void testCopyLinkMissingReplace() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path missing = top.resolve("missing");
    assertTrue(java.nio.file.Files.notExists(missing));
    Path targetFile = top.resolve("missingLink");
    Path missingFileLink = createSymbolicLink(targetFile, missing);
    try (InputStream inputStream = getTestStream()) {
      Files.copy(inputStream, missingFileLink, REPLACE_EXISTING);
    }
    assertThat(readAllBytes(targetFile), is(TEST_STREAM_CONTENT));
    assertTrue(java.nio.file.Files.notExists(missing));
  }

  @Test
  public void testCopyLinkExistsNoReplace() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    Path fileLink = createSymbolicLink(top.resolve("linkedFile"), topFile);
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, fileLink),
          threw(Matchers.<Throwable>both(instanceOf(FileAlreadyExistsException.class))
              .and(hasMessage(containsString(fileLink.toString())))));
    }
  }

  @Test
  public void testCopyLinkExistsReplace() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);

    byte[] topFileContent = readAllBytes(topFile);
    Path linkedFile = top.resolve("linkedFile");
    Path fileLink = createSymbolicLink(linkedFile, topFile);
    try (PathHolder holder = new PathHolder(topFile, Duration.ofMillis(100))) {
      holder.start();
      try (InputStream inputStream = getTestStream()) {
        Files.copy(inputStream, fileLink, REPLACE_EXISTING);
      }
    }
    assertThat(readAllBytes(linkedFile), is(TEST_STREAM_CONTENT));
    assertThat(topFile.toFile(), is(anExistingFile()));
    assertThat(readAllBytes(topFile), is(topFileContent));
  }

  /**
   * Test {@link Files#copy(InputStream, Path, CopyOption...) copy(inputStream, dirpath)} where
   * {@code dirpath} is an empty directory -- should throw FileAlreadyExistsException without {@code REPLACE_EXISTING}.
   */
  @Test
  public void testCopyDirectoryExistsNoReplace() throws Exception {
    assertThat(emptyDir.toFile(), is(anExistingDirectory()));
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, emptyDir), threw(instanceOf(FileAlreadyExistsException.class)));
    }
  }

  /**
   * Test {@link Files#copy(InputStream, Path, CopyOption...) copy(inputStream, dirpath, REPLACE_EXISTING)} where
   * {@code dirpath} is an empty directory -- the empty directory is replaced with a file holding the stream content.
   */
  @Test
  public void testCopyDirectoryReplaceEmpty() throws Exception {
    assertThat(emptyDir.toFile(), is(anExistingDirectory()));
    try (InputStream inputStream = getTestStream()) {
      Files.copy(inputStream, emptyDir, REPLACE_EXISTING);
    }
    assertThat(emptyDir.toFile(), is(anExistingFile()));
    assertThat(readAllBytes(emptyDir), is(TEST_STREAM_CONTENT));
  }

  /**
   * Test {@link Files#copy(InputStream, Path, CopyOption...) copy(inputStream, dirpath, REPLACE_EXISTING)} where
   * {@code dirpath} is a non-empty directory -- the copy fails.
   */
  @Test
  public void testCopyDirectoryReplaceNonEmpty() throws Exception {
    try (InputStream inputStream = getTestStream()) {
      assertThat(() -> Files.copy(inputStream, top, REPLACE_EXISTING), threw(instanceOf(DirectoryNotEmptyException.class)));
    }
  }

  private static InputStream getTestStream() {
    InputStream inputStream = FilesStreamCopyTest.class.getResourceAsStream(TEST_RESOURCE_NAME);
    assertNotNull("Failed to obtain stream on " + TEST_RESOURCE_NAME , inputStream);
    return inputStream;
  }
}