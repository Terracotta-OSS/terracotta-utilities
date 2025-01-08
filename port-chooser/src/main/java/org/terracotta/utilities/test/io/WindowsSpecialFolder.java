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
package org.terracotta.utilities.test.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.exec.Shell;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Provides the value for Windows Special Folder identifiers.
 * <p>
 * The values represented by this enumeration are determined when first referenced
 * by calling {@code PowerShell} to obtain the path assigned to the special folder
 * identifier.
 *
 * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.environment.specialfolder?view=netframework-4.8">
 * Environment.SpecialFolder Enum</a>
 * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.environment.getfolderpath?view=netframework-4.8">
 * Environment.GetFolderPath Method</a>
 */
public enum WindowsSpecialFolder {

  /**
   * Provides the {@code Path} corresponding to the {@code CommonApplicationData} special folder.
   * A get via this constant will attempt to create the folder if it does not already exist.
   */
  COMMON_APPLICATION_DATA("CommonApplicationData", true),

  /**
   * Provides the {@code Path} corresponding to the {@code System} special folder.
   */
  SYSTEM("System", false);

  private final LazyProperty<Path> accessor;

  WindowsSpecialFolder(String identifier, boolean create) {
    requireNonNull(identifier, "identifier");
    this.accessor = LazyProperty.lazily(() -> getSpecialFolder(identifier, create));
  }

  /**
   * Gets the {@code Path} assigned to the identified special folder.
   * @return the special folder {@code Path}
   * @throws IOException if an error is raised while attempting to determine the
   *      {@code Path} for the special folder
   */
  public Path get() throws IOException {
    return accessor.get();
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(WindowsSpecialFolder.class);

  /**
   * Gets the {@link Path} to the identified Windows Special Folder.
   *
   * @param specialFolderId the special folder identifier
   * @param create          if {@code true}, attempts to create the folder if it does not exist
   * @return the path to the special folder; an empty path is returned if the folder does not exist
   * @throws IOException if the special folder value cannot be determined
   */
  private static Path getSpecialFolder(String specialFolderId, boolean create) throws IOException {
    String[] command = new String[] {
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "&{$ErrorActionPreference = 'Stop'; " +
            "[environment]::getfolderpath('" + specialFolderId + "'" + (create ? ", 'create'" : "") + ")}" };

    String specialFolder;
    Shell.Result result;
    try {
      result = Shell.execute(Shell.Encoding.CHARSET, command);
    } catch (IOException e) {
      LOGGER.error("Unable to determine special folder for {}; {} failed",
          specialFolderId, Arrays.toString(command), e);
      throw e;
    }
    if (result.exitCode() == 0) {
      specialFolder = result.lines().get(0);
    } else {
      SpecialFolderException exception =
          new SpecialFolderException(specialFolderId, result.lines(), result.exitCode());
      LOGGER.error("Unable to determine special folder for {}", specialFolderId, exception);
      throw exception;
    }

    return Paths.get(specialFolder);
  }

  /**
   * A <i>derived on first reference</i> value holder.
   * @param <T> the value type
   */
  private static final class LazyProperty<T> {
    public static <T> LazyProperty<T> lazily(ThrowingSupplier<T> from) {
      return new LazyProperty<>(from);
    }

    private final AtomicReference<T> reference = new AtomicReference<>();
    private final ThrowingSupplier<T> supplier;

    private LazyProperty(ThrowingSupplier<T> supplier) {
      this.supplier = supplier;
    }

    public T get() throws IOException {
      T t;
      do {
        t = reference.get();
      } while (t == null && !reference.compareAndSet(null, t = supplier.get()));
      return t;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
      T get() throws IOException;
    }
  }

  /**
   * Thrown when the folder assigned to a Windows Special Folder identifier cannot be determined.
   */
  public static class SpecialFolderException extends IOException {
    private static final long serialVersionUID = 8319003610562205355L;
    private final int code;

    private SpecialFolderException(String specialFolder, List<String> errorDetail, int code) {
      super("Error determining directory assigned to special folder \"" + specialFolder + "\"; rc=" + code
          + (errorDetail == null ? "" : "\n    " + String.join("\n    ", errorDetail)));
      this.code = code;
    }

    public int code() {
      return code;
    }
  }
}
