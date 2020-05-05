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
package org.terracotta.utilities.test.io;

import org.junit.Rule;
import org.junit.Test;
import org.terracotta.org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Basic tests for {@link CommonFiles}.
 */
public class CommonFilesTest {

  private static final Random rnd = new SecureRandom();

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testCreateCommonFileNonRelativePath() throws Exception {
    Path tempFile = temporaryFolder.newFile().toPath().toAbsolutePath();
    org.terracotta.utilities.io.Files.delete(tempFile);
    try {
      CommonFiles.createCommonAppFile(tempFile);
      fail("Expecting IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testCreateCommonFile() throws Exception {
    String randomDirName = String.format("delete_me_%s", rnd.nextInt());
    String randomFileName = String.format("delete_me_%s", rnd.nextInt());
    Path relPath = Paths.get(randomDirName, randomFileName);
    Path commonAppFile = CommonFiles.createCommonAppFile(relPath);
    assertTrue(Files.exists(commonAppFile));

    // Just in case ...
    commonAppFile.toFile().deleteOnExit();
    commonAppFile.getParent().toFile().deleteOnExit();

    assertTrue(Files.isReadable(commonAppFile));
    assertTrue(Files.isWritable(commonAppFile));

    List<String> expectedLines = Collections.singletonList("file data");
    Files.write(commonAppFile, expectedLines, StandardCharsets.UTF_8);
    List<String> actualLines = Files.readAllLines(commonAppFile, StandardCharsets.UTF_8);
    assertThat(actualLines, is(expectedLines));

    // Attempt to create it again ...
    Path secondCommonAppFile = CommonFiles.createCommonAppFile(relPath);
    assertTrue(Files.isSameFile(secondCommonAppFile, commonAppFile));

    // Remove the file (keeping the directory) and try one more time ...
    org.terracotta.utilities.io.Files.delete(commonAppFile);
    assertTrue(Files.exists(commonAppFile.getParent()));
    assertFalse(Files.exists(commonAppFile));
    Path thirdCommonAppFile = CommonFiles.createCommonAppFile(relPath);
    assertTrue(Files.isSameFile(thirdCommonAppFile, commonAppFile));

    org.terracotta.utilities.io.Files.deleteTree(commonAppFile.getParent());
  }
}