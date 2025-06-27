/*
 * Copyright IBM Corp. 2025
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
package org.terracotta.utilities.memory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.LoggerFactory;
import org.terracotta.utilities.test.logging.ConnectedListAppender;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;

import static java.util.Collections.newSetFromMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.terracotta.utilities.memory.MemoryInfo.formatSize;
import static org.terracotta.utilities.test.matchers.Eventually.within;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

/**
 * Tests for {@link MemoryMonitor}.
 * @author Clifford W. Johnson
 */
public class MemoryMonitorTest {

  @Rule
  public final TestName testName = new TestName();

  private static Level logLevel = null;

  @BeforeClass
  public static void setDebug() {
    // Some tests require DEBUG-level logging
    ch.qos.logback.classic.Logger memoryMonitorLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(MemoryMonitor.class);
    if (!memoryMonitorLogger.isDebugEnabled()) {
      logLevel = memoryMonitorLogger.getLevel();
      memoryMonitorLogger.setLevel(Level.DEBUG);
    }
  }

  @AfterClass
  public static void resetDebug() {
    if (logLevel != null) {
      ch.qos.logback.classic.Logger memoryMonitorLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(MemoryMonitor.class);
      memoryMonitorLogger.setLevel(logLevel);
    }
  }

  @Test
  public void testSetPolling() {
    try (ConnectedListAppender appender = ConnectedListAppender.newInstance(LoggerFactory.getLogger(MemoryMonitor.class), "INFO")) {
      MemoryMonitor.setPolling(null);
      synchronized (appender) {
        assertThat(appender.events(), not(hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage", containsString("Memory usage reporting disabled"))
            )))
        );
        appender.events().clear();
      }

