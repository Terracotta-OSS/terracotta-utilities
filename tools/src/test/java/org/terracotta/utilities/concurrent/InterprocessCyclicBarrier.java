/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Implements an inter-process coordination barrier similar to {@link java.util.concurrent.CyclicBarrier CyclicBarrier}.
 * <p>
 * Unlike, {@code CyclicBarrier}, {@code InterprocessCyclicBarrier} requires each participant <i>register</i>
 * with the barrier after local instantiation.  Registration generates a {@link Participant} instance that
 * is then used to manage barrier coordination.
 *
 * <h3>Notes</h3>
 * <ul>
 *   <li>
 *     This implementation is <b>not</b> suitable for critical use -- it is currently intended for test
 *     use cases and lacks the robustness necessary for critical use.
 *   </li>
 *   <li>
 *     More than one instance of {@code InterprocessCyclicBarrier} over the same file
 *     <b>cannot</b> be used in a single JVM.  The file locking used is not effective in preventing
 *     interaction among multiple threads in a JVM -- at best,
 *     {@link java.nio.channels.OverlappingFileLockException OverlappingFileLockException} is
 *     thrown and may result in thread/process hangs.
 *   </li>
 *   <li>
 *     If a participant fails to call {@link #register()} <i>and</i> does not close its
 *     {@code InterprocessCyclicBarrier} instance, other participants in the barrier
 *     will wait forever in an {@code await} call.  The current implementation does not
 *     provide a timed wait option.
 *   </li>
 *   <li>
 *     This implementation does not provide a {@code reset()} method.
 *   </li>
 * </ul>
 *
 * @see java.util.concurrent.CyclicBarrier
 */
public class InterprocessCyclicBarrier implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(InterprocessCyclicBarrier.class);

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean broken = new AtomicBoolean(false);
  private final Set<Participant> localParticipants = new CopyOnWriteArraySet<>();

  private final int participantCount;
  private final int fullMask;
  private final Path syncFile;
  private final BarrierAccessor barrierAccessor;

  /**
   * Create a {@code InterprocessCyclicBarrier} for the indicated number of participants using the
   * file at {@code syncFile} for inter-process communication.
   * <p>
   * Every instantiation using the same {@code syncFile} must agree on the number of participants.  A failure
   * within this constructor results in a broken barrier.
   * @param participantCount the number of participants in the barrier
   * @param syncFile the file to use for inter-process synchronization
   * @throws IOException
   *        if an error is raise opening, reading, or writing to {@code syncFile}
   * @throws IllegalStateException
   *        if {@code syncFile} is already initialized with a different participant count
   */
  public InterprocessCyclicBarrier(int participantCount, Path syncFile) throws IOException {
    if (participantCount <= 1 || participantCount > Integer.SIZE) {
      throw new IllegalArgumentException("participantCount must be greater than 1 and not more than " + Integer.SIZE);
    }
    LOGGER.trace("Instantiating {}({}, {})", this.getClass().getSimpleName(), participantCount, syncFile);

    this.participantCount = participantCount;
    this.fullMask = (1 << participantCount) - 1;
    this.syncFile = requireNonNull(syncFile, "syncFile");

    this.barrierAccessor = new BarrierAccessor(this.syncFile);
    try {
      barrierAccessor.underLock((accessor, record) -> {
        if (record.participantCount() == 0) {
          /*
           * If this is the first access, initialize the sync data.
           */
          accessor
              .participantCount(participantCount)
              .generation(0)
              .activeMask(0)              // Initialized with no participants active
              .pendingMask(fullMask);     // But all potential participants pending
        } else {
          /*
           * Otherwise, ensure the provided participant count matches the existing one.
           */
          int discoveredCount = record.participantCount();
          if (discoveredCount != this.participantCount) {
            throw new IllegalStateException(
                String.format("Participant count mismatch: instance=%d, sync file (%s)=%d",
                    this.participantCount, syncFile, discoveredCount));
          }
        }
        return null;
      });
    } catch (RuntimeException | IOException e) {
      breakBarrier().ifPresent(e::addSuppressed);
      throw e;
    }
  }

  /**
   * Registers a new participant in this {@code InterprocessCyclicBarrier}.
   * @return the participant identifier
   * @throws IOException if an error is raised while writing to the sync file
   * @throws InterruptedException if the calling thread is interrupted; the barrier state becomes <i>broken</i>
   * @throws BrokenBarrierException if the barrier is broken by another participant
   * @throws IllegalStateException if all barrier participants have already registered
   */
  public synchronized Participant register() throws IOException, BrokenBarrierException, InterruptedException {
    checkClosed();
    if (Thread.interrupted()) {
      InterruptedException interruptedException = new InterruptedException();
      breakBarrier().ifPresent(interruptedException::addSuppressed);
      throw interruptedException;
    }
    checkBroken();

    int participantMask;
    try {
      participantMask = barrierAccessor.underLock((accessor, record) -> {

        /*
         * If the barrier is already broken ...
         */
        if (record.isBroken()) {
          broken.set(true);
          throw new Broken();
        }

        if (record.participantCount() != participantCount) {
          barrierAccessor.setBroken();
          broken.set(true);
          throw new IllegalStateException(
              String.format("Participant count mismatch: instance=%d, sync file (%s)=%d",
                  record.participantCount(), syncFile, participantCount));
        }

        int activeMask = record.activeMask();
        if (Integer.bitCount(activeMask) >= participantCount) {
          throw new IllegalStateException(
              String.format("All expected participants (%d) are registered with sync file (%s);"
                  + " no further registrations are permitted", participantCount, syncFile));
        }

        // Assign the participant the rightmost zero bit
        int assignedParticipantMask = ~activeMask & (activeMask + 1);

        accessor.activeMask(activeMask | assignedParticipantMask);
        accessor.pendingMask(record.pendingMask() | assignedParticipantMask);

        return assignedParticipantMask;
      });
    } catch (Broken e) {
      BrokenBarrierException exception = new BrokenBarrierException();
      exception.initCause(e);
      throw exception;
    } catch (IOException e) {
      throw new IOException("Failed to register participant with sync file " + syncFile, e);
    }

    Participant participant = new Participant(participantMask);
    localParticipants.add(participant);
    LOGGER.trace("Barrier participant registered {}", participant);
    return participant;
  }

  /**
   * Waits until all participants have called {@code await()}.
   * @see Participant#await(String)
   */
  private int await(Participant participant, String loggingContext)
      throws IOException, InterruptedException, BrokenBarrierException {
    requireNonNull(participant, "participant");
    checkClosed();
    if (Thread.interrupted()) {
      InterruptedException interruptedException = new InterruptedException();
      breakBarrier().ifPresent(interruptedException::addSuppressed);
      throw interruptedException;
    }
    checkBroken();
    LOGGER.trace("Entering await({}) for {}", loggingContext, participant);

    int participantMask = participant.participantMask;
    int[] arrivalOrder = new int[1];
    if (localParticipants.contains(participant)) {

      /*
       * Indicate, through the sync file, that the participant has reached await and is
       * no longer pending.  Remember the current generation -- the last arriving
       * participant will advance it so the current generation is complete if it's different.
       */
      int currentGeneration;
      synchronized (this) {
        try {
          currentGeneration = barrierAccessor.underLock((accessor, record) -> {
            int generation = record.generation();
            if (record.isBroken()) {
              LOGGER.trace("Barrier ({}) is BROKEN for {}", loggingContext, syncFile);
              broken.set(true);
              throw new Broken();
            } else if (record.isTerminating()) {
              LOGGER.trace("Barrier ({}) was TERMINATING, now BROKEN for {}", loggingContext, syncFile);
              barrierAccessor.setBroken();
              broken.set(true);
              throw new Broken();
            }

            int pendingMask = record.pendingMask();
            if ((pendingMask & participantMask) != 0) {
              int remaining = pendingMask & ~participantMask;
              arrivalOrder[0] = Integer.bitCount(remaining);
              if (arrivalOrder[0] == 0) {
                accessor.pendingMask(fullMask);               // Prepare pending for next cycle
                accessor.generation(generation + 1);          // Last pending; advance generation
              } else {
                accessor.pendingMask(remaining);
              }
              LOGGER.trace("Barrier ({}) arrival: arrivalOrder={}, generation={}, remaining=0x{} {}",
                  loggingContext, arrivalOrder[0], generation, Integer.toHexString(remaining), participant);
            } else {
              // The participant wasn't recorded as being active!
              throw new IllegalStateException("Participant " + Integer.lowestOneBit(participantMask)
                  + " is not pending in sync file " + syncFile);
            }

            return generation;
          });
        } catch (Broken e) {
          BrokenBarrierException exception = new BrokenBarrierException();
          exception.initCause(e);
          throw exception;
        } catch (IOException e) {
          IOException fault = new IOException("Failed to record arrival of participant "
              + Integer.lowestOneBit(participantMask) + " in sync file " + syncFile);
          breakBarrier().ifPresent(fault::addSuppressed);
          throw fault;
        }
      }

      LOGGER.trace("Barrier ({}) wait: arrivalOrder={}, generation={} {}",
          loggingContext, arrivalOrder[0], currentGeneration, participant);

      /*
       * If we're the last to arrive, our wait is done.  Otherwise, we need to poll until the
       * generation changes, the barrier is BROKEN, or the barrier is TERMINATING.  In the last
       * case, we move the barrier to BROKEN.
       */
      if (arrivalOrder[0] == 0) {
        LOGGER.trace("Barrier ({}) wait ended: arrivalOrder=0 oldGeneration={}, newGeneration=? {}",
            loggingContext, currentGeneration, participant);
        return arrivalOrder[0];
      } else {
        while (true) {
          BarrierRecord record;
          synchronized (this) {
            record = barrierAccessor.getUnderLock();
          }
          int generation = record.generation();
          if (record.isBroken()) {
            LOGGER.trace("Barrier ({}) is BROKEN for {}", loggingContext, syncFile);
            broken.set(true);
            throw new BrokenBarrierException();
          } else if (record.isTerminating() && generation == currentGeneration) {
            /*
             * Some participant has de-registered _before_ calling the matching await -- barrier is BROKEN
             */
            LOGGER.trace("Barrier ({}) was TERMINATING, now BROKEN; activeMask=0x{}, pendingMask=0x{}, generation={} {}",
                loggingContext, Integer.toHexString(record.activeMask()), Integer.toHexString(record.pendingMask()),
                generation, syncFile);
            BrokenBarrierException brokenBarrierException = new BrokenBarrierException();
            breakBarrier().ifPresent(brokenBarrierException::addSuppressed);
            throw brokenBarrierException;
          } else if (generation != currentGeneration) {
            /*
             * If generation has advanced, all participants have arrived; some may have already
             * de-registered, so the barrier may already be TERMINATING.
             */
            LOGGER.trace("Barrier ({}) wait ended: arrivalOrder={}, oldGeneration={}, newGeneration={}, isTerminating={} {}",
                loggingContext, arrivalOrder[0], currentGeneration, generation, record.isTerminating(), participant);
            return arrivalOrder[0];
          } else {
            TimeUnit.MILLISECONDS.sleep(100L);
          }
        }
      }

    } else {
      throw new IllegalStateException("Participant " + Integer.lowestOneBit(participantMask) + " is not registered");
    }
  }

  /**
   * De-registers a participant from this {@code InterprocessCyclicBarrier}.  This barrier
   * is marked as {@code TERMINATING}.
   * <p>
   * A failure to de-register a participant does not cause a broken barrier.
   * @param participant the identifier of the participant to de-register
   * @throws IOException if an error is raised while reading from or  writing to the sync file
   * @see Participant#deregister()
   */
  synchronized void deregister(Participant participant) throws IOException {
    requireNonNull(participant, "participant");
    if (closed.get()) {
      return;
    }

    boolean interrupted = Thread.interrupted();
    try {
      deregisterInternal(participant);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void deregisterInternal(Participant participant) throws IOException {
    if (localParticipants.contains(participant)) {
      LOGGER.trace("De-registering {}", participant);

      int participantMask = participant.participantMask;
      try {
        barrierAccessor.underLock((accessor, record) -> {

          int activeMask = record.activeMask();
          if ((activeMask & participantMask) != 0) {
            // Indicate the barrier is terminating if not already TERMINATING or BROKEN
            if (!record.isBroken() && !record.isTerminating()) {
              accessor.setTerminating();
              LOGGER.trace("Barrier marked terminating by {}", participant);
            }

            // Remove the participant
            accessor.pendingMask(record.pendingMask() & ~participantMask);
            accessor.activeMask(activeMask & ~participantMask);
            LOGGER.trace("Barrier participant de-registered {}", participant);
          }

          return null;
        });
      } catch (IOException e) {
        throw new IOException("Failed to de-register participant "
            + Integer.lowestOneBit(participantMask) + " from sync file " + syncFile, e);
      } finally {
        participant.registered.set(false);
        localParticipants.remove(participant);
        LOGGER.trace("Barrier participant removed {}", participant);
      }
    }
  }

  /**
   * Indicates if this {@code InterprocessCyclicBarrier} is in a broken state.  If this barrier
   * is closed, the last known broken state is returned.
   * <p>
   * This method will attempt to access the sync file if this barrier is not known to be
   * in a broken state.
   * @return {@code true} if this barrier is broken; {@code false} otherwise
   * @throws UncheckedIOException if an error is raised while reading the sync file
   */
  public boolean isBroken() {
    if (closed.get()) {
      return broken.get();
    } else if (broken.get()) {
      return true;
    } else {
      boolean interrupted = Thread.interrupted();
      BarrierRecord record;
      try {
        record = barrierAccessor.getUnderLock();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      boolean broken = record.isBroken();
      if (broken) {
        this.broken.compareAndSet(false, true);
      }
      return broken;
    }
  }

  /**
   * Change the sync file state to indicate the barrier is broken.  After recording the broken state,
   * the sync file is closed.  This method may fail without throwing an exception.
   *
   * @return the exception, if any, raised while marking broken barrier state in the sync file
   */
  private synchronized Optional<Exception> breakBarrier() {
    LOGGER.trace("Breaking barrier for {}", syncFile);
    boolean interrupted = Thread.interrupted();
    BarrierAccessor temp = this.barrierAccessor;
    try (BarrierAccessor localAccessor = (temp.isClosed() ? new BarrierAccessor(syncFile) : temp)) {
      localAccessor.underLock((accessor, record) -> {
        accessor.setBroken();
        return null;
      });
      return Optional.empty();
    } catch (Exception e) {
      LOGGER.error("Failed to mark sync file {} as broken", syncFile, e);
      return Optional.of(e);
    } finally {
      broken.set(true);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * De-registers local participants, marks this barrier as {@code TERMINATING}, and closes the sync file.
   * @throws IOException if there was an error raised while de-registering the participants or closing the sync file
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      LOGGER.trace("Closing barrier for {}", syncFile);
      boolean interrupted = Thread.interrupted();
      try {
        List<Exception> errors = new ArrayList<>();

        ArrayList<Participant> participants = new ArrayList<>(localParticipants);
        if (barrierAccessor.isClosed()) {
          /*
           * If barrierAccessor is already closed, we can't formally de-register our
           * participants.  We can, however, remove any remaining participant ids
           * and mark them no longer registered.
           */
          participants.forEach(id -> {
            localParticipants.remove(id);
            id.registered.set(false);
          });

        } else {
          /*
           * The barrierAccessor is not closed -- formally deregister the participants
           * then close the barrierAccessor.
           */
          for (Participant localParticipant : participants) {
            try {
              deregisterInternal(localParticipant);
            } catch (IOException e) {
              errors.add(e);
            }
          }

          // No participants de-registered -- mark the barrier TERMINATING
          if (participants.isEmpty()) {
            try {
              barrierAccessor.underLock((accessor, record) -> {
                if (!record.isTerminating() && !record.isBroken()) {
                  accessor.setTerminating();
                }
                return null;
              });
            } catch (IOException e) {
              errors.add(e);
            }
          }

          try {
            barrierAccessor.close();
          } catch (IOException e) {
            errors.forEach(e::addSuppressed);
            throw  e;
          }
        }

        if (!errors.isEmpty()) {
          IOException ioException = new IOException("Failed to de-register participants from " + syncFile);
          errors.forEach(ioException::addSuppressed);
          throw ioException;
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void checkBroken() throws BrokenBarrierException {
    if (broken.get()) {
      throw new BrokenBarrierException("Barrier using sync file " + syncFile + " is broken");
    }
  }

  private void checkClosed() throws IllegalStateException {
    if (closed.get()) {
      throw new IllegalStateException("Barrier using sync file " + syncFile +" is closed");
    }
  }

  /**
   * Package-private method to return the current barrier sync file content.
   * @return a new {@code BarrierRecord} holding the current state of the barrier
   */
  synchronized BarrierRecord get() throws IOException {
    boolean interrupted = Thread.interrupted();
    try (BarrierAccessor localAccessor = new BarrierAccessor(syncFile)) {
      return localAccessor.getUnderLock();
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Issue a {@code buffer.force()} and manage the exception fallout.
   * <p>
   * The implementation of {@link MappedByteBuffer#force()} can throw an {@link IOException} but lacks the
   * declaration for the checked exception.
   * JDK bug <a href="https://bugs.openjdk.java.net/browse/JDK-6539707">JDK-6539707 (fc) MappedByteBuffer.force() method throws an IOException in a very simple test</a>
   * tracks this aberrant behavior and indicates the problem is "fixed" in JDK 17 -- ultimately by wrapping the
   * {@code IOException} in an {@link UncheckedIOException}.
   * @param buffer the {@code MappedByteBuffer} on which the {@code force} method is called
   * @return {@code buffer}
   * @throws IOException if {@code force} fails to sync
   */
  @SuppressWarnings("UnusedReturnValue")
  private static MappedByteBuffer force(MappedByteBuffer buffer) throws IOException {
    return ioOp(buffer::force);
  }

  /**
   * Issue a {@code buffer.load()} and manage the exception fallout.
   * <p>
   * Unlike {@link MappedByteBuffer#force()}, the fact that {@code MappedByteBuffer.load()} can throw an undeclared
   * {@code IOException} is not (yet) recognized but the *NIX implementation can.
   * @param buffer the {@code MappedByteBuffer} on which the {@code load} method is called
   * @return {@code buffer}
   * @throws IOException if {@code load} fails
   */
  @SuppressWarnings("UnusedReturnValue")
  private static MappedByteBuffer load(MappedByteBuffer buffer) throws IOException {
    return ioOp(buffer::load);
  }

  private static MappedByteBuffer ioOp(Callable<MappedByteBuffer> ioOp) throws IOException {
    try {
      return ioOp.call();
    } catch (Exception e) {
      if (e instanceof IOException) {                   // Undeclared, expected before JDK 17
        throw (IOException)e;
      } else if (e instanceof UncheckedIOException) {   // Expected with JDK 17 and beyond
        throw ((UncheckedIOException)e).getCause();
      } else if (e instanceof RuntimeException) {       // Not expected
        throw (RuntimeException)e;
      } else {                                          // Not expected
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Identifies a registered {@code InterprocessCyclicBarrier} participant.  An instance of
   * this class is used to manage barrier coordination for the participant.
   */
  public final class Participant implements AutoCloseable {
    private final int participantMask;
    private final AtomicBoolean registered = new AtomicBoolean(true);

    private Participant(int participantMask) {
      this.participantMask = participantMask;
    }

    /**
     * Indicates if this {@code Participant} is currently registered.
     * @return {@code true} if the participant has not been de-registered
     */
    public boolean isRegistered() {
      return registered.get();
    }

    /**
     * Package-private method to obtain the participant mask.
     * @return the participant mask
     */
    int getParticipantMask() {
      return participantMask;
    }

    /**
     * Waits until all participants have called {@code await()}.  This method operates as
     * if calling {@link #await(String) await(null)}.
     *
     * @return a number corresponding to the arrival order of this {@code participant}; zero indicates the
     *      last participant to arrive and the barrier's current wait is complete
     * @throws IOException
     *        if an error is raised while reading from or writing to the sync file
     * @throws InterruptedException
     *        if this thread is interrupted before or during this method's execution
     * @throws BrokenBarrierException
     *        if this barrier is placed in a state inconsistent with normal coordination processing
     * @throws IllegalStateException
     *        if this participant is de-registered
     */
    public int await() throws BrokenBarrierException, IOException, InterruptedException {
      return await(null);
    }

    /**
     * Waits until all participants have called {@code await()}.  This method changes the associated
     * {@link InterprocessCyclicBarrier} {@code syncFile} to record the specified participant's "arrival"
     * and polls participant status until all participants have arrived or the barrier is broken.
     * <p>
     * The barrier is marked as <i>broken</i> if:
     * <ul>
     *   <li>the executing thread is interrupted</li>
     *   <li>the barrier is marked as TERMINATING on entry to {@code await}; this indicates one or more
     *   participants have de-registered without proper coordination of {@code await} calls</li>
     *   <li>the barrier is marked as TERMINATING while polling for completion and the barrier generation
     *   has not advanced; this indicates one or more participants have de-registered without reaching the
     *   current await coordination point</li>
     * </ul>
     *
     * @param loggingContext an identifier to include in trace output of the barrier; may be null
     * @return a number corresponding to the arrival order of this {@code participant}; zero indicates the
     *      last participant to arrive and the barrier's current wait is complete
     * @throws IOException
     *        if an error is raised while reading from or writing to the sync file
     * @throws InterruptedException
     *        if this thread is interrupted before or during this method's execution
     * @throws BrokenBarrierException
     *        if this barrier is placed in a state inconsistent with normal coordination processing
     * @throws IllegalStateException
     *        if this participant is de-registered
     */
    @SuppressWarnings("UnusedReturnValue")
    public int await(String loggingContext) throws BrokenBarrierException, IOException, InterruptedException {
      return InterprocessCyclicBarrier.this.await(this, loggingContext);
    }

    /**
     * De-registers this participant from the associated {@link InterprocessCyclicBarrier}.
     * The associated barrier is marked as {@code TERMINATING}.
     * <p>
     * A failure to de-register a participant does not cause a broken barrier.
     * @throws IOException if an error is raised while reading from or  writing to the sync file
     */
    public void deregister() throws IOException {
      InterprocessCyclicBarrier.this.deregister(this);
    }

    /**
     * Closes this {@code Participant} by calling {@link #deregister}.
     * @throws IOException if thrown by {@code deregister}
     */
    @Override
    public void close() throws IOException {
      this.deregister();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("Participant{");
      sb.append("participantMask=0x").append(Integer.toHexString(participantMask));
      sb.append(", syncFile=").append(InterprocessCyclicBarrier.this.syncFile);
      sb.append(", registered=").append(registered);
      sb.append('}');
      return sb.toString();
    }
  }

  /**
   * Isolates the I/O constructs needed to manipulate the barrier sync file.
   */
  @SuppressWarnings("UnusedReturnValue")
  private static final class BarrierAccessor implements AutoCloseable {

    /**
     * {@link Offsets#FLAGS FLAGS} mask indicating this barrier is broken.
     */
    private static final int BARRIER_BROKEN = 0x01;
    /**
     * {@link Offsets#FLAGS FLAGS} mask indicating this barrier is terminating.
     * The first participant calling {@link #deregister} or {@link InterprocessCyclicBarrier#close()}
     * sets this value; callers already in {@link #await} complete the wait normally iff all participants
     * have coordinated {@linkplain #await} calls; new entrants to {@linkplain #await} observe a
     * {@link BrokenBarrierException}.
     */
    private static final int BARRIER_TERMINATING = 0x02;

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final MappedByteBuffer buffer;

    private BarrierAccessor(Path syncFile) throws IOException {
      this.file = new RandomAccessFile(syncFile.toFile(), "rwd");
      this.channel = this.file.getChannel();
      this.buffer = this.channel.map(FileChannel.MapMode.READ_WRITE, 0, Offsets.size());
      load(this.buffer);
    }

    /**
     * Execute the supplied task while holding a lock on the {@code FileChannel} for this
     * {@code BarrierAccessor}.  If {@code task} completed normally, this method calls
     * {@link #force(MappedByteBuffer) force} to flush the buffer.
     * @param task the task to run
     * @return a value calculated by {@code task}
     * @param <T> the type of the return value
     * @throws IOException
     *        if an error is raised while accessing the barrier file
     */
    @SuppressWarnings("try")
    <T> T underLock(IOBiFunction<BarrierAccessor, BarrierRecord, T> task) throws IOException {
      try (FileLock ignored = channel.lock()) {
        T result = task.apply(this, this.get());
        force(buffer);
        return result;
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }

    /**
     * Gets the current {@code BarrierRecord} values while holding a lock on the {@code FileChannel}
     * for this {@code BarrierAccessor}.
     * @return a new {@code BarrierRecord} holding the current barrier values
     * @throws IOException
     *        if an error is raised while accessing the barrier file
     */
    @SuppressWarnings("try")
    BarrierRecord getUnderLock() throws IOException {
      try (FileLock ignored = channel.lock()) {
        return get();
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }

    /**
     * Gets the current {@code BarrierRecord} values for a caller already holding the appropriate lock.
     * @return a new {@code BarrierRecord} instance holding the current barrier values
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    private BarrierRecord get() {
      checkClosed();
      if (buffer.limit() == 0) {
        return null;
      } else {
        return new BarrierRecord(
            Offsets.COUNT.get(buffer),
            Offsets.FLAGS.get(buffer),
            Offsets.GENERATION.get(buffer),
            Offsets.ACTIVE_MASK.get(buffer),
            Offsets.PENDING_MASK.get(buffer)
        );
      }
    }

    /**
     * Replaces the value for the barrier participant count.
     * @param participantCount the count to set
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor participantCount(int participantCount) {
      checkClosed();
      Offsets.COUNT.put(buffer, participantCount);
      return this;
    }

    /**
     * Set this barrier as BROKEN.
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor setBroken() {
      checkClosed();
      int broken = Offsets.FLAGS.get(buffer) | BARRIER_BROKEN;
      Offsets.FLAGS.put(buffer, broken);
      return this;
    }

    /**
     * Set this barrier as TERMINATING.
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor setTerminating() {
      checkClosed();
      int terminating = Offsets.FLAGS.get(buffer) | BARRIER_TERMINATING;
      Offsets.FLAGS.put(buffer, terminating);
      return this;
    }

    /**
     * Replaces the value for the barrier generation.
     * @param generation the generation number to set
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor generation(int generation) {
      checkClosed();
      Offsets.GENERATION.put(buffer, generation);
      return this;
    }

    /**
     * Replaces the value for the barrier active participant mask.
     * @param activeMask the active mask value to set
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor activeMask(int activeMask) {
      checkClosed();
      Offsets.ACTIVE_MASK.put(buffer, activeMask);
      return this;
    }

    /**
     * Replaces the value for the barrier pending participant mask.
     * @param pendingMask the pending mask value to set
     * @return this {@code BarrierAccessor}
     * @throws UncheckedIOException if this {@code BarrierAccessor} is closed
     */
    BarrierAccessor pendingMask(int pendingMask) {
      checkClosed();
      Offsets.PENDING_MASK.put(buffer, pendingMask);
      return this;
    }

    @Override
    public void close() throws IOException {
      // Closes the FileChannel as well ...
      file.close();
    }

    /**
     * Indicates if this {@code BarrierAccessor} is closed.
     * @return {@code true} if this {@code BarrierAccessor} is closed; {@code false} if open
     */
    boolean isClosed() {
      return !channel.isOpen();
    }

    private void checkClosed() {
      if (!channel.isOpen()) {
        throw new UncheckedIOException(new ClosedChannelException());
      }
    }

    private enum Offsets {
      /**
       * The number of participants in the {@code InterprocessCyclicBarrier}.
       */
      COUNT(0),
      /**
       * Hold state flags for the {@code InterprocessCyclicBarrier}.
       * @see #BARRIER_BROKEN
       * @see #BARRIER_TERMINATING
       */
      FLAGS(4),
      /**
       * Holds the "generation" of the {@code InterprocessCyclicBarrier}.  A new barrier starts with a
       * generation of zero, advancing by one for each successful coordinated await group.
       */
      GENERATION(8),
      /**
       * A bitmask indicating the participants registered in the {@code InterprocessCyclicBarrier}.
       */
      ACTIVE_MASK(12),
      /**
       * A bitmask indicating the participants that have <b>not yet</b> called {@link InterprocessCyclicBarrier#await}
       * in the current generation.
       */
      PENDING_MASK(16),
      ;
      final int offset;

      Offsets(int offset) {
        this.offset = offset;
      }

      public int get(ByteBuffer buffer) {
        return buffer.getInt(offset);
      }

      public void put(ByteBuffer buffer, int value) {
        buffer.putInt(offset, value);
      }

      /**
       * Gets the byte length of the {@code Offsets}.
       * @return the {@code Offsets} size
       */
      public static int size() {
        Offsets[] values = values();
        int lastOffset = values[values.length - 1].offset;
        return lastOffset + Integer.BYTES;
      }
    }
  }

  @FunctionalInterface
  private interface IOBiFunction<T, U, V> {
    V apply(T t, U u) throws IOException;
  }

  private static class Broken extends IOException {
    private static final long serialVersionUID = -7618496492560957289L;
    public Broken() {
    }
  }

  // Package-private for testing
  static final class BarrierRecord {
    private final int participantCount;
    private final int flags;
    private final int generation;
    private final int activeMask;
    private final int pendingMask;

    private BarrierRecord(int participantCount, int flags, int generation, int activeMask, int pendingMask) {
      this.participantCount = participantCount;
      this.flags = flags;
      this.generation = generation;
      this.activeMask = activeMask;
      this.pendingMask = pendingMask;
    }

    boolean isBroken() {
      return (flags & BarrierAccessor.BARRIER_BROKEN) != 0;
    }

    boolean isTerminating() {
      return (flags & BarrierAccessor.BARRIER_TERMINATING) != 0;
    }

    int participantCount() {
      return participantCount;
    }

    int generation() {
      return generation;
    }

    int activeMask() {
      return activeMask;
    }

    int pendingMask() {
      return pendingMask;
    }
  }
}
