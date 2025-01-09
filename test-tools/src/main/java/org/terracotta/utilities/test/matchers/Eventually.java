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
package org.terracotta.utilities.test.matchers;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static java.time.Instant.now;
import static java.util.concurrent.Executors.callable;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 * Factory methods for creating 'eventual' matchers and runner.
 */
public class Eventually {

  /**
   * Create a {@link Timeout} of the given {@code duration}.
   *
   * @param duration timeout duration
   * @return a {@code Timeout} instance
   */
  public static Timeout within(Duration duration) {
    return new Timeout(duration);
  }

  /**
   * A factory for "eventual" {@link Matcher} and 'runner' instances.
   */
  public static final class Timeout {

    private final Duration duration;

    private Timeout(Duration duration) {
      this.duration = duration;
    }

    /**
     * Create an eventual {@code Supplier} matcher against {@code value}.
     * <p>
     * This matches any supplier that returns {@code value} within the preconfigured timeout.
     *
     * @param value the value to match
     * @param <T> type of the provided value
     * @return an eventual supplier matcher
     */
    public <T> Matcher<Supplier<? super T>> is(T value) {
      return matches(equalTo(value));
    }

    /**
     * Create an eventual {@code Supplier} matcher delegating to {@code matcher}.
     * <p>
     * This matches any supplier that returns a value matching {@code matcher} within the preconfigured timeout.
     *
     * @param matcher the matcher to match against
     * @param <T> type of the matched value
     * @return an eventual supplier matcher
     */
    public <T> Matcher<Supplier<? super T>> matches(Matcher<T> matcher) {
      return new BaseMatcher<Supplier<? super T>>() {
        private volatile Object last;

        @Override
        public boolean matches(Object item) {
          if (item instanceof Supplier) {
            Supplier<?> supplier = (Supplier<?>) item;
            try {
              runsCleanly(() -> {
                if (!matcher.matches((last = supplier.get()))) {
                  throw new InternalAssertionError();
                }
              });
              return true;
            } catch (InternalAssertionError e) {
              return false;
            }
          } else {
            last = item;
            return false;
          }
        }

        @Override
        public void describeMismatch(Object item, Description description) {
          Object lastGenerated = this.last;
          if (lastGenerated == null) {
            super.describeMismatch(item, description);
          } else {
            matcher.describeMismatch(lastGenerated, description);
          }
        }

        @Override
        public void describeTo(Description description) {
          description.appendDescriptionOf(matcher).appendText(" (within ").appendValue(duration).appendText(")");
        }
      };
    }

    /**
     * Executes the given {@code Callable} repeatedly until it returns normally.
     * <p>
     * The supplied task will be executed repeatedly until it completes cleanly or the timeout expires.
     * On successful completion the result will be return from this method.
     * If the timeout expires then the method will throw the last exception thrown from the task.
     *
     * @param task the task to execute
     * @param <T> the return type of the task
     * @return the successful task result
     * @throws Exception the exception thrown by {@code task} on timeout
     */
    public <T> T execsCleanly(Callable<T> task) throws Exception {
      Instant threshold = now().plus(duration);
      Duration sleep = Duration.ofMillis(50);
      try {
        do {
          try {
            return task.call();
          } catch (Throwable t) {
            Thread.sleep(sleep.toMillis());
            sleep = sleep.multipliedBy(2);
          }
        } while (now().isBefore(threshold));
        return task.call();
      } catch (InterruptedException e) {
        try {
          return task.call();
        } finally {
          Thread.currentThread().interrupt();
        }
      }
    }

    /**
     * Executes the given {@code Runnable} repeatedly until it returns normally.
     * <p>
     * The supplied task will be executed repeatedly until it completes cleanly or the timeout expires.
     * On successful completion the method will return normally.
     * If the timeout expires then the method will throw the last {@code Throwable} thrown from the task.
     *
     * @param task the task to execute
     */
    public void runsCleanly(Runnable task) {
      try {
        execsCleanly(callable(task));
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  private static class InternalAssertionError extends AssertionError {
    private static final long serialVersionUID = 1L;
  }
}
