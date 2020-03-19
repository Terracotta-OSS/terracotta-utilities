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

import org.hamcrest.Matcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.time.Instant.now;
import static org.hamcrest.MatcherAssert.assertThat;

public final class WaitForAssert {

  private WaitForAssert() {}

  public static <T> EventualAssertion assertThatEventually(Supplier<T> value, Matcher<? super T> matcher) {
    return new EventualAssertion().and(value, matcher);
  }


  public static void assertThatCleanly(Callable<?> task, Duration timeout) throws TimeoutException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try {
      waitOrTimeout(() -> {
        try {
          task.call();
          return true;
        } catch (Throwable t) {
          failure.set(t);
          return false;
        }
      }, timeout);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    } catch (TimeoutException e) {
      e.addSuppressed(failure.get());
      throw e;
    }
  }

  public static final class EventualAssertion {

    private final Collection<Runnable> assertions = new ArrayList<>();
    private Consumer<Throwable> action = null;

    public <T> EventualAssertion and(Supplier<T> value, Matcher<? super T> matcher) {
      assertions.add(() -> assertThat(value.get(), matcher));
      return this;
    }

    /**
     * Causes an action to be taken when the assertion fails to resolve within the time specified
     * in the {@link #within(Duration)} method call.  The action receives a {@code Throwable}
     * -- the last exception thrown from the assertions provided through the
     * {@link #assertThatEventually(Supplier, Matcher)} and {@link #and(Supplier, Matcher)} methods.
     * <p>
     * Multiple {@code onFailure} actions are chained in the order specified.  Each chained action is
     * invoked; an exception thrown from any action is held until all actions are invoked then the
     * exception from the first failing action is thrown; failures from other actions are
     * added as suppressed exceptions.
     * @param action a {@code Consumer} implementing the action to take; {@code action}
     *               is passed the exception from the first failing {@link #and}
     *               condition or a {@link TimeoutException} if the {@link #within}
     *               duration is exceeded
     * @return this {@code EventualAssertion}
     */
    public EventualAssertion onFailure(Consumer<Throwable> action) {
      if (this.action == null) {
        this.action = action;
      } else {
        Consumer<Throwable> first = this.action;
        this.action = t -> {
          try {
            first.accept(t);
          } catch (Throwable t1) {
            try {
              action.accept(t);
            } catch (Throwable t2) {
              try {
                t1.addSuppressed(t2);
              } catch (Throwable ignore) {
              }
            }
            throw t1;
          }
          action.accept(t);
        };
      }
      return this;
    }

    /**
     * Causes a thread dump to be written to {@code System.err} if the {@link #within(Duration)}
     * time is exhausted.
     * @return this {@code EventualAssertion}
     */
    public EventualAssertion threadDumpOnTimeout() {
      return onFailure(t -> Diagnostics.threadDump());
    }

    /**
     * Repeatedly performs the assertion checks until all assertions succeed or the
     * specified time limit is exceeded.  If one or more {@link #onFailure} actions are specified,
     * the actions are invoked if the time limit is exceeded.  The first failure from an action
     * is added as a suppressed exception to the exception thrown for the failed test.
     * @param timeout the time permitted for the assertion conditions to be met
     * @throws TimeoutException if {@code timeout} is exceeded
     */
    public void within(Duration timeout) throws TimeoutException {
      try {
        assertThatCleanly(() -> {
          assertions.forEach(Runnable::run);
          return null;
        }, timeout);
      } catch (TimeoutException t) {
        if (action != null) {
          try {
            action.accept(t.getSuppressed()[0]);
          } catch (Throwable t1) {
            try {
              t.addSuppressed(t1);
            } catch (Throwable ignore) {
            }
          }
        }
        throw t;
      }
    }
  }

  private static void waitOrTimeout(BooleanSupplier condition, Duration timeout) throws TimeoutException, InterruptedException {
    Instant threshold = now().plus(timeout);
    Duration sleep = Duration.ofMillis(50);
    do {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(sleep.toMillis());
      sleep = sleep.multipliedBy(2);
    } while (now().isBefore(threshold));

    if (condition.getAsBoolean()) {
      return;
    }

    throw new TimeoutException();
  }
}
