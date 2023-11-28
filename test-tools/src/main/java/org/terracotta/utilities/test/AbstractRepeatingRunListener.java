/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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

import org.junit.runner.notification.RunListener;

/**
 * Base {@code RunListener} implementation for {@link AbstractRepeatingTest} implementations.
 *
 * @see <a href="https://junit.org/junit4/javadoc/latest/org/junit/runner/notification/RunListener.html">org.junit.runner.notification.RunListener</a>
 */
public abstract class AbstractRepeatingRunListener extends RunListener {
  private int iteration = 0;

  /**
   * Sets the current test iteration number.  This method called by {@link AbstractRepeatingTest}
   * to inform this listener of the current repeat iteration.
   * @param iteration the iteration number
   */
  final void setIteration(int iteration) {
    this.iteration = iteration;
  }

  /**
   * Gets the current test repeat iteration number.  The value returned is set by
   * {@link AbstractRepeatingTest} when starting a repeated test cycle.
   * @return the current test iteration number
   */
  public final int iteration() {
    return iteration;
  }
}
