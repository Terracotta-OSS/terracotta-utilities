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
package org.terracotta.utilities.test;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

/**
 * Rudimentary tests for {@link Diagnostics} methods.
 */
public class DiagnosticsTest {

  @Test
  public void testDumpThreads() throws Exception {

    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      Thread blockedThread = new Thread(() -> {
        try {
          barrier.await();
        } catch (InterruptedException | BrokenBarrierException ignored) {
        }
      }, "Blocked Thread");
      blockedThread.setDaemon(true);
      blockedThread.start();

      Thread terminatedThread = new Thread(() -> {}, "Terminated Thread");
      terminatedThread.setDaemon(true);
      terminatedThread.start();
      terminatedThread.join();

      Thread currentThread = Thread.currentThread();
      String currentThreadName = "\"" + currentThread.getName() + "\"";
      List<Thread> threads = Arrays.asList(blockedThread, terminatedThread, currentThread);

      assertThat(perform(out -> Diagnostics.dumpThreads(threads, true, out)),
          containsInRelativeOrder(
              allOf(startsWith("\"Blocked Thread\""), containsString("WAITING on")),
              allOf(startsWith("\"Terminated Thread\""), containsString("TERMINATED")),
              allOf(startsWith(currentThreadName), containsString("RUNNABLE"))
          ));

      assertThat(perform(out -> Diagnostics.dumpThreads(threads, false, out)),
          containsInRelativeOrder(
              allOf(startsWith("\"Blocked Thread\""), containsString("WAITING")),
              allOf(startsWith("\"Terminated Thread\""), containsString("TERMINATED")),
              allOf(startsWith(currentThreadName), containsString("RUNNABLE"))
          ));

    } finally {
      barrier.reset();
    }
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testGetPid() {
    long pid = Diagnostics.getLongPid();
    assertThat(pid, is(not(-1L)));
    int intPid = Diagnostics.getPid();      // deprecation
    assertThat(intPid, is((int)pid));
  }

  private List<String> perform(Consumer<PrintStream> task) {
    try {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream(4096);
      try (PrintStream out = new PrintStream(byteStream, true, StandardCharsets.UTF_8.name())) {
        task.accept(out);
      }

      try (BufferedReader reader =
               new BufferedReader(new StringReader(byteStream.toString(StandardCharsets.UTF_8.name())))) {
        return reader.lines().collect(toList());
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}