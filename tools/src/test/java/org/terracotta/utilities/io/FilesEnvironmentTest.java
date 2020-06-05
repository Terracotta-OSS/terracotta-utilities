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
import org.junit.BeforeClass;
import org.junit.Test;
import org.terracotta.utilities.exec.Shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static java.util.Collections.singleton;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Performs tests against {@link java.nio.file.Files} to gauge operational
 * behaviors and requirements.
 *
 * <h3>Observations</h3>
 * These observations are collected from tests in this class run in the following environments:
 *
 * <dl>
 *   <dt>Windows</dt>
 *   <dd>OS: Microsoft Windows 10 Enterprise 10.0.17763 N/A Build 17763
 *     <br>Java Runtime: Java(TM) SE Runtime Environment (1.8.0_231-b11)
 *     <br>Java VM: Java HotSpot(TM) 64-Bit Server VM (25.231-b11)</dd>
 *   <dt>Linux</dt>
 *   <dd>OS: Linux 4.4.0-17763-Microsoft #864-Microsoft Thu Nov 07 15:22:00 PST 2019
 *     <br>Java Runtime: OpenJDK Runtime Environment (1.8.0_232-b18)
 *     <br>Java VM: OpenJDK 64-Bit Server VM (25.232-b18)</dd>
 *   <dt>Mac OS X</dt>
 *   <dd>OS: Darwin 19.4.0 Darwin Kernel Version 19.4.0: Wed Mar  4 22:28:40 PST 2020; root:xnu-6153.101.6~15/RELEASE_X86_64
 *     <br>Java Runtime: Java(TM) SE Runtime Environment (1.8.0_241-b07)
 *     <br>Java VM: Java HotSpot(TM) 64-Bit Server VM (25.241-b07)</dd>
 * </dl>
 *
 * <table style="table-layout: fixed; border-collapse:collapse; border-spacing:0;">
 * <colgroup>
 *   <col style="width: 8%;">
 *   <col style="width: 17%;">
 *   <col style="width: 25%;">
 *   <col style="width: 25%;">
 *   <col style="width: 25%;">
 * </colgroup>
 * <thead>
 *   <tr>
 *     <th style="border-color:black;border-style:solid;border-width:1px;"></th>
 *     <th style="border-color:black;border-style:solid;border-width:1px;"></th>
 *     <th colspan="3" style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Operating System</th>
 *   </tr>
 *   <tr>
 *     <th style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Source</th>
 *     <th style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Target</th>
 *     <th style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Windows</th>
 *     <th style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Mac OS X</th>
 *     <th style="border-color:black;border-style:solid;border-width:1px; text-align: center;">Linux</th>
 *   </tr>
 * </thead>
 * <tbody>
 *   <tr>
 *     <th colspan="5" style="border-color:black;border-style:solid;border-width:1px; text-align: center;">java.nio.file.Files.move(...)</th>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException<br></td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Not a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException<br></td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>FileSystemException<br>("Is a directory")</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2716;<br>AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">&#x2714;</td>
 *   </tr>
 *   <tr>
 *     <th colspan="5" style="border-color:black;border-style:solid;border-width:1px; text-align: center;">java.nio.file.Files.createFile(...)</th>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <th colspan="5" style="border-color:black;border-style:solid;border-width:1px; text-align: center;">java.nio.file.Files.createDirectory(...)</th>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <th colspan="5" style="border-color:black;border-style:solid;border-width:1px; text-align: center;">java.nio.file.Files.createSymlink(...)</th>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">AccessDeniedException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">File Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 *   <tr>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px;">Directory Symlink</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *     <td style="border-color:black;border-style:solid;border-width:1px; text-align: center;">FileAlreadyExistsException</td>
 *   </tr>
 * </tbody>
 * </table>
 */
public class FilesEnvironmentTest extends FilesTestBase {

  private Path target;

  @BeforeClass
  public static void collectSystemInfo() {
    assumeTrue("To enable, use -Dfiles.environment.test.enable=true",
        Boolean.getBoolean("files.environment.test.enable"));

    String systemDescription;
    if (isWindows) {
      systemDescription = identifyWindowsSystem();
    } else {
      systemDescription = identifyUnixSystem();
    }
    System.out.format("%n********************************************************************************%n"
            + "    OS: %s%n"
            + "    Java Runtime: %s (%s)%n"
            + "    Java VM: %s (%s)%n"
            + "********************************************************************************%n",
        systemDescription,
        System.getProperty("java.runtime.name", "unknown"), System.getProperty("java.runtime.version", "unknown"),
        System.getProperty("java.vm.name", "unknown"), System.getProperty("java.vm.version", "unknown"));
  }


  @Before
  public void prepareTarget() throws IOException {
    target = createDirectory(root.resolve("target_" + testName.getMethodName()));  // root/target
  }

  @Test
  public void testTryFileAtomicMoveToFile() throws Exception {
    tryAtomicMove(PathType.FILE, this.topFile);
  }

  @Test
  public void testTryFileAtomicMoveToDirectory() throws Exception {
    tryAtomicMove(PathType.DIRECTORY, this.topFile);
  }

  @Test
  public void testTryFileAtomicMoveToFileSymlink() throws Exception {
    tryAtomicMove(PathType.SYMBOLIC_LINK_FILE, this.topFile);
  }

  @Test
  public void testTryFileAtomicMoveToDirectorySymlink() throws Exception {
    tryAtomicMove(PathType.SYMBOLIC_LINK_DIRECTORY, this.topFile);
  }

  @Test
  public void testTryDirectoryAtomicMoveToFile() throws Exception {
    tryAtomicMove(PathType.FILE, this.top);
  }

  @Test
  public void testTryDirectoryAtomicMoveToDirectory() throws Exception {
    tryAtomicMove(PathType.DIRECTORY, this.top);
  }

  @Test
  public void testTryDirectoryAtomicMoveToFileSymlink() throws Exception {
    tryAtomicMove(PathType.SYMBOLIC_LINK_FILE, this.top);
  }

  @Test
  public void testTryDirectoryAtomicMoveToDirectorySymlink() throws Exception {
    tryAtomicMove(PathType.SYMBOLIC_LINK_DIRECTORY, this.top);
  }

  @Test
  public void testTryFileSymlinkAtomicMoveToFile() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.FILE, this.fileLink);
  }

  @Test
  public void testTryFileSymlinkAtomicMoveToDirectory() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.DIRECTORY, this.fileLink);
  }

  @Test
  public void testTryFileSymlinkAtomicMoveToFileSymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.SYMBOLIC_LINK_FILE, this.fileLink);
  }

  @Test
  public void testTryFileSymlinkAtomicMoveToDirectorySymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.SYMBOLIC_LINK_DIRECTORY, this.fileLink);
  }

  @Test
  public void testTryDirectorySymlinkAtomicMoveToFile() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.FILE, this.dirLink);
  }

  @Test
  public void testTryDirectorySymlinkAtomicMoveToDirectory() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.DIRECTORY, this.dirLink);
  }

  @Test
  public void testTryDirectorySymlinkAtomicMoveToFileSymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.SYMBOLIC_LINK_FILE, this.dirLink);
  }

  @Test
  public void testTryDirectorySymlinkAtomicMoveToDirectorySymlink() throws Exception {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    tryAtomicMove(PathType.SYMBOLIC_LINK_DIRECTORY, this.dirLink);
  }

  @Test
  public void testTryFileCreateToFile() throws Exception {
    tryCreateExisting(PathType.FILE, p -> Files.createFile(p), "createFile");
  }

  @Test
  public void testTryFileCreateToDirectory() throws Exception {
    tryCreateExisting(PathType.DIRECTORY, p -> Files.createFile(p), "createFile");
  }

  @Test
  public void testTryFileCreateToFileSymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_FILE, p -> Files.createFile(p), "createFile");
  }

  @Test
  public void testTryFileCreateToDirectorySymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_DIRECTORY, p -> Files.createFile(p), "createFile");
  }

  @Test
  public void testTryDirectoryCreateToFile() throws Exception {
    tryCreateExisting(PathType.FILE, p -> Files.createDirectory(p), "createDirectory");
  }

  @Test
  public void testTryDirectoryCreateToDirectory() throws Exception {
    tryCreateExisting(PathType.DIRECTORY, p -> Files.createDirectory(p), "createDirectory");
  }

  @Test
  public void testTryDirectoryCreateToFileSymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_FILE, p -> Files.createDirectory(p), "createDirectory");
  }

  @Test
  public void testTryDirectoryCreateToDirectorySymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_DIRECTORY, p -> Files.createDirectory(p), "createDirectory");
  }

  @Test
  public void testTryFileSymlinkCreateToFile() throws Exception {
    tryCreateExisting(PathType.FILE, FilesEnvironmentTest::createFileSymlink, "createFileSymlink");
  }

  @Test
  public void testTryFileSymlinkCreateToDirectory() throws Exception {
    tryCreateExisting(PathType.DIRECTORY, FilesEnvironmentTest::createFileSymlink, "createFileSymlink");
  }

  @Test
  public void testTryFileSymlinkCreateToFileSymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_FILE, FilesEnvironmentTest::createFileSymlink, "createFileSymlink");
  }

  @Test
  public void testTryFileSymlinkCreateToDirectorySymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_DIRECTORY, FilesEnvironmentTest::createFileSymlink, "createFileSymlink");
  }

  @Test
  public void testTryDirectorySymlinkCreateToFile() throws Exception {
    tryCreateExisting(PathType.FILE, FilesEnvironmentTest::createDirectorySymlink, "createDirectorySymlink");
  }

  @Test
  public void testTryDirectorySymlinkCreateToDirectory() throws Exception {
    tryCreateExisting(PathType.DIRECTORY, FilesEnvironmentTest::createDirectorySymlink, "createDirectorySymlink");
  }

  @Test
  public void testTryDirectorySymlinkCreateToFileSymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_FILE, FilesEnvironmentTest::createDirectorySymlink, "createDirectorySymlink");
  }

  @Test
  public void testTryDirectorySymlinkCreateToDirectorySymlink() throws Exception {
    tryCreateExisting(PathType.SYMBOLIC_LINK_DIRECTORY, FilesEnvironmentTest::createDirectorySymlink, "createDirectorySymlink");
  }

  private void tryAtomicMove(PathType type, Path source) throws IOException {
    Path targetPath = type.create(target);
    System.out.format("%n[%s/%s] Trying move(%s, %s, ATOMIC_MOVE)%n",
        testName.getMethodName(), type, source, targetPath);
    java.nio.file.Files.move(source, targetPath, StandardCopyOption.ATOMIC_MOVE);
    System.out.format("[%s/%s] Successful%n", testName.getMethodName(), type);
  }

  private static Path createFileSymlink(Path path) throws IOException {
    Path tempFile = java.nio.file.Files.createTempFile("delete_me", null);
    tempFile.toFile().deleteOnExit();
    try {
      return Files.createSymbolicLink(path, tempFile);
    } catch (IOException e) {
      try {
        org.terracotta.utilities.io.Files.deleteTree(tempFile);
      } catch (Exception ex) {
        e.addSuppressed(ex);
      }
      throw e;
    }
  }

  private static Path createDirectorySymlink(Path path) throws IOException {
    Path tempDir = java.nio.file.Files.createTempDirectory("delete_me");
    tempDir.toFile().deleteOnExit();
    try {
      return Files.createSymbolicLink(path, tempDir);
    } catch (IOException e) {
      try {
        org.terracotta.utilities.io.Files.deleteTree(tempDir);
      } catch (Exception ex) {
        e.addSuppressed(ex);
      }
      throw e;
    }
  }

  private void tryCreateExisting(PathType type, ThrowingBiFunction<Path> function, String methodName)
      throws IOException {
    Path targetPath = type.create(target);
    System.out.format("%n[%s/%s] Trying %s(%s)", testName.getMethodName(), type, methodName, targetPath);
    try {
      function.apply(targetPath);
      fail("Expecting FileAlreadyExistsException");
    } catch (FileAlreadyExistsException ignored) {
      // expected
    }
    System.out.format("[%s/%s] Successful%n", testName.getMethodName(), type);
  }

  @FunctionalInterface
  private interface ThrowingBiFunction<T> {
    T apply(T t) throws IOException;
  }

  /**
   * Invokes {@code systeminfo.exe} to collect Windows version details.
   * @return a description of the Windows version
   */
  private static String identifyWindowsSystem() {
    String systemInfo;
    try {
      Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, "systeminfo.exe", "/FO", "LIST");
      if (result.exitCode() == 0) {
        String osName = "unknown";
        String osVersion = "unknown";
        Pattern lineParse = Pattern.compile("^([^\\s:][^:]+):\\s+(.+)$");
        for (String line : result) {
          Matcher matcher = lineParse.matcher(line);
          if (matcher.matches()) {
            String fieldName = matcher.group(1);
            if (fieldName.equalsIgnoreCase("OS Name")) {
              osName = matcher.group(2);
            } else if (fieldName.equalsIgnoreCase("OS Version")) {
              osVersion = matcher.group(2);
            }
          }
        }
        systemInfo = osName + " " + osVersion;
      } else {
        systemInfo = "unknown";
      }
    } catch (IOException e) {
      systemInfo = "unknown";
    }
    return systemInfo;
  }

  /**
   * Invokes {@code uname} to identify the *NIX system version details.
   * @return a description of the *NIX version
   */
  private static String identifyUnixSystem() {
    String systemInfo;
    try {
      Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, "uname", "-srv");
      if (result.exitCode() == 0) {
        systemInfo = result.lines().get(0);
      } else {
        systemInfo = "unknown";
      }
    } catch (IndexOutOfBoundsException | IOException e) {
      systemInfo = "unknown";
    }
    return systemInfo;
  }

  private enum PathType {
    FILE("targetFile") {
      @Override
      Path createTarget(Path target) throws IOException {
        Path path = createFile(target);
        java.nio.file.Files.write(path, singleton(target.toString()), StandardCharsets.UTF_8);
        return path;
      }
    },
    DIRECTORY("targetDir") {
      @Override
      Path createTarget(Path target) throws IOException {
        return createDirectory(target);
      }
    },
    SYMBOLIC_LINK_FILE("targetFileLink") {
      @Override
      Path createTarget(Path target) throws IOException {
        assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
        Path tempFile = java.nio.file.Files.createTempFile("delete_me", null);
        tempFile.toFile().deleteOnExit();
        return java.nio.file.Files.createSymbolicLink(target, tempFile);
      }
    },
    SYMBOLIC_LINK_DIRECTORY("targetDirLink") {
      @Override
      Path createTarget(Path target) throws IOException {
        assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
        Path tempDir = java.nio.file.Files.createTempDirectory("delete_me");
        tempDir.toFile().deleteOnExit();
        return java.nio.file.Files.createSymbolicLink(target, tempDir);
      }
    };

    private final Path target;

    PathType(String relativeTarget) {
      this.target = Paths.get(relativeTarget);
    }

    abstract Path createTarget(Path target) throws IOException;

    public Path create(Path targetDir) throws IOException {
      assert java.nio.file.Files.isDirectory(targetDir);
      Path target = targetDir.resolve(this.target);
      target = createTarget(target);
      System.out.format("%n[?/%s] Created: %s (%s)%n",
          name(), target, java.nio.file.Files.exists(target, LinkOption.NOFOLLOW_LINKS));
      return target;
    }
  }
}
