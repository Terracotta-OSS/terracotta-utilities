/*
 * Copyright 2024 Terracotta, Inc., a Software AG company.
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

import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.terracotta.utilities.test.matchers.Eventually.within;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Tests for {@link GcTracker}.
 */
@SuppressWarnings("UnusedAssignment")
public class GcTrackerTest {

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @After
  public void cleanup() {
    scheduler.shutdownNow();
  }

  @Test
  public void testCtor0() {
    new GcTracker();
  }

  @Test
  public void testCtor1() {
    assertThat(() -> new GcTracker(null, Duration.ofMinutes(1L)), threw(instanceOf(NullPointerException.class)));
    assertThat(() -> new GcTracker(Duration.ofMillis(100L), null), threw(instanceOf(NullPointerException.class)));

    assertThat(() -> new GcTracker(Duration.ofMillis(99L), Duration.ofMinutes(1L)), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> new GcTracker(Duration.ofMillis(TimeUnit.SECONDS.toMillis(5L) + 1), Duration.ofMinutes(1L)), threw(instanceOf(IllegalArgumentException.class)));

    assertThat(() -> new GcTracker(Duration.ofMillis(100L), Duration.ofMillis(TimeUnit.SECONDS.toMillis(5L) - 1)), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> new GcTracker(Duration.ofMillis(100L), Duration.ofMillis(TimeUnit.MINUTES.toMillis(5L) + 1)), threw(instanceOf(IllegalArgumentException.class)));

    new GcTracker(Duration.ofMillis(100L), Duration.ofSeconds(30L));
    new GcTracker(Duration.ofSeconds(5L), Duration.ofMinutes(5L));
  }

  @Test
  public void testNoBuffers() {
    GcTracker tracker = new GcTracker();
    ByteBuffer buffer;
    try {
      buffer = ByteBuffer.allocate(4096);
      tracker.add(buffer);
    } finally {
      tracker.awaitGc();
    }
  }

  @Test
  public void testSingleBuffer() {
    GcTracker tracker = new GcTracker();
    try {
      ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
      tracker.add(buffer);

      Thread currentThread = Thread.currentThread();
      scheduler.schedule(currentThread::interrupt, 500L, TimeUnit.MILLISECONDS);
      assertThat(() -> tracker.awaitGc(Duration.ofMillis(1500L)), threw(instanceOf(TimeoutException.class)));
      assertThat(Thread.currentThread().isInterrupted(), is(true));

      buffer = null;
    } finally {
      tracker.awaitGc();
      assertThat(Thread.currentThread().isInterrupted(), is(true));
    }
  }

  @Test
  public void testMaxWaitDuration() {
    GcTracker tracker = new GcTracker(Duration.ofMillis(100L), Duration.ofSeconds(5L));
    try {
      ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
      tracker.add(buffer);
    } finally {
      within(Duration.ofSeconds(6L)).runsCleanly(
          () -> assertThat(tracker::awaitGc, threw(instanceOf(GcTracker.WaitTimeExhaustedException.class))));
    }
  }

  @Test
  public void testDoubleBuffer() {
    GcTracker tracker = new GcTracker();
    try {
      ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
      tracker.add(buffer);

      buffer = ByteBuffer.allocateDirect(4096);
      tracker.add(buffer);
      buffer = null;
    } finally {
      tracker.awaitGc();
    }
  }

  @Test
  public void testBadDuration() {
    GcTracker tracker = new GcTracker();
    assertThat(() -> tracker.awaitGc(Duration.ofSeconds(-1L)), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> tracker.awaitGc(Duration.ZERO), threw(instanceOf(IllegalArgumentException.class)));
  }

  @Test
  public void testInterruptibleSingleBuffer() throws InterruptedException {
    GcTracker tracker = new GcTracker();
    try {
      ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
      tracker.add(buffer);

      Thread currentThread = Thread.currentThread();
      scheduler.schedule(currentThread::interrupt, 1L, TimeUnit.SECONDS);
      assertThat(tracker::awaitGcInterruptibly, threw(instanceOf(InterruptedException.class)));

      buffer = null;
    } finally {
      tracker.awaitGcInterruptibly();
    }
  }

  @Test
  public void testInterruptibleMaxWaitDuration() {
    GcTracker tracker = new GcTracker(Duration.ofMillis(100L), Duration.ofSeconds(5L));
    ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
    tracker.add(buffer);

    Thread currentThread = Thread.currentThread();
    scheduler.schedule(currentThread::interrupt, 1L, TimeUnit.SECONDS);
    assertThat(tracker::awaitGcInterruptibly, threw(instanceOf(InterruptedException.class)));

    within(Duration.ofSeconds(6L)).runsCleanly(
        () -> assertThat(tracker::awaitGcInterruptibly, threw(instanceOf(GcTracker.WaitTimeExhaustedException.class))));
  }

  @Test
  public void testInterruptibleTimeout() {
    GcTracker tracker = new GcTracker(Duration.ofMillis(100L), Duration.ofSeconds(5L));
    ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
    tracker.add(buffer);
    within(Duration.ofSeconds(3L)).runsCleanly(
        () -> assertThat(() -> tracker.awaitGcInterruptibly(Duration.ofSeconds(1L)), threw(instanceOf(TimeoutException.class))));
  }

  @Test
  public void testInterruptibleRepeat() {
    GcTracker tracker = new GcTracker(Duration.ofMillis(100L), Duration.ofSeconds(5L));
    ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
    tracker.add(buffer);

    Thread currentThread = Thread.currentThread();
    ScheduledFuture<?> interruptFuture =
        scheduler.scheduleAtFixedRate(currentThread::interrupt, 500L, 500L, TimeUnit.MILLISECONDS);

    int i = 0;
    try {
      while (true) {
        try {
          tracker.awaitGcInterruptibly();
          break;
        } catch (InterruptedException e) {
          if (++i >= 8) {
            buffer = null;
          }
        }
      }
    } finally {
      interruptFuture.cancel(true);
    }
  }

  @Test
  public void testInterruptibleBadDuration() {
    GcTracker tracker = new GcTracker();
    assertThat(() -> tracker.awaitGcInterruptibly(Duration.ofSeconds(-1L)), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(() -> tracker.awaitGcInterruptibly(Duration.ZERO), threw(instanceOf(IllegalArgumentException.class)));
  }
}