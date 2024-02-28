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

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

/**
 * Tool supporting <i>enforced</i> garbage collection of {@code MappedByteBuffer} instances.
 * <p>
 * This class is meant to aid testing involving off-heap buffer instances.  Like heap memory,
 * management of off-heap buffers relies on JVM garbage collection (GC).  Unfortunately, GC
 * cycles are initiated by heap pressure with no regard for the usage of off-heap buffers.
 * {@code GcTracker} can be used to <i>encourage</i> collection of off-heap buffers by
 * repeatedly invoking {@code System.gc} until the tracked buffer instances are collected.
 * <p>
 * The following is a sample usage pattern:
 * <pre>{@code
 *     GcTracker tracker = new GcTracker();
 *     try {
 *       ByteBuffer buffer = // allocate an off-heap buffer
 *       tracker.add(buffer);
 *
 *       // Work with the off-heap buffer
 *
 *       // Nullify the buffer reference; without nullification, the JVM can retain the reference
 *       buffer = null;
 *     } finally {
 *       // Await collection of the buffer
 *       tracker.awaitGc();
 *     }
 * }</pre>
 * When working with file-based {@code MappedByteBuffer} instances, the following pattern is effective:
 * <pre><code>
 *  GcTracker tracker = new GcTracker();
 *   try {
 *     try (FileInputStream fis = new FileInputStream(file);
 *          FileChannel fc = fis.getChannel()) {
 *
 *       /*
 *        * Create a buffer mapping the file content.  This opens an additional
 *        * file descriptor for the file that is not closed/release until the
 *        * buffer is garbage collected -- closure of the FileInputStream and
 *        * FileChannel does not release this buffer and prevents deletion or
 *        * re-use of the file on Windows.
 *        *&#x2F;
 *       int sz = (int)fc.size();
 *       MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, sz);
 *       tracker.add(bb);
 *
 *       // Work with the MappedByteBuffer
 *
 *       // Nullify the buffer reference
 *       bb = null;    // Encourages GC
 *     }
 *   } finally {
 *     tracker.awaitGc();
 *   }
 * </code></pre>
 *
 * Note, in each example, the assignment of the buffer reference to {@code null}.  Without nullification,
 * the JVM can retain a reference to the buffer and prevent garbage collection even when the reference
 * appears out of scope.
 */
public class GcTracker {

  private final Set<PhantomReference<MappedByteBuffer>> trackedRefs = new LinkedHashSet<>();
  private final ReferenceQueue<MappedByteBuffer> refQueue = new ReferenceQueue<>();

  private final long pollingMillis;

  /**
   * The maximum amount of time the non-timed {@code awaitGc} methods will poll/wait
   * for collection of the registered objects.
   */
  private final long maxWaitTime;

  /**
   * The minimum amount of time to wait for a {@code ReferenceQueue.remove(long)} operation.
   * The value, {@code 15} milliseconds, is based on the "normal" Windows timer interrupt frequency.
   */
  private static final long MINIMUM_REMOVE_WAIT_MILLIS = 15L;

  /**
   * Creates a {@code GcTracker} instance with a 100ms polling interval and a 1-minute maximum wait time.
   * Equivalent to calling {@code new GcTracker(Duration.ofMillis(100L), Duration.ofMinutes(1L))}.
   */
  public GcTracker() {
    this(ofMillis(100L), ofMinutes(1L));
  }

  /**
   * Creates a {@code GcTracker} instance using the specified polling interval.
   * The {@code pollingInterval} is converted to milliseconds for use.
   *
   * @param pollingInterval  the duration expressing the polling interval used in the
   *                         {@code awaitGc} polling loops; must between 100ms and
   *                         5s, inclusive
   * @param maxWaitTime the duration expressing the maximum amount of time a
   *                         {@code awaitGc} will pool/wait; must be between 5s and
   *                         5m, inclusive
   */
  public GcTracker(Duration pollingInterval, Duration maxWaitTime) {
    Objects.requireNonNull(pollingInterval, "pollingInterval");
    Objects.requireNonNull(maxWaitTime, "maxWaitTime");
    if (pollingInterval.compareTo(ofMillis(100L)) < 0 || 0 < pollingInterval.compareTo(ofSeconds(5L))) {
      throw new IllegalArgumentException("pollingInterval must be at least 100ms and not more than 5s");
    }
    if (maxWaitTime.compareTo(ofSeconds(5L)) < 0 || 0 < maxWaitTime.compareTo(ofMinutes(5L))) {
      throw new IllegalArgumentException("maxWaitTime must be at least 5s and not more than 5m");
    }
    this.pollingMillis = pollingInterval.toMillis();
    this.maxWaitTime = maxWaitTime.toMillis();
  }

  /**
   * Adds a {@code MappedByteBuffer} to the collection of tracked objects.
   * @param buffer the buffer to track; if the buffer instance is not a
   *               {@code MappedByteBuffer}, it is silently ignored
   * @param <B> the buffer type
   */
  public <B extends ByteBuffer> void add(B buffer) {
    if (buffer instanceof MappedByteBuffer) {
      trackedRefs.add(new PhantomReference<>((MappedByteBuffer)buffer, refQueue));
    }
  }