      MemoryMonitor.setPolling(Duration.ofSeconds(30L));
      synchronized (appender) {
        assertThat(appender.events(), hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage",
                    stringContainsInOrder("Memory usage reporting enabled", "PT30S"))
            ))
        );
      }

      // Await the arrival of the first polling message and capture the service thread name
      assertThat(() -> {
        synchronized (appender) {
          return new ArrayList<>(appender.events());
        }
      }, within(Duration.ofSeconds(30L))
          .matches(hasItem(allOf(
              Matchers.<ILoggingEvent>hasProperty("level", equalTo(Level.INFO)),
              hasProperty("threadName", containsString("MemoryMonitor"))
          ))));
      int nextThreadGroup;
      synchronized (appender) {
        nextThreadGroup = appender.events().stream()
            .map(ILoggingEvent::getThreadName)
            .filter(n -> n.startsWith("MemoryMonitor"))
            .findFirst()
            .map(threadName -> Integer.parseInt(threadName.split("-")[1]) + 1)
            .orElseThrow(() -> new AssertionError("Failed to find thread id"));
        appender.events().clear();
      }

      MemoryMonitor.setPolling(Duration.ofSeconds(30L));
      synchronized (appender) {
        assertThat(appender.events(), hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage",
                    stringContainsInOrder("Memory usage reporting already enabled", "PT30S"))
            ))
        );
        appender.events().clear();
      }

      MemoryMonitor.setPolling(Duration.ofSeconds(60L));
      synchronized (appender) {
        assertThat(appender.events(), hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage",
                    stringContainsInOrder("Memory usage reporting enabled", "PT1M"))
            ))
        );
        appender.events().clear();
      }

      MemoryMonitor.setPolling(null);
      synchronized (appender) {
        assertThat(appender.events(), hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage",
                    containsString("Memory usage reporting disabled"))
            ))
        );
        appender.events().clear();
      }

      // Re-enable to check for "next" service thread
      MemoryMonitor.setPolling(Duration.ofSeconds(60L));
      synchronized (appender) {
        assertThat(appender.events(), hasItem(allOf(
                hasProperty("level", equalTo(Level.INFO)),
                hasProperty("formattedMessage",
                    stringContainsInOrder("Memory usage reporting enabled", "PT1M"))
            ))
        );
      }
      assertThat(() -> {
        synchronized (appender) {
          return new ArrayList<>(appender.events());
        }
      }, within(Duration.ofSeconds(30L))
          .matches(hasItem(allOf(
              Matchers.<ILoggingEvent>hasProperty("level", equalTo(Level.INFO)),
              hasProperty("threadName", containsString("MemoryMonitor-" + nextThreadGroup))
          ))));

    } finally {
      MemoryMonitor.setPolling(null);
    }
  }

  @Test
  public void testSetUsageThreshold() {
    List<MemoryPoolMXBean> usageBeans = ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(b -> b.getType().equals(MemoryType.HEAP))
        .filter(MemoryPoolMXBean::isUsageThresholdSupported)
        .collect(toList());

    Supplier<Map<String, Long>> usageThresholds = () -> usageBeans.stream().collect(
        toMap(MemoryPoolMXBean::getName, MemoryPoolMXBean::getUsageThreshold));
    Map<String, Long> originalThresholds = usageThresholds.get();

    assertThat(() -> MemoryMonitor.setUsageThreshold(200.0F), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(usageThresholds.get(), is(originalThresholds));

    Function<Float, Map<String, Long>> calculateUsageThresholds =
        percentage -> usageBeans.stream()
            .collect(toMap(MemoryPoolMXBean::getName, b -> {
              long poolMax = b.getUsage().getMax();
              long maxMemory = poolMax == -1 ? Runtime.getRuntime().maxMemory() : poolMax;
              return (long)Math.ceil(maxMemory * percentage / 100.0F);
            }));

    try {
      MemoryMonitor.setUsageThreshold(5.0F);
      assertThat(usageThresholds.get(), is(calculateUsageThresholds.apply(5.0F)));

      // Now reset to original
      MemoryMonitor.setUsageThreshold(-1.0F);
      assertThat(usageThresholds.get(), is(originalThresholds));

      MemoryMonitor.setUsageThreshold(0.0F);
      assertThat(usageThresholds.get().values(), everyItem(is(0L)));

    } finally {
      MemoryMonitor.setUsageThreshold(-1.0F);   // Reset to previous values
    }
  }

  @Test
  public void testSetCollectionThreshold() {
    List<MemoryPoolMXBean> usageBeans = ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(b -> b.getType().equals(MemoryType.HEAP))
        .filter(MemoryPoolMXBean::isCollectionUsageThresholdSupported)
        .collect(toList());

    Supplier<Map<String, Long>> usageThresholds = () -> usageBeans.stream().collect(
        toMap(MemoryPoolMXBean::getName, MemoryPoolMXBean::getCollectionUsageThreshold));
    Map<String, Long> originalThresholds = usageThresholds.get();

    assertThat(() -> MemoryMonitor.setCollectionThreshold(200.0F), threw(instanceOf(IllegalArgumentException.class)));
    assertThat(usageThresholds.get(), is(originalThresholds));

    Function<Float, Map<String, Long>> calculateUsageThresholds =
        percentage -> usageBeans.stream()
            .collect(toMap(MemoryPoolMXBean::getName, b -> {
              long poolMax = b.getUsage().getMax();
              long maxMemory = poolMax == -1 ? Runtime.getRuntime().maxMemory() : poolMax;
              return (long)Math.ceil(maxMemory * percentage / 100.0F);
            }));

    try {
      MemoryMonitor.setCollectionThreshold(5.0F);
      assertThat(usageThresholds.get(), is(calculateUsageThresholds.apply(5.0F)));

      // Now reset to original
      MemoryMonitor.setCollectionThreshold(-1.0F);
      assertThat(usageThresholds.get(), is(originalThresholds));

      MemoryMonitor.setCollectionThreshold(0.0F);
      assertThat(usageThresholds.get().values(), everyItem(is(0L)));

    } finally {
      MemoryMonitor.setCollectionThreshold(-1.0F);   // Reset to previous values
    }
  }

  @Test
  public void testSimpleRegisterDeregister() throws Exception {
    NotificationListener listener = (notification, handback) -> {};
    MemoryMonitor.registerListener(listener);
    assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    MemoryMonitor.deregisterListener(listener);
    assertThat(MemoryMonitor.getListeners(), is(empty()));
  }

  @Test
  public void testLessSimpleRegisterDeregister() throws Exception {
    NotificationListener listener = (notification, handback) -> {};
    MemoryMonitor.registerListener(listener);
    assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    // Shows a listener added without a filter/handback can be removed specifying nulls
    MemoryMonitor.deregisterListener(listener, null, null);
    assertThat(MemoryMonitor.getListeners(), is(empty()));
  }

  @Test
  public void testFullRegisterDeregister() throws Exception {
    NotificationListener listener = (notification, handback) -> {};
    NotificationFilter filter = notification -> true;
    Object handback = new byte[0];
    MemoryMonitor.registerListener(listener, filter, handback);
    assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    assertThat(() -> MemoryMonitor.deregisterListener(listener, null, null),
        threw(instanceOf(ListenerNotFoundException.class)));
    assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    MemoryMonitor.deregisterListener(listener);
    assertThat(MemoryMonitor.getListeners(), is(empty()));

    MemoryMonitor.registerListener(listener, filter, handback);
    assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    MemoryMonitor.deregisterListener(listener, filter, handback);
    assertThat(MemoryMonitor.getListeners(), is(empty()));
  }

  @SuppressWarnings("try")
  @Test
  public void testSimpleRegisterClose() {
    NotificationListener listener = (notification, handback) -> {};
    try (MemoryMonitor.Handle ignored = MemoryMonitor.registerListener(listener)) {
      assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    }
    assertThat(MemoryMonitor.getListeners(), is(empty()));
  }

  @SuppressWarnings("try")
  @Test
  public void testFullRegisterClose() {
    NotificationListener listener = (notification, handback) -> {};
    NotificationFilter filter = notification -> true;
    Object handback = new byte[0];
    try (MemoryMonitor.Handle ignored = MemoryMonitor.registerListener(listener, filter, handback)) {
      assertThat(MemoryMonitor.getListeners(), is(singletonList(listener)));
    }
    assertThat(MemoryMonitor.getListeners(), is(empty()));
  }

  @SuppressWarnings("try")
  @Test
  public void testNotifications() throws Exception {
    float thresholdPercentage = 5.0F;
    float consumptionPercentage = 0.75F;

    List<Notification> notifications = new CopyOnWriteArrayList<>();
    NotificationListener listener = (notification, handback) -> notifications.add(notification);

    try (MemoryMonitor.Handle ignored = MemoryMonitor.registerListener(listener);
         ConnectedListAppender appender =
             ConnectedListAppender.newInstance(LoggerFactory.getLogger(MemoryMonitor.class), "INFO")) {

      MemoryMonitor.setUsageThreshold(thresholdPercentage);
      MemoryMonitor.setCollectionThreshold(thresholdPercentage);

      AtomicBoolean stop = new AtomicBoolean(false);
      Thread allocator = memoryEater(consumptionPercentage, stop);

      // Await arrival of logged collection and usage threshold events
      assertThat(() -> {
        synchronized (appender) {
          return new ArrayList<>(appender.events());
        }
      }, within(Duration.ofSeconds(30L))
          .matches(Matchers.<ILoggingEvent>hasItems(
              allOf(hasProperty("level", equalTo(Level.WARN)),
                  hasProperty("formattedMessage", containsString("collectionCount="))),
              allOf(hasProperty("level", equalTo(Level.WARN)),
                  hasProperty("formattedMessage", containsString("usageCount=")))
          )));

      // Await arrival of collection and usage threshold notifications
      assertThat(() -> notifications, within(Duration.ofSeconds(30L))
          .matches(Matchers.<Notification>hasItems(
              hasProperty("type", equalTo(MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED)),
              hasProperty("type", equalTo(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED))
          )));

      stop.set(true);   // Seen what we're looking for -- stop the storage consumption
      allocator.join(TimeUnit.SECONDS.toMillis(30L));

    } finally {
      MemoryMonitor.setCollectionThreshold(-1.0F);
      MemoryMonitor.setUsageThreshold(-1.0F);
    }
  }

  @Test
  public void testHandle() {
    float usageThreshold1 = 10.0F;
    float collectionThreshold = 5.0F;

    List<Notification> notificationsA = new CopyOnWriteArrayList<>();
    NotificationListener listenerA = (notification, handback) -> notificationsA.add(notification);
    List<Notification> notificationsB = new CopyOnWriteArrayList<>();
    NotificationListener listenerB = (notification, handback) -> notificationsB.add(notification);

    try (MemoryMonitor.Handle handle = MemoryMonitor.registerListener(listenerA);
         ConnectedListAppender appender =
             ConnectedListAppender.newInstance(LoggerFactory.getLogger(MemoryMonitor.class), "DEBUG")) {
      assertThat(MemoryMonitor.getListeners(), hasSize(1));
      assertThat(MemoryMonitor.getUsageThresholdCount(), is(anEmptyMap()));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), is(anEmptyMap()));

      handle.setUsageThreshold(usageThreshold1);
      synchronized (appender) {
        assertThat(appender.events(), hasItem(
            allOf(hasProperty("level", equalTo(Level.DEBUG)),
                hasProperty("formattedMessage", containsString("usage threshold")))
        ));
      }
      assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getUsageThresholdCount(), hasEntry(usageThreshold1, 1));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), is(anEmptyMap()));

      handle.setCollectionThreshold(collectionThreshold);
      synchronized (appender) {
        assertThat(appender.events(), hasItem(
            allOf(hasProperty("level", equalTo(Level.DEBUG)),
                hasProperty("formattedMessage", containsString("collection threshold")))
        ));
      }
      assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getUsageThresholdCount(), hasEntry(usageThreshold1, 1));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), hasEntry(collectionThreshold, 1));


      float usageThreshold2 = usageThreshold1 + 2.0F;
      handle.setUsageThreshold(usageThreshold2);
      assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getUsageThresholdCount(), hasEntry(usageThreshold2, 1));

      try (MemoryMonitor.Handle inner = MemoryMonitor.registerListener(listenerB)) {
        inner.setUsageThreshold(usageThreshold1);

        assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(2)));
        assertThat(MemoryMonitor.getUsageThresholdCount(),
            allOf(hasEntry(usageThreshold1, 1), hasEntry(usageThreshold2, 1)));
        assertThat(MemoryMonitor.getCollectionThresholdCount(), is(aMapWithSize(1)));
        assertThat(MemoryMonitor.getCollectionThresholdCount(), hasEntry(collectionThreshold, 1));

        usageThreshold2 = usageThreshold1 - 2.0F;
        handle.setUsageThreshold(usageThreshold2);
        assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(2)));
        assertThat(MemoryMonitor.getUsageThresholdCount(),
            allOf(hasEntry(usageThreshold1, 1), hasEntry(usageThreshold2, 1)));
      }

      assertThat(MemoryMonitor.getUsageThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getUsageThresholdCount(), hasEntry(usageThreshold2, 1));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), is(aMapWithSize(1)));
      assertThat(MemoryMonitor.getCollectionThresholdCount(), hasEntry(collectionThreshold, 1));

    }

    assertThat(MemoryMonitor.getListeners(), hasSize(0));
    assertThat(MemoryMonitor.getUsageThresholdCount(), is(anEmptyMap()));
    assertThat(MemoryMonitor.getCollectionThresholdCount(), is(anEmptyMap()));
  }

  @Test
  public void testDereferenceClose() throws Exception {
    float threshold = 5.0F;
    List<Notification> notifications = new CopyOnWriteArrayList<>();
    NotificationListener listener = (notification, handback) -> notifications.add(notification);

    Duration oldDereferenceInterval = MemoryMonitor.setDereferenceInterval(Duration.ofSeconds(1L));
    try (ConnectedListAppender appender =
             ConnectedListAppender.newInstance(LoggerFactory.getLogger(MemoryMonitor.class), "DEBUG")) {

      try {
        {
          // No use of Handle -- not subject to dereference de-registration
          MemoryMonitor.Handle handle = MemoryMonitor.registerListener(listener);
          assertThat(MemoryMonitor.getListeners(), hasSize(1));
          handle = null;
        }
        System.gc();
        assertThat(MemoryMonitor.getListeners(), hasSize(1));
      } finally {
        MemoryMonitor.deregisterListener(listener);
      }
      assertThat(MemoryMonitor.getListeners(), is(empty()));

      synchronized (appender) {
        appender.events().clear();
      }

      try {
        {
          // Handle used; dereference subject to de-registration
          MemoryMonitor.Handle handle = MemoryMonitor.registerListener(listener);
          assertThat(MemoryMonitor.getListeners(), hasSize(1));
          handle.setUsageThreshold(threshold);
          synchronized (appender) {
            assertThat(appender.events(), hasItem(
                allOf(hasProperty("level", equalTo(Level.DEBUG)),
                    hasProperty("formattedMessage", containsString("usage threshold")))
            ));
          }
          handle = null;
        }
        assertThat(() -> {
          System.gc();
          return MemoryMonitor.getListeners();
        }, within(Duration.ofSeconds(30L)).matches(is(empty())));
      } finally {
        try {
          MemoryMonitor.deregisterListener(listener);
        } catch (ListenerNotFoundException ignored) {
        }
      }
      assertThat(MemoryMonitor.getListeners(), is(empty()));

    } finally {
      MemoryMonitor.setDereferenceInterval(oldDereferenceInterval);
    }
  }

  /**
   * Consumes the specified percentage of heap memory.
   * @param consumptionPercentage the percentage of heap memory to consume
   * @param stop the {@code AtomicBoolean} used to stop the memory consumption thread
   * @return the memory consumptio {@code Thread}
   */
  private Thread memoryEater(float consumptionPercentage, AtomicBoolean stop) {
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    long bytesToConsume = (long)Math.ceil(memoryMXBean.getHeapMemoryUsage().getInit() * consumptionPercentage);
    int chunkSize = (int)Math.min(bytesToConsume / 10, 128L * 1024 * 1024);
    System.out.format("%s is to consume %s in %s chunks%n",
        testName.getMethodName(), formatSize(bytesToConsume), formatSize(chunkSize));

    int heldCount = 20;
    Set<IdentityWrapper<ByteBuffer>> heldBuffers =
        newSetFromMap(new LinkedHashMap<IdentityWrapper<ByteBuffer>, Boolean>(heldCount) {
          private static final long serialVersionUID = -1039378651716638057L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<IdentityWrapper<ByteBuffer>, Boolean> eldest) {
            return size() > heldCount;
          }
        });

    // Consume some heap in the background
    Thread allocator = new Thread(() -> {
      for (long l = chunkSize; l < bytesToConsume && !stop.get(); l += chunkSize) {
        heldBuffers.add(new IdentityWrapper<>(ByteBuffer.allocate(chunkSize)));
        System.gc();    // Encourage collection to generate a collection notification
        try {
          TimeUnit.MILLISECONDS.sleep(100L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }

    }, this.getClass().getSimpleName() + " Allocator");
    allocator.setDaemon(true);
    allocator.start();
    return allocator;
  }

  private static final class IdentityWrapper<T> {
    private final T wrapped;

    private IdentityWrapper(T wrapped) {
      this.wrapped = wrapped;
    }

    @SuppressWarnings("unused")
    public T wrapped() {
      return wrapped;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      return this.wrapped == ((IdentityWrapper<?>)o).wrapped;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(wrapped);
    }
  }
}