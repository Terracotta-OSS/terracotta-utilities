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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.terracotta.utilities.exec.Shell;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.file.Files.createFile;
import static java.nio.file.Files.getFileAttributeView;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.WARN;

/**
 * Tool for creating a system-wide, all-user-writable application file.
 */
public final class CommonFiles {
  private static final Logger LOGGER = LoggerFactory.getLogger(CommonFiles.class);
  private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("win");

  /**
   * Creates a file, using the relative path provided, in the system-appropriate directory
   * for persistent, cross-process, cross-user data.
   * <p>
   * On Windows machines, the Windows directory assigned to the {@code CommonApplicationData}
   * special folder is used.  On *NIX machines, {@code /var/tmp} (not {@code java.io.tmpdir})
   * is used.
   *
   * @param path the relative path to the file to create within the common directory
   * @return the path of the created file
   * @throws FileNotFoundException    if the system-appropriate directory does not exist
   * @throws IOException              if an error is raised while attempting to create the file
   * @throws IllegalArgumentException if {@code path} is not relative or is empty
   * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.environment.specialfolder?view=netframework-4.8">
   * Environment.SpecialFolder Enum</a>
   * @see <a href="http://refspecs.linuxfoundation.org/FHS_3.0/fhs/index.html">Filesystem Hierarchy Standard</a>
   */
  @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification = "'/var/tmp' is the FHS-designated name for *NIX OS")
  public static Path createCommonAppFile(Path path) throws IOException {
    Path normalizedPath = path.normalize();
    if (normalizedPath.getNameCount() == 0) {
      throw new IllegalArgumentException("Path \"" + path + "\" is an effectively empty path");
    } else if (normalizedPath.isAbsolute()) {
      throw new IllegalArgumentException("Path \"" + path + "\" is not relative");
    }

    Path commonFolder;
    if (IS_WINDOWS) {
      /*
       * Get the location of Windows CommonApplicationData special folder
       * creating it if necessary.
       */
      commonFolder = getSpecialFolder("CommonApplicationData", true);
    } else {
      /*
       * For *NIX, use '/var/tmp'.  This should be "world" writable.
       */
      commonFolder = Paths.get("/var/tmp");
    }

    if (!Files.exists(commonFolder)) {
      throw new FileNotFoundException("Directory \"" + commonFolder + "\" does not exist");
    }

    /*
     * Create the target file.  If path has multiple parts, first create the any directories
     * up to the filename.
     */
    Path commonFile = commonFolder.resolve(normalizedPath);
    Iterator<Path> pathIterator = normalizedPath.iterator();
    Path pathInProgress = commonFolder;
    while (pathIterator.hasNext()) {
      pathInProgress = pathInProgress.resolve(pathIterator.next());
      try {
        if (pathIterator.hasNext()) {
          // The current segment represents a directory
          try {
            Files.createDirectory(pathInProgress);
            LOGGER.info("Created \"{}\"", pathInProgress);
          } catch (FileAlreadyExistsException e) {
            // If the existing path is not a directory; throw
            if (!Files.isDirectory(pathInProgress)) {
              LOGGER.error("Directory \"{}\" cannot be created; file/dead link already exists in its place",
                  pathInProgress);
              throw e;
            }
            // If the directory is a symbolic link, follow it to ensure permissions are set correctly
            if (Files.isSymbolicLink(pathInProgress)) {
              Path originalPath = pathInProgress;
              pathInProgress = pathInProgress.toRealPath();
              LOGGER.debug("Path \"{}\" linked to \"{}\"", originalPath, pathInProgress);
            }
            LOGGER.info("Directory \"{}\" already exists; will attempt to update ACL/permissions", pathInProgress);
          }
        } else {
          // The current (last) segment represents the file to be created
          try {
            createFile(pathInProgress);
            LOGGER.info("Created \"{}\"", pathInProgress);
          } catch (FileAlreadyExistsException e) {
            LOGGER.info("File \"{}\" already exists; will attempt to update ACL/permissions", pathInProgress);
          }
        }
      } catch (FileAlreadyExistsException e) {
        // Already logged as necessary
        throw e;
      } catch (IOException e) {
        LOGGER.error("Unable to create \"{}\"", pathInProgress);
        throw e;
      }

      copyOwnerPermissions(pathInProgress);
    }

    return commonFile;
  }

  /**
   * Grant all permissions held by the owner to other users.
   *
   * @param path the path on which permissions are altered
   */
  private static void copyOwnerPermissions(Path path) {
    Set<String> fileAttributeViews = path.getFileSystem().supportedFileAttributeViews();
    if (fileAttributeViews.contains("posix")) {
      updatePosixPermissions(path);
    } else if (fileAttributeViews.contains("acl")) {
      updateAcl(path);
    } else {
      LOGGER.warn("Path \"{}\" supports neither ACL nor POSIX permissions ({}); permissions not updated",
          path, fileAttributeViews);
    }
  }

  /**
   * Updates ACL-based permissions.  This method copies the owner permissions into an ACL entry
   * representing <i>all</i> users.
   *
   * <h3>NOTE</h3>
   * The JDK does not expose the {@code INHERITED_ACE} header bit designating whether or not an ACE
   * is actually present in the ACL or inherited from a parent ACL.  (This not the only thing in a
   * Windows ACL not exposed by the JDK.)  Because of this lack, all ACEs, not only the owner and
   * "All Users" ACEs, are explicitly replicated breaking the rights inheritance chain that existed.
   *
   * @param path the file for which permissions are altered
   */
  private static void updateAcl(Path path) {
    logAcl("Before update", DEBUG, path);

    /*
     * Get the Access Control List for the file and determine the owner UserPrincipal.
     */
    AclFileAttributeView view;
    List<AclEntry> aclEntryList = null;
    UserPrincipal owner = null;
    try {
      view = getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null) {
        aclEntryList = view.getAcl();
        owner = view.getOwner();
      }
    } catch (IOException e) {
      LOGGER.warn("Unable to get ACL for \"{}\"; ACL remains unchanged", path, e);
      return;
    }

    /*
     * No owner?  No ACL to duplicate.
     */
    if (owner == null) {
      LOGGER.warn("Owner for \"{}\" is not identified; ; ACL remains unchanged", path);
      return;
    }

    /*
     * Determine "All Users" principal used to make the file generally accessible.
     * For *NIX systems, the POSIX value of "EVERYONE@" is presumed.  (Most *NIX systems are
     * likely to handle this via POSIX permissions and not ACL.)
     */
    String allUsersName = (IS_WINDOWS ? WindowsWellKnownIdentities.AUTHENTICATED_USERS : "EVERYONE@");
    UserPrincipal allUsersPrincipal;
    try {
      allUsersPrincipal = path.getFileSystem()
          .getUserPrincipalLookupService().lookupPrincipalByName(allUsersName);
    } catch (IOException e) {
      LOGGER.warn("Unable to obtain principal representing \"{}\"; ACL remains unchanged", allUsersName, e);
      return;
    }

    /*
     * Windows, anyway, has a "creator owner" placeholder for an ACE that is logically merged
     * into the ACE for the owner.  When replacing the ACL, this needs to be merged into the
     * replacement ACE.
     */
    UserPrincipal creatorOwnerPrincipal = null;
    if (IS_WINDOWS) {
      try {
        creatorOwnerPrincipal = path.getFileSystem()
            .getUserPrincipalLookupService().lookupPrincipalByName(WindowsWellKnownIdentities.CREATOR_OWNER);
      } catch (IOException e) {
        LOGGER.warn("Unable to obtain principal representing \"{}\"; ignoring associated ACE",
            WindowsWellKnownIdentities.CREATOR_OWNER, e);
      }
    }

    /*
     * Get the owner ACE and the ACE for "creator owner" if any.  If there is no owner ACE,
     * there's nothing to copy into the "All Users" ACE so we exit early.
     *
     * It is possible to have multiple ACEs for a principal -- this code isn't capable of
     * handling multiple ACEs ... the proper merge method needs to be figured out.  For
     * the moment, if two owner or CREATOR_OWNER entries are found, we'll abort the ACL
     * update.
     */
    // TODO: Handle multiple ACE for owner principals
    boolean multipleAce = false;
    AclEntry existingOwnerEntry = null;
    AclEntry existingCreatorOwnerEntry = null;
    for (AclEntry entry : aclEntryList) {
      if (entry.principal().equals(owner)) {
        multipleAce |= existingOwnerEntry != null;
        existingOwnerEntry = entry;
      } else if (entry.principal().equals(creatorOwnerPrincipal)) {
        multipleAce |= existingCreatorOwnerEntry != null;
        existingCreatorOwnerEntry = entry;
      }
    }
    if (multipleAce) {
      LOGGER.warn("The ACL for \"{}\" contains multiple ACE for {}; abandoning ACL update",
          path, owner + (creatorOwnerPrincipal == null ? "" : " or " + creatorOwnerPrincipal));
      logAcl("Duplicate ACE", WARN, path);
      return;
    }
    if (existingOwnerEntry == null) {
      LOGGER.warn("Owner of \"{}\" - \"{}\" - has no ACL; ACL remains unchanged", path, owner);
      return;
    }

    /*
     * Construct the ACE for "All Users" and create a merged ACE for owner.  When setting the ACL,
     * inherited entries are replaced.  Both the owner and "All Users" entries need to reflect the
     * full rights set which includes the "CREATOR_OWNER" entry, if any.
     */
    AclEntry.Builder ownerBuilder = AclEntry.newBuilder(existingOwnerEntry);
    AclEntry.Builder allUsersBuilder = AclEntry.newBuilder(existingOwnerEntry).setPrincipal(allUsersPrincipal);
    if (existingCreatorOwnerEntry != null) {
      Set<AclEntryPermission> mergedPermissions = new LinkedHashSet<>(existingOwnerEntry.permissions());
      mergedPermissions.addAll(existingCreatorOwnerEntry.permissions());
      allUsersBuilder.setPermissions(mergedPermissions);
      ownerBuilder.setPermissions(mergedPermissions);

      Set<AclEntryFlag> mergedFlags = new LinkedHashSet<>(existingOwnerEntry.flags());
      mergedFlags.addAll(existingCreatorOwnerEntry.flags());
      mergedFlags.remove(AclEntryFlag.INHERIT_ONLY);      // These are now REAL permissions
      allUsersBuilder.setFlags(mergedFlags);
      ownerBuilder.setFlags(mergedFlags);
    }
    AclEntry allUsersEntry = allUsersBuilder.build();
    AclEntry replacementOwnerEntry = ownerBuilder.build();

    /*
     * Scan the ACL, replace the owner ACE with the merged one, and insert the "All Users"
     * ACE following the owner's entry.  Ideally, we'd "omit" inherited ACEs but, due to a
     * JDK shortcoming, we can't tell inherited ACEs from explicit ACEs.
     *
     * The "CREATOR_OWNER" entry, if any, is removed -- the version provided by the JDK does
     * not fully reflect the attributes observed by 'icacls' and, if included in the ACL,
     * results in the loss of access to files created in a directory which includes the
     * crippled entry.
     */
    boolean allUsersEncountered = false;
    boolean foundOwner = false;
    ListIterator<AclEntry> entryIterator = aclEntryList.listIterator();
    while (entryIterator.hasNext()) {
      AclEntry aclEntry = entryIterator.next();
      if (aclEntry.principal().equals(owner)) {
        entryIterator.set(replacementOwnerEntry);
        entryIterator.add(allUsersEntry);
        foundOwner = true;
      } else if (aclEntry.principal().equals(creatorOwnerPrincipal)) {
        entryIterator.remove();             // This is now merged into the owner ACE
      } else if (aclEntry.principal().equals(allUsersPrincipal)) {
        if (foundOwner) {
          // Once the ACL is updated, remove any "All Users" entry encountered
          entryIterator.remove();
        } else {
          // If the ACL has not yet been updated, just remember for later removal
          allUsersEncountered = true;
        }
      }
    }

    /*
     * Complete processing at the "top" of the ACL; need to remove any "All Users"
     * entries _before_ the owner's entry
     */
    if (allUsersEncountered) {
      entryIterator = aclEntryList.listIterator();
      while (entryIterator.hasNext()) {
        AclEntry aclEntry = entryIterator.next();
        if (aclEntry.principal().equals(owner)) {
          break;
        } else if (aclEntry.principal().equals(allUsersPrincipal)) {
          entryIterator.remove();
        }
      }
    }

    try {
      LOGGER.info("Updating ACL on \"{}\"", path);
      view.setAcl(aclEntryList);
      logAcl("After update", DEBUG, path);
    } catch (IOException e) {
      LOGGER.error("Unable to alter ACL for \"{}\"; permissions remain unchanged", path, e);
    }
  }

  /**
   * Updates POSIX-based permissions.  This method ensures that "other" includes all permissions
   * granted to "owner".
   *
   * @param path the file for which permissions are altered
   */
  private static void updatePosixPermissions(Path path) {
    logPermissions("Before update", DEBUG, path);

    PosixFileAttributeView view = null;
    Set<PosixFilePermission> permissions = null;
    try {
      view = getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null) {
        PosixFileAttributes attributes = view.readAttributes();
        permissions = attributes.permissions();
      }
    } catch (IOException e) {
      LOGGER.error("Unable to get permissions for \"{}\"", path, e);
    }

    if (permissions != null) {
      List<PosixFilePermission> otherPermissions = permissions.stream()
          .map(OWNER_TO_OTHER_MAPPING::get).filter(Objects::nonNull).collect(Collectors.toList());
      if (permissions.addAll(otherPermissions)) {
        try {
          LOGGER.info("Updating permissions on \"{}\"", path);
          view.setPermissions(permissions);
          logPermissions("After update", DEBUG, path);
        } catch (IOException e) {
          LOGGER.error("Unable to alter permissions for \"{}\"", path, e);
        }
      }
    }
  }

  /**
   * Maps OWNER permissions into the corresponding OTHER permission.
   */
  private static final EnumMap<PosixFilePermission, PosixFilePermission> OWNER_TO_OTHER_MAPPING;

  static {
    EnumMap<PosixFilePermission, PosixFilePermission> map = new EnumMap<>(PosixFilePermission.class);
    map.put(PosixFilePermission.OWNER_READ, PosixFilePermission.OTHERS_READ);
    map.put(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OTHERS_WRITE);
    map.put(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
    OWNER_TO_OTHER_MAPPING = map;
  }

  /**
   * Gets the {@link Path} to the identified Windows Special Folder.
   *
   * @param specialFolderId the special folder identifier
   * @param create          if {@code true}, attempts to create the folder if it does not exist
   * @return the path to the special folder; an empty path is returned if the folder does not exist
   * @throws IOException if the special folder value cannot be determined
   * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.environment.specialfolder?view=netframework-4.8">
   * Environment.SpecialFolder Enum</a>
   * @see <a href="https://docs.microsoft.com/en-us/dotnet/api/system.environment.getfolderpath?view=netframework-4.8">
   * Environment.GetFolderPath Method</a>
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
   * Logs the POSIX permissions for a {@code Path} at the logging level specified.
   *
   * @param description a description to write ahead of the logged permissions
   * @param logLevel    the level at which the ACL is to be logged
   * @param path        the path for which the ACL is logged
   */
  private static void logPermissions(String description, Level logLevel, Path path) {
    LoggerBridge bridge = LoggerBridge.getInstance(LOGGER, logLevel);
    if (bridge.isLevelEnabled()) {
      try {
        PosixFileAttributeView view =
            getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
          PosixFileAttributes attributes = view.readAttributes();
          Set<PosixFilePermission> permissions = attributes.permissions();
          bridge.log("{}: POSIX permissions for \"{}\" owned by {}:\n    [{}] {}",
              description, path, attributes.owner(), PosixFilePermissions.toString(permissions),
              permissions.stream().map(PosixFilePermission::name).collect(joining("+")));
        } else {
          bridge.log("POSIX permissions for \"{}\" not supported", path);
        }
      } catch (IOException e) {
        bridge.log("Unable to get POSIX permissions for \"{}\"", path, e);
      }
    }
  }

  /**
   * Logs the ACL for a {@code Path} at the logging level specified.  If running on Windows,
   * the {@code ICACLS} to get the Windows view of the ACL.
   *
   * @param description a description to write ahead of the logged ACL
   * @param logLevel    the level at which the ACL is to be logged
   * @param path        the path for which the ACL is logged
   */
  private static void logAcl(String description, Level logLevel, Path path) {
    LoggerBridge bridge = LoggerBridge.getInstance(LOGGER, logLevel);
    if (bridge.isLevelEnabled()) {
      try {
        AclFileAttributeView view = getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
          bridge.log("{}: ACL for \"{}\" owned by {}:\n    {}",
              description, path, view.getOwner(),
              view.getAcl().stream().map(Object::toString).collect(joining("\n    ")));
        } else {
          bridge.log("ACL for \"{}\" not supported", path);
        }
      } catch (IOException e) {
        bridge.log("Unable to get ACL for \"{}\"", path, e);
      }

      if (IS_WINDOWS) {
        try {
          Shell.Result result = Shell.execute(Shell.Encoding.CHARSET, "icacls", "\"" + path + "\"");
          if (result.exitCode() == 0) {
            bridge.log("ICACLS \"{}\"\n    {}", path,
                String.join("\n    ", result.lines()));
          } else {
            bridge.log("Failed to run ICACLS for \"{}\"\n    {}",
                path, String.join("\n    ", result.lines()));
          }
        } catch (IOException e) {
          bridge.log("Unable to run ICACLS for \"{}\"", path, e);
        }
      }
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

  /**
   * Bridge to permit variable-level use of SLF4j.
   */
  private static final class LoggerBridge {
    private static final Map<Map.Entry<Logger, Level>, LoggerBridge> INSTANCES = new HashMap<>();

    private final Logger delegate;
    private final Level level;
    private final MethodHandle isLevelEnabled;
    private final MethodHandle log;

    /**
     * Creates or gets the {@code LoggerBridge} instance for the delegate {@link Logger} and {@link Level}.
     *
     * @param delegate the {@code Logger} to which logging calls are delegated
     * @param level    the {@code Level} at which the returned {@code LoggingBridge} logs
     * @return a {@code LoggingBridge} instance
     */
    public static LoggerBridge getInstance(Logger delegate, Level level) {
      return INSTANCES.computeIfAbsent(new AbstractMap.SimpleImmutableEntry<>(delegate, level),
          e -> new LoggerBridge(e.getKey(), e.getValue()));
    }

    /**
     * Creates a {@code LoggerBridge} instance sending logging calls to the
     * designated {@link Logger} at the specified level.
     *
     * @param delegate the delegate {@code Logger}
     * @param level    the level at which the {@link #log} method records
     */
    private LoggerBridge(Logger delegate, Level level) {
      this.delegate = requireNonNull(delegate, "delegate");
      this.level = requireNonNull(level, "level");

      String levelName = level.name().toLowerCase(Locale.ROOT);
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      MethodType type;

      /*
       * Find the boolean 'is<Level>Enabled'() method.
       */
      MethodHandle isLevelEnabled;
      type = MethodType.methodType(boolean.class);
      try {
        isLevelEnabled = lookup.findVirtual(Logger.class,
            "is" + levelName.substring(0, 1).toUpperCase(Locale.ROOT) + levelName.substring(1) + "Enabled", type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        isLevelEnabled = null;
        delegate.error("Unable to resolve '{} {}({})' method on {}; will log at INFO level",
            type.returnType(), levelName, type.parameterList(), Logger.class, e);
      }
      this.isLevelEnabled = isLevelEnabled;

      /*
       * Find the void '<level>'(String, Object...) method.
       */
      MethodHandle log;
      type = MethodType.methodType(void.class, String.class, Object[].class);
      try {
        log = lookup.findVirtual(Logger.class, levelName, type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        log = null;
        delegate.error("Unable to resolve '{} {}({})' method on {}; will log at INFO level",
            type.returnType(), levelName, type.parameterList(), Logger.class, e);
      }
      this.log = log;
    }

    /**
     * Checks if the delegate logger is active for the configured level.
     *
     * @return {@code true} if the delegate logger is configured to record events of the level
     * of this {@code LoggerBridge}
     */
    public boolean isLevelEnabled() {
      if (isLevelEnabled != null) {
        try {
          return (boolean)isLevelEnabled.invokeExact(delegate);
        } catch (Throwable throwable) {
          delegate.error("Failed to call {}; presuming {} is enabled", isLevelEnabled, level, throwable);
          return true;
        }
      } else {
        return delegate.isInfoEnabled();
      }
    }

    /**
     * Submits a log event to the delegate logger at the level of this {@code LoggerBridge}.
     * If the virtual call to the log method fails, the log event is recorded at the {@code INFO}
     * level.
     *
     * @param format    the log message format
     * @param arguments the arguments for the message
     */
    public void log(String format, Object... arguments) {
      if (log != null) {
        try {
          log.invokeExact(delegate, format, arguments);
        } catch (Throwable throwable) {
          delegate.error("Failed to call {}; logging at INFO level", log, throwable);
          delegate.info(format, arguments);
        }
      } else {
        delegate.info(format, arguments);
      }
    }
  }

  /**
   * Windows Well-Known Security Identifiers (SIDs) for use with ACL operations.
   * In early days of Windows NT-based systems, there existed a collection of user and group
   * named pre-defined in the system.  But the names like "Authenticated Users", "Everyone", and
   * "CREATOR OWNER" aren't the canonical names for the concepts and have <i>changed over time</i>.
   * The names used in the current OS version must be looked up using the SID assigned to the
   * security principal.
   *
   * @see <a href="https://docs.microsoft.com/en-us/windows/win32/secauthz/well-known-sids">Well-known SIDs</a>
   * @see <a href="https://support.microsoft.com/en-us/help/243330/well-known-security-identifiers-in-windows-operating-systems>
   * Well-known security identifiers in Windows operating systems</a>
   */
  private static final class WindowsWellKnownIdentities {
    /**
     * The principal name identifying the group corresponding to the Windows
     * Security Identifier (SID) {@code S-1-5-11} -- Authenticated Users.
     */
    static final String AUTHENTICATED_USERS =
        convertStringSidToPrincipalName("S-1-5-11", "NT AUTHORITY\\Authenticated Users");

    /**
     * The principal name identifying the user corresponding to the Windows
     * Security Identifier (SID) {@code S-1-3-0} -- Creator Owner.
     */
    static final String CREATOR_OWNER =
        convertStringSidToPrincipalName("S-1-3-0", "\\CREATOR OWNER");

    /**
     * Use PowerShell to convert a string SID value to a principal name.
     *
     * @param stringSid            the string SID to convert
     * @param defaultPrincipalName the principal name to use if the conversion fails
     * @return the principal name for {@code stringSid} if available; {@code defaultPrincipalName} otherwise
     */
    private static String convertStringSidToPrincipalName(String stringSid, String defaultPrincipalName) {
      String[] command = new String[] {
          "powershell.exe",
          "-NoLogo",
          "-NoProfile",
          "-NonInteractive",
          "-Command",
          "&{$ErrorActionPreference = 'Stop'; " +
              "(New-Object System.Security.Principal.SecurityIdentifier("
              + "'" + stringSid + "'"
              + ")).Translate([System.Security.Principal.NTAccount]).Value}" };
      Shell.Result result;
      try {
        result = Shell.execute(Shell.Encoding.CHARSET, command);
      } catch (IOException e) {
        LOGGER.error("{} failed; using \"{}\"", Arrays.toString(command), defaultPrincipalName, e);
        return defaultPrincipalName;
      }

      if (result.exitCode() == 0) {
        return result.lines().get(0);
      } else {
        LOGGER.warn("Unable to obtain user/group name for security identifier {}; using \"{}\"\n    {}",
            stringSid, defaultPrincipalName,
            String.join("\n    ", result.lines()));
        return defaultPrincipalName;
      }
    }
  }
}
