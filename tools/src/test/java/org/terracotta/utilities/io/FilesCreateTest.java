/*
 * Copyright 2022-2023 Terracotta, Inc., a Software AG company.
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.terracotta.utilities.concurrent.InterprocessCyclicBarrier;
import org.terracotta.utilities.test.Diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.reflect.Modifier.isStatic;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Provides tests for the {@link Files#createFile(Path, FileAttribute[])} method.
 */
public class FilesCreateTest extends FilesTestBase {

  private static final Logger LOGGER = LoggerFactory.getLogger(FilesCreateTest.class);


  @Test
  public void testNullArgs() {
    assertThat(() -> Files.createFile(null), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> Files.createFile(null, Duration.ofSeconds(1L)), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> Files.createFile(topFile, (Duration)null), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> Files.createFile(topFile, Duration.ofSeconds(1L), (FileAttribute<?>)null), threw(instanceOf(NullPointerException.class)));
  }

  @Test
  public void testExistingFile() {
    assertThat(() -> Files.createFile(topFile), threw(instanceOf(FileAlreadyExistsException.class)));
  }

  @Test
  public void testExistingDirectory() {
    if (isWindows) {
      // Windows throws an AccessDeniedException when attempting to create a file over an existing director/directory link
      assertThat(() -> Files.createFile(top), threw(instanceOf(AccessDeniedException.class)));
    } else {
      assertThat(() -> Files.createFile(top), threw(instanceOf(FileAlreadyExistsException.class)));
    }
  }

  @Test
  public void testExistingFileLink() {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    assertThat(() -> Files.createFile(fileLink), threw(instanceOf(FileAlreadyExistsException.class)));
  }

  @Test
  public void testExistingDirectoryLink() {
    assumeTrue("Skipped because symbolic links cannot be created in current environment", symlinksSupported);
    if (isWindows) {
      // Windows throws an AccessDeniedException when attempting to create a file over an existing director/directory link
      assertThat(() -> Files.createFile(dirLink), threw(instanceOf(AccessDeniedException.class)));
    } else {
      assertThat(() -> Files.createFile(dirLink), threw(instanceOf(FileAlreadyExistsException.class)));
    }
  }