  /**
   * Awaits garbage collection of all buffers added to this tracker.
   * This method uses a polling loop that is not interruptible.
   * @throws WaitTimeExhaustedException if the configured maximum wait time is exhausted
   */
  public void awaitGc() {
    try {
      awaitGc(ofMillis(maxWaitTime));
    } catch (TimeoutException e) {
      throw new WaitTimeExhaustedException("Maximum wait time of " + ofMillis(maxWaitTime) + " exhausted leaving " +
          trackedRefs.size() + " buffers uncollected." +
          "\n  If this is not expected, explicitly set the buffer reference(s) to null before calling awaitGc();" +
          "\n  variable scoping may not be sufficient for allowing buffers to be reclaimed by garbage collection", e);
    }
  }

  /**
   * Awaits garbage collection of all buffers added to this tracker.
   * This method uses a polling loop that is not interruptible.
   * @param maxWaitTime the maximum amount of time to wait for garbage collection;
   *                    this value overrides the value specified with
   *                    {@link #GcTracker(Duration,Duration) GcTracker(pollingInterval,maxWaitTime)}
   * @throws TimeoutException if the wait duration/unit time is exhausted
   */
  public void awaitGc(Duration maxWaitTime) throws TimeoutException {
    Objects.requireNonNull(maxWaitTime, "maxWaitTime");
    if (maxWaitTime.isNegative() || maxWaitTime.isZero()) {
      throw new IllegalArgumentException("maxWaitTime must be more than zero");
    }

    try {
      awaitGcInternal(maxWaitTime, false);
    } catch (InterruptedException e) {
      throw new IllegalStateException("Unexpected InterruptionException", e);
    }
  }

  /**
   * Awaits garbage collection of all buffers added to this tracker.
   * This method uses an interruptible polling loop which may be
   * resumed following an interruption by repeating the call to this method.
   * @throws InterruptedException if an interrupt is raised on the current thread
   * @throws WaitTimeExhaustedException if the configured maximum wait time is exhausted
   */
  public void awaitGcInterruptibly() throws InterruptedException {
    try {
      awaitGcInterruptibly(ofMillis(maxWaitTime));
    } catch (TimeoutException e) {
      throw new WaitTimeExhaustedException("Maximum wait time of " + ofMillis(maxWaitTime) + " exhausted leaving " +
          trackedRefs.size() + " buffers uncollected." +
          "\n  If this is not expected, explicitly set the buffer reference(s) to null before calling awaitGc();" +
          "\n  variable scoping may not be sufficient for allowing buffers to be reclaimed by garbage collection", e);
    }
  }

  /**
   * Awaits garbage collection of all buffers added to this tracker.
   * This method uses an interruptible polling loop which may be
   * resumed following an interruption by repeating the call to this method.
   * @param maxWaitTime the maximum amount of time to wait for garbage collection;
   *                    this value overrides the value specified with
   *                    {@link #GcTracker(Duration,Duration) GcTracker(pollingInterval,maxWaitTime)}
   * @throws InterruptedException if an interrupt is raised on the current thread
   * @throws TimeoutException if the wait duration/unit time is exhausted
   */
  public void awaitGcInterruptibly(Duration maxWaitTime) throws InterruptedException, TimeoutException {
    Objects.requireNonNull(maxWaitTime, "maxWaitTime");
    if (maxWaitTime.isNegative() || maxWaitTime.isZero()) {
      throw new IllegalArgumentException("maxWaitTime must be more than zero");
    }

    awaitGcInternal(maxWaitTime, true);
  }

  @SuppressWarnings("removal")
  private void awaitGcInternal(Duration maxWaitTime, boolean isInterruptible)
      throws TimeoutException, InterruptedException {
    long pollingNanos = TimeUnit.MILLISECONDS.toNanos(pollingMillis);

    boolean interrupted = (!isInterruptible && Thread.interrupted());
    long deadlineNanos = System.nanoTime() + maxWaitTime.toNanos();
    try {
      while (!trackedRefs.isEmpty()) {
        long nanosRemaining = deadlineNanos - System.nanoTime();
        if (nanosRemaining <= 0) {
          throw new TimeoutException("Wait duration exhausted leaving " + trackedRefs.size() + " buffers uncollected");
        }

        long waitMillis = Math.max(TimeUnit.NANOSECONDS.toMillis(
            Math.min(nanosRemaining, pollingNanos)), MINIMUM_REMOVE_WAIT_MILLIS);
        Reference<? extends MappedByteBuffer> queuedRef = null;
        try {
          queuedRef = refQueue.remove(waitMillis);
          if (queuedRef != null) {
            if (!trackedRefs.remove(queuedRef)) {
              throw new IllegalStateException("Unexpected reference obtained from queue");
            }
          }
        } catch (InterruptedException e) {
          if (isInterruptible) {
            InterruptedException ie =
                new InterruptedException("Interrupted leaving " + trackedRefs.size() + " buffers uncollected");
            ie.initCause(e);
            throw ie;
          } else {
            interrupted = true;
          }
        }
        // queuedRef == null means the remove time limit expired or was interrupted; GC before trying again
        if (queuedRef == null) {
          System.gc();
          System.runFinalization();   // Marked for removal beyond Java 21
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Indicates that the maximum time permitted for {@link GcTracker#awaitGc()} is exhausted.
   */
  public static final class WaitTimeExhaustedException extends RuntimeException {
    private static final long serialVersionUID = -3483070765958796042L;

    public WaitTimeExhaustedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
