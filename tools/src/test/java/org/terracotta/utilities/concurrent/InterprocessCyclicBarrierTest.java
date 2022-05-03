/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.concurrent;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.terracotta.org.junit.rules.TemporaryFolder;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Test for {@link InterprocessCyclicBarrier}.
 */
public class InterprocessCyclicBarrierTest  {

  @Rule
  public final TestName testName = new TestName();

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @SuppressWarnings("resource")
  @Test
  public void testCtorBad() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    assertThat(() -> new InterprocessCyclicBarrier(0, syncFile), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> new InterprocessCyclicBarrier(1, syncFile), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> new InterprocessCyclicBarrier(2, null), threw(instanceOf(NullPointerException.class)));
  }

  @SuppressWarnings({"EmptyTryBlock", "try"})
  @Test
  public void testCtor() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    try (InterprocessCyclicBarrier ignored = new InterprocessCyclicBarrier(2, syncFile)) {
      // empty
    }
  }

  @SuppressWarnings({"resource", "try"})
  @Test
  public void testParticipantMismatch() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    try (InterprocessCyclicBarrier outerBarrier = new InterprocessCyclicBarrier(2, syncFile)) {
      assertThat(() -> new InterprocessCyclicBarrier(3, syncFile), threw(instanceOf(IllegalStateException.class)));
      assertThat(outerBarrier::register, threw(instanceOf(BrokenBarrierException.class)));
      assertTrue(outerBarrier.isBroken());
    }
  }

  @Test
  public void testRegisterAll() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    int participantCount = 2;
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(participantCount, syncFile)) {
      List<InterprocessCyclicBarrier.Participant> participants = new ArrayList<>();
      int aggregateParticipantMask = 0;
      for (int i = 0; i < participantCount; i++) {
        InterprocessCyclicBarrier.Participant id = barrier.register();
        assertThat(Integer.bitCount(id.getParticipantMask()), is(1));
        aggregateParticipantMask |= id.getParticipantMask();
        participants.add(id);
      }
      assertThat(Integer.bitCount(aggregateParticipantMask), is(participantCount));
      assertThat(barrier::register, threw(instanceOf(IllegalStateException.class)));
      assertTrue(participants.stream().allMatch(InterprocessCyclicBarrier.Participant::isRegistered));
      assertFalse(barrier.isBroken());

      InterprocessCyclicBarrier.BarrierRecord record = barrier.get();
      assertThat(record.activeMask(), is(aggregateParticipantMask));
      assertThat(record.pendingMask(), is(aggregateParticipantMask));
    }
  }

  @Test
  public void testRegisterDeregister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {
      InterprocessCyclicBarrier.Participant id = barrier.register();
      assertTrue(id.isRegistered());

      InterprocessCyclicBarrier.BarrierRecord record = barrier.get();
      assertThat(record.participantCount(), is(2));
      assertThat(Integer.bitCount(record.activeMask()), is(1));
      assertThat(Integer.bitCount(record.pendingMask()), is(2));

      barrier.deregister(id);
      assertFalse(id.isRegistered());

      record = barrier.get();
      assertThat(Integer.bitCount(record.activeMask()), is(0));
      assertThat(Integer.bitCount(record.pendingMask()), is(1));
      assertTrue(record.isTerminating());
    }
  }

  @Test
  public void testRegisterClose() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    InterprocessCyclicBarrier.Participant id;
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {
      id = barrier.register();
      assertTrue(id.isRegistered());
    }
    assertFalse(id.isRegistered());
  }

  @SuppressWarnings({ "resource", "ResultOfMethodCallIgnored" })
  @Test
  public void testInterruptBeforeCtor() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    Thread.currentThread().interrupt();
    try {
      assertThat(() -> new InterprocessCyclicBarrier(2, syncFile), threw(instanceOf(ClosedByInterruptException.class)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void testInterruptBeforeRegister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {
      Thread.currentThread().interrupt();
      assertThat(barrier::register, threw(instanceOf(InterruptedException.class)));
      assertFalse(Thread.currentThread().isInterrupted());
      assertThat(barrier::register, threw(instanceOf(BrokenBarrierException.class)));
      assertTrue(barrier.isBroken());
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  public void testInterruptBeforeDeregister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {
      InterprocessCyclicBarrier.Participant id = barrier.register();
      Thread.currentThread().interrupt();
      try {
        id.deregister();
        assertFalse(id.isRegistered());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();   // Clear the interrupt
      }
      assertFalse(barrier.isBroken());
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  public void testInterruptBeforeClose() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    int participantCount = 2;
    List<InterprocessCyclicBarrier.Participant> participants = new ArrayList<>();
    InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(participantCount, syncFile);
    try {
      for (int i = 0; i < participantCount; i++) {
        participants.add(barrier.register());
      }

    } finally {
      Thread.currentThread().interrupt();
      try {
        barrier.close();
      } finally {
        Thread.interrupted();
      }
    }
    assertFalse(barrier.isBroken());
    assertTrue(participants.stream().noneMatch(InterprocessCyclicBarrier.Participant::isRegistered));

    InterprocessCyclicBarrier.BarrierRecord record = barrier.get();
    assertTrue(record.isTerminating());
    assertThat(record.activeMask(), is(0));
    assertThat(record.pendingMask(), is(0));
  }

  @Test
  public void testTwoThreadsCommonBarrierEarlyDeregister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    CyclicBarrier observerBarrier = new CyclicBarrier(3);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {

      // First task -- this task waits
      Future<Void> waitingTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration
        observerBarrier.await();      // Allow observer to examine barrier state
        observerBarrier.await();      // Wait for de-registeringTask to deregister
        participant.await();
        fail("Expecting BrokenBarrierException");
        return null;
      });

      // Second task -- this task just de-registers
      Future<Void> deregisteringTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration
        observerBarrier.await();      // Allow observer to examine barrier state
        participant.deregister();
        observerBarrier.await();      // Permit waitingTask to continue with wait
        return null;
      });

      observerBarrier.await();      // Align everyone to participant registration

      InterprocessCyclicBarrier.BarrierRecord record = barrier.get();
      assertThat(Integer.bitCount(record.activeMask()), is(2));
      assertThat(Integer.bitCount(record.pendingMask()), is(2));
      observerBarrier.await();      // State examination complete; let tasks continue

      observerBarrier.await();      // Let wait/de-registration continue

      assertThat(waitingTask::get, threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(BrokenBarrierException.class)))));
      assertNull(deregisteringTask.get());

      assertTrue(barrier.isBroken());
    }
  }

  @Test
  public void testSharedParticipantAwaitDeregister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    CyclicBarrier observerBarrier = new CyclicBarrier(3);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {

      InterprocessCyclicBarrier.Participant participant = barrier.register();
      int participantMask = participant.getParticipantMask();

      // First task -- this task waits
      Future<Void> waitingTask = executorService.submit(() -> {
        observerBarrier.await();      // Align everyone to thread running
        participant.await();
        fail("Expecting BrokenBarrierException");
        return null;
      });

      // Second task -- this task just de-registers
      Future<Void> deregisteringTask = executorService.submit(() -> {
        observerBarrier.await();      // Align everyone to thread running

        // Wait until participant's pending mask is cleared
        while ((barrier.get().pendingMask() & participantMask) != 0) {
          TimeUnit.MILLISECONDS.sleep(50L);
        }

        participant.deregister();
        return null;
      });

      observerBarrier.await();      // Align everyone to participant registration

      assertNull(deregisteringTask.get(15L, TimeUnit.SECONDS));
      assertThat(waitingTask::get, threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(BrokenBarrierException.class)))));

      assertTrue(barrier.isBroken());
    }
  }

  @Test
  public void testTwoThreadsCommonBarrierAwaitDeregister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    CyclicBarrier observerBarrier = new CyclicBarrier(2);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {

      int[] participantMask = new int[1];
      // First task -- this task waits
      Future<Void> waitingTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        participantMask[0] = participant.getParticipantMask();
        observerBarrier.await();      // Align everyone to participant registration
        participant.await();
        fail("Expecting BrokenBarrierException");
        return null;
      });

      // Second task -- this task just de-registers
      Future<Void> deregisteringTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration

        // Wait until participant's pending mask is cleared
        while ((barrier.get().pendingMask() & participantMask[0]) != 0) {
          TimeUnit.MILLISECONDS.sleep(50L);
        }

        participant.deregister();
        return null;
      });

      assertThat(() -> waitingTask.get(15L, TimeUnit.SECONDS), threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(BrokenBarrierException.class)))));
      assertNull(deregisteringTask.get());

      assertTrue(barrier.isBroken());
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  public void testTwoThreadsCommonBarrierInterruptedBeforeRegister() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    CyclicBarrier observerBarrier = new CyclicBarrier(3);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {

      // First task -- this task waits
      Future<Void> waitingTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration
        participant.await();
        fail("Expecting BrokenBarrierException");
        return null;
      });

      // Second task -- this task interrupts register
      Future<Void> interruptedTask = executorService.submit(() -> {
        Thread.currentThread().interrupt();
        try {
          barrier.register();
          fail("Expecting InterruptedException");
        } finally {
          Thread.interrupted();         // Reset interruption
          observerBarrier.await();      // Align everyone to participant registration
        }
        return null;
      });

      observerBarrier.await();      // Align everyone to participant registration

      assertThat(() -> waitingTask.get(15L, TimeUnit.SECONDS), threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(BrokenBarrierException.class)))));
      assertThat(interruptedTask::get, threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(InterruptedException.class)))));

      assertTrue(barrier.isBroken());
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  public void testTwoThreadsCommonBarrierInterruptedBeforeWait() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    CyclicBarrier observerBarrier = new CyclicBarrier(3);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile)) {

      // First task -- this task waits
      Future<Void> waitingTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration
        observerBarrier.await();      // Wait for interrupted thread to enter await
        participant.await();
        fail("Expecting BrokenBarrierException");
        return null;
      });

      // Second task -- this task interrupts before await
      Future<Void> interruptedTask = executorService.submit(() -> {
        InterprocessCyclicBarrier.Participant participant = barrier.register();
        observerBarrier.await();      // Align everyone to participant registration
        Thread.currentThread().interrupt();
        try {
          participant.await();
          fail("Expecting InterruptedException");
        } finally {
          observerBarrier.await();      // Permit waitingTask to continue with wait
          Thread.interrupted();         // Reset interruption
        }
        return null;
      });

      observerBarrier.await();      // Align everyone to participant registration
      observerBarrier.await();      // Let wait/interruption continue

      assertThat(() -> waitingTask.get(15L, TimeUnit.SECONDS), threw(
          allOf(
              instanceOf(ExecutionException.class),
              hasProperty("cause", instanceOf(BrokenBarrierException.class)))));
      assertThat(interruptedTask::get, threw(
          allOf(instanceOf(ExecutionException.class),
              hasProperty("cause", is(instanceOf(InterruptedException.class))))));

      assertTrue(barrier.isBroken());
    }
  }

  @Test
  public void testTwoThreadsCommonBarrierTwoWaitNormal() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    int participantCount = 2;
    int waitCount = 5;
    ExecutorService executorService = Executors.newFixedThreadPool(participantCount);
    CyclicBarrier observerBarrier = new CyclicBarrier(participantCount + 1);
    try (InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(participantCount, syncFile)) {

      List<Future<List<Integer>>> tasks = new ArrayList<>();
      for (int i = 0; i < participantCount; i++) {
        tasks.add(executorService.submit(() -> {
          List<Integer> arrivalOrders = new ArrayList<>();
          InterprocessCyclicBarrier.Participant participant = barrier.register();
          observerBarrier.await();      // Align everyone to participant registration
          for (int j = 0; j < waitCount; j++) {
            arrivalOrders.add(participant.await());
          }
          return arrivalOrders;
        }));
      }

      observerBarrier.await();      // Align everyone to participant registration

      List<List<Integer>> taskArrivalOrders = new ArrayList<>();
      for (Future<List<Integer>> future : tasks) {
        taskArrivalOrders.add(future.get());
      }

      assertFalse(barrier.isBroken());

      for (List<Integer> arrivalOrders : taskArrivalOrders) {
        assertTrue(arrivalOrders.stream().mapToInt(Integer::intValue).allMatch(i -> i >= 0 && i < participantCount));
      }

      // Ensure each arrival order is represented in each wait attempt
      assertTrue(taskArrivalOrders.stream().allMatch(l -> l.size() == waitCount));
      for (int i = 0; i < waitCount; i++) {
        int aggregateMask = 0;
        for (List<Integer> arrivalOrders : taskArrivalOrders) {
          aggregateMask |= 0x01 << arrivalOrders.get(i);
        }
        assertThat(Integer.bitCount(aggregateMask), is(participantCount));
      }
    }
  }

  @Test
  public void testCloseSetsTerminate() throws Exception {
    Path syncFile = temporaryFolder.newFile(testName.getMethodName()).toPath();
    InterprocessCyclicBarrier barrier = new InterprocessCyclicBarrier(2, syncFile);

    InterprocessCyclicBarrier.BarrierRecord record;
    try {
      record = barrier.get();
      assertFalse(record.isTerminating());
      assertFalse(record.isBroken());
    } finally {
      barrier.close();
    }

    record = barrier.get();
    assertTrue(record.isTerminating());
    assertFalse(record.isBroken());
  }
}