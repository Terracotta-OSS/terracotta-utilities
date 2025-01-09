/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Represents the byte code version of a class.
 */
@SuppressWarnings("unused")
public class ByteCodeVersion {

  /**
   * Java byte code version to language level mappings.
   *
   * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.1-200-B.2">
   * <i>Java Virtual Machine Specification</i> / The <code>class</code> File Format / Table 4.1-A. class file format major versions</a>
   */
  private static final SortedMap<Integer, String> VERSION_MAP;
  static {
    TreeMap<Integer, String> versionMap = new TreeMap<>();
    versionMap.put(45, "1.1");
    versionMap.put(46, "1.2");
    versionMap.put(47, "1.3");
    versionMap.put(48, "1.4");
    versionMap.put(49, "5.0");
    versionMap.put(50, "6");
    versionMap.put(51, "7");
    versionMap.put(52, "8");
    versionMap.put(53, "9");
    versionMap.put(54, "10");
    versionMap.put(55, "11");
    versionMap.put(56, "12");
    versionMap.put(57, "13");
    versionMap.put(58, "14");
    versionMap.put(59, "15");
    versionMap.put(60, "16");
    versionMap.put(61, "17");
    versionMap.put(62, "18");
    versionMap.put(63, "19");
    versionMap.put(64, "20");
    versionMap.put(65, "21");
    VERSION_MAP = Collections.unmodifiableSortedMap(versionMap);
  }

  private final int majorVersion;
  private final int minorVersion;

  private ByteCodeVersion(short majorVersion, short minorVersion) {
    this.majorVersion = majorVersion;
    this.minorVersion = minorVersion;
  }

  /**
   * Determines the byte code version of the indicated class.
   *
   * @param clazz the class for which the byte code version is determined
   * @return new {@code ByteCodeVersion} instance; {@code major} is -1 if the byte code version cannot be determined
   */
 @SuppressFBWarnings(value = "DCN_NULLPOINTER_EXCEPTION", justification = "NPE from getResourceAsStream is handled quietly")
  public static ByteCodeVersion fromClass(Class<?> clazz) {
    requireNonNull(clazz, "clazz");
    try (InputStream in = clazz.getClassLoader()
        .getResourceAsStream(clazz.getName().replace('.', '/') + ".class");
         DataInputStream dataStream = new DataInputStream(requireNonNull(in, "in"))) {
      int magic = dataStream.readInt();
      if (magic != 0xCAFEBABE) {
        return new ByteCodeVersion((short)-1, (short)-1);
      }
      short minor_version = dataStream.readShort();
      short major_version = dataStream.readShort();
      return new ByteCodeVersion(major_version, minor_version);
    } catch (NullPointerException e) {
      return new ByteCodeVersion((short)-1, (short)-2);
    } catch (IOException e) {
      return new ByteCodeVersion((short)-1, (short)-3);
    }
  }

  /**
   * Gets the <i>major</i> version number.
   * @return the major version number
   */
  public int majorVersion() {
    return majorVersion;
  }

  /**
   * Gets the <i>minor</i> version number.
   * @return the minor version number
   */
  public int minorVersion() {
    return minorVersion;
  }

  /**
   * Gets the Java language level at which the <i>major.minor</i> version was introduced.
   * <p>
   * If the major version is outside the range recognized by this routine, the value returned
   * is {@code "<first>-"} for a major below the recognized values or {@code "<last>+"} for above.
   * @return the Java language level for this {@code ByteCodeVersion}
   */
  public String languageLevel() {
    String languageVersion = VERSION_MAP.get(majorVersion);
    if (languageVersion == null) {
      if (majorVersion > VERSION_MAP.lastKey()) {
        languageVersion = VERSION_MAP.lastKey() + "+";
      } else if (majorVersion < VERSION_MAP.firstKey()) {
        languageVersion = VERSION_MAP.firstKey() + "-";
      } else {
        throw new AssertionError("major_version " + majorVersion + " not found in table");
      }
    }
    return languageVersion;
  }

  @Override
  public String toString() {
    return "ByteCodeVersion{" +
        "majorVersion=" + majorVersion +
        ", minorVersion=" + minorVersion +
        ", languageLevel='" + languageLevel() + '\'' +
        '}';
  }
}