  /**
   * Tests {@link Files#createFile(Path, FileAttribute[])} when the target is a file <i>marked</i> for
   * deletion on Windows.  This test relies on an external process to open and delete the file.  (Different
   * failures are observed when this is done in-process.)
   */
  @SuppressWarnings({"try"})
  @Test
  public void testDeletedFile() throws Exception {
    assumeTrue("Skipped -- Windows-specific behavior", isWindows);

    try (MDC.MDCCloseable ignored = MDC.putCloseable("PID", Long.toString(Diagnostics.getLongPid()))) {

      Path syncFile = TEST_ROOT.newFile(testName.getMethodName() + "_syncFile").toPath();
      assertTrue(java.nio.file.Files.exists(topFile));

      Process process = spawn("backgroundDelete", syncFile.toString(), topFile.toString());
      Thread outputThread = new Thread(() -> {
        try {
          InputStream stream = process.getInputStream();
          int b;
          while ((b = stream.read()) != -1) {
            System.out.write(b);
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      outputThread.setDaemon(true);
      outputThread.start();

      try {
        try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(3, syncFile)) {
          InterprocessCyclicBarrier.Participant participant = barrier.register();

          participant.await("file open");             // Align to file open

          assertThat(() -> Files.createFile(topFile, Duration.ofMillis(250L)),
              threw(instanceOf(FileAlreadyExistsException.class)));

          participant.await("post-create-A");         // Align post-file create A attempt
          participant.await("post-close-A");          // Align post-close in first thread

          /*
           * At this point, the file is marked deleted but still exists because the second
           * thread in the background process is holding it open.  The following pair of
           * assertions may seem paradoxical but, on Windows, open files are 'marked' deleted
           * (both 'exists' == false and 'notExists' == false) but not actually deleted until
           * all file handles for the file are closed.
           */
          assertFalse("topFile is not 'marked' for deletion", java.nio.file.Files.exists(topFile));
          assertFalse("topFile is not 'marked' for deletion", java.nio.file.Files.notExists(topFile));

          assertThat(() -> Files.createFile(topFile, Duration.ofMillis(250L)),
              threw(instanceOf(AccessDeniedException.class)));

          participant.await("post-create-B");         // Align post-file create B attempt
          participant.await("post-close-B");          // Align post-close in second thread

          Files.createFile(topFile, Duration.ofMillis(250L));
        }
      } finally {
        process.waitFor();        // Ensure all background stdout is complete
      }
    }
  }

  /**
   * Off-process companion to {@link #testDeletedFile()} to set up conditions for a file
   * <i>marked</i> for deletion but not yet deleted.
   * @param syncFileName the {@link InterprocessCyclicBarrier} sync file
   * @param targetFileName the file to mark for deletion
   */
  // Used symbolically
  @SuppressWarnings({ "TryFinallyCanBeTryWithResources", "unused", "ThrowFromFinallyBlock" })
  private static void backgroundDelete(String syncFileName, String targetFileName)
      throws IOException, BrokenBarrierException, InterruptedException {

    Path syncFile = Paths.get(syncFileName);
    Path targetPath = Paths.get(targetFileName);

    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(3, syncFile)) {

      // Background, in-process thread to hold open a file handle
      Map<String, String> mdc = MDC.getCopyOfContextMap();
      FutureTask<Void> task = new FutureTask<>(() -> {
        MDC.setContextMap(mdc);
        holdOpen(barrier, targetPath);
        return null;
      });
      Thread peer = new Thread(task, "peer");
      peer.setDaemon(true);
      peer.start();

      Throwable fault = null;
      try {
        InterprocessCyclicBarrier.Participant participant = barrier.register();

        SeekableByteChannel channel = java.nio.file.Files.newByteChannel(targetPath, StandardOpenOption.DELETE_ON_CLOSE);
        try {
          assertTrue(channel.isOpen());
          participant.await("file open");             // Align to file open
          participant.await("post-create-A");         // Align post-file create A attempt
          channel.close();
          participant.await("post-close-A");          // Align post-close in this thread
          participant.await("post-create-B");         // Align post-file create B attempt
          participant.await("post-close-B");          // Align post-close in other thread
        } finally {
          channel.close();
        }
      } catch (IOException | BrokenBarrierException | InterruptedException exception) {
        fault = exception;

      } finally {
        try {
          task.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (fault != null) {
            fault.addSuppressed(cause);
            cause = fault;
          }
          if (cause instanceof Error) {
            throw (Error)cause;
          } else if (cause instanceof RuntimeException) {
            throw (RuntimeException)cause;
          } else if (cause instanceof IOException) {
            throw (IOException)cause;
          } else if (cause instanceof BrokenBarrierException) {
            throw (BrokenBarrierException)cause;
          } else if (cause instanceof InterruptedException) {
            throw (InterruptedException)cause;
          } else {
            throw new RuntimeException(cause);
          }
        }
      }
    }
  }

  /**
   * Peer of {@link #backgroundDelete(String, String)} to hold open a file handle against {@code targetPath}.
   * @param barrier the {@code InterprocessCyclicBarrier} shared with {@code backgroundDelete}
   * @param targetPath the file to open
   */
  @SuppressWarnings("try")
  private static void holdOpen(InterprocessCyclicBarrier barrier, Path targetPath)
      throws IOException, BrokenBarrierException, InterruptedException {
    InterprocessCyclicBarrier.Participant participant = barrier.register();
    try (SeekableByteChannel ignored = java.nio.file.Files.newByteChannel(targetPath)) {
      participant.await("file open");             // Align to file open
      participant.await("post-create-A");         // Align post-file create A attempt
      participant.await("post-close-A");          // Align post-close in other thread
      participant.await("post-create-B");         // Align post-file create B attempt
    }
    participant.await("post-close-B");            // Align post-close in this thread
  }

  /**
   * Spawns a Java process to execute the specified method in this class.
   * @param methodName the name of the method to run
   * @param methodArguments the arguments to pass to the method
   * @return the {@code Process} representing the spawned process
   * @throws IOException if an error is raised while spawning the process
   * @throws NoSuchMethodException if {@code methodName} does not identify a static void method in this
   *        class accepting the same number of {@code String} arguments in {@code methodArguments}
   */
  @SuppressWarnings("SameParameterValue")
  private Process spawn(String methodName, String... methodArguments) throws IOException, NoSuchMethodException {
    findMethod(methodName, methodArguments);        // Validate locally ...

    Path workingDirectory = Paths.get("").toAbsolutePath();
    Map<String, String> environment = System.getenv();

    // Pass most JVM arguments into spawned Java process
    Set<Predicate<String>> exclusions = Stream.of(
            compile("-((cp)|(classpath))=.*"),
            compile(quote("-Dvisualvm.id=") + ".*"),
            compile(quote("-Didea.test.cyclic.buffer.size=") + ".*"),
            compile("-javaagent:.*[/\\\\]idea_rt\\.jar=.*"),
            compile("-Djava\\.security\\.manager=.*"),
            compile("-Dorg\\.gradle\\.*")
        )
        .map(Pattern::asPredicate).collect(toSet());
    List<String> jvmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(a -> exclusions.stream().noneMatch(p -> p.test(a)))
        .collect(toList());
    if (!jvmArguments.isEmpty()) {
      LOGGER.info("Propagating JVM arguments: {}", jvmArguments);
    }

    List<String> commandLine = new ArrayList<>();
    commandLine.add(Paths.get(System.getProperty("java.home"), "bin", (isWindows ? "java.exe" : "java")).toAbsolutePath().toString());
    commandLine.addAll(jvmArguments);
    commandLine.addAll(Arrays.asList("-classpath", System.getProperty("java.class.path")));
    commandLine.add(this.getClass().getName());
    commandLine.add(methodName);
    commandLine.addAll(Arrays.asList(methodArguments));

    ProcessBuilder builder = new ProcessBuilder(commandLine);
    builder.redirectErrorStream(true);
    builder.directory(workingDirectory.toFile());
    builder.environment().putAll(environment);
    LOGGER.info("Starting {}", builder.command());
    return builder.start();
  }

  /**
   * Main method to extend test case execution into additional processes.
   * @param args strings as require for the test; the first argument is required; the remainder are processed
   *             by designated method:
   * <dl>
   *   <dt>{@code methodName}</dt>
   *   <dd>name of the method to run in task</dd>
   *   <dt>argN</dt>
   *   <dd>arguments as required by {@code methodName}</dd>
   * </dl>
   */
  @SuppressWarnings("try")
  public static void main(String[] args) {
    try (MDC.MDCCloseable ignored = MDC.putCloseable("PID", Long.toString(Diagnostics.getLongPid()))) {
      LOGGER.info("Entered {}.main({})", FilesCreateTest.class.getName(), Arrays.toString(args));
      if (args.length == 0) {
        LOGGER.error("No arguments received");
        // System.exit(1);
      } else {
        String methodName = args[0];
        String[] methodArguments = new String[args.length - 1];
        System.arraycopy(args, 1, methodArguments, 0, methodArguments.length);
        try {
          Method method = findMethod(methodName, methodArguments);
          LOGGER.info("Invoking {}.{}({})", FilesCreateTest.class.getName(), methodName, Arrays.toString(methodArguments));
          method.invoke(null, (Object[])methodArguments);
          LOGGER.info("Exited {}.{}({})", FilesCreateTest.class.getName(), methodName, Arrays.toString(methodArguments));
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
          LOGGER.error("Failed to execute {}.{}({})", FilesCreateTest.class.getName(), methodName, Arrays.toString(methodArguments), e);
          // System.exit(2);
        }
      }
      LOGGER.info("Exiting {}.main({})", FilesCreateTest.class.getName(), Arrays.toString(args));
    }
  }

  /**
   * Locates the specified method in this class and ensures it is a static void method accepting the
   * number of {@code String} arguments in {@code methodArgs}.
   * @param methodName the method to locale
   * @param methodArgs the arguments to check; only the count is needed
   * @return the located method
   * @throws NoSuchMethodException if the proper method is not found
   */
  private static Method findMethod(String methodName, String... methodArgs) throws NoSuchMethodException {
    Class<?>[] methodArgumentTypes = Stream.generate(() -> String.class).limit(methodArgs.length).toArray(Class<?>[]::new);
    Method method = FilesCreateTest.class.getDeclaredMethod(methodName, methodArgumentTypes);
    method.setAccessible(true);
    if (!isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
      throw new NoSuchMethodException(String.format("Target method %s.%s must be a static void method",
          FilesCreateTest.class.getName(), methodName));
    }
    return method;
  }
}