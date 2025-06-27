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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.terracotta.utilities.memory.MemoryInfo.formatSize;

/**
 * A simple Java memory monitor.
 * <p>
 * This class provides three capabilities:
 * <ul>
 *   <li>periodically logging heap and off-heap memory consumption; this is enabled using {@link #setPolling};</li>
 *   <li>logging and notification of excess heap usage by registering a {@code NotificationListener} and using:
 *     <ul>
 *       <li><i>managed</i> thresholds using the {@link Handle#setUsageThreshold(float)} method (recommended) or</li>
 *       <li><i>unmanaged</i> thresholds using the {@link #setUsageThreshold(float)} method</li>
 *     </ul>
 *   </li>
 *   <li>logging and notification of excess heap remaining after a collection cycle by registering a
 *      {@code NotificationListener} and using:
 *     <ul>
 *       <li><i>managed</i> thresholds using the {@link Handle#setCollectionThreshold(float)} method (recommended) or</li>
 *       <li><i>unmanaged</i> thresholds using the {@link #setCollectionThreshold(float)} method</li>
 *     </ul>
 *   </li>
 * </ul>
 * De-registering a {@code NotificationListener} is done using the {@link Handle#close()} method or the
 * {@link #deregisterListener} methods; use of the {@code Handle.close} method is preferred.
 * <b>The {@code NotificationListener} de-registers itself if, after using either the {@link Handle#setUsageThreshold}
 * or {@link Handle#setCollectionThreshold} methods, the {@code Handle} instance returned by
 * {@link #registerListener} becomes weakly referenced.</b>
 * <p>
 * Thresholds are set using a percentage of the <i>maximum</i> memory available to each pool for which a
 * threshold can be set (as determined by {@code MemoryPoolMXBean.isUsageThresholdSupported()} and
 * {@code MemoryPoolMXBean.isCollectionUsageThresholdSupported()} methods. The maximum used is the value of
 * {@code MemoryUsage.getMax}, if defined, for each pool.  If {@code MemoryUsage.getMax} is not defined,
 * {@code Runtime.maxMemory} is used.
 *
 * <h3>Usage Caution</h3>
 * <h4>Memory statistics and thresholds are JVM-wide</h4>
 * This class uses {@code java.lang.management.MemoryMXBean}, {@code java.lang.management.MemoryPoolMXBean}, and
 * {@code java.lang.management.GarbageCollectorMXBean} instances to interact with the JVM memory management
 * infrastructure.  These beans provide a JVM-wide view of memory management and provide no means of localizing
 * memory use to a thread (or collection of threads).  Setting usage and collection thresholds is, as a result,
 * also JVM-wide and, unless all threshold setters are cooperative, threshold setters will interfere with each other.
 * <p>
 * If all threshold setters use a {@link #registerListener} method and the {@link Handle#setUsageThreshold}
 * and {@link Handle#setCollectionThreshold} methods, threshold interference is <i>managed</i>:
 * <ul>
 *   <li>the more sensitive of the thresholds set using these methods is used when setting the JVM-wide threshold;</li>
 *   <li>notifications are presented to {@code NotificationListener} instances
 *      if its {@code Handle}-set threshold permits.</li>
 * </ul>
 * Use of the <i>unmanaged</i> {@link #setUsageThreshold} and {@link #setCollectionThreshold} methods or
 * direct calls to the {@code java.lang.management.MemoryPoolMXBean.setUsageThreshold} and
 * {@code java.lang.management.MemoryPoolMXBean.setCollectionUsageThreshold(long)} methods are not recommended.
 *
 * @author Clifford W. Johnson
 */
public final class MemoryMonitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(MemoryMonitor.class);

  private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

  // Comments in sun.management.MemoryImpl.getMemoryPools (the source of the MemoryPoolMXBean instances)
  // indicate dynamic changes to the memory manager and memory pool collection are not supported but
  // java.lang.management.ManagementFactory.getMemoryPoolMXBeans says the JVM "may add or remove memory
  // pools during execution".
  //
  // MemoryPoolMXBeans can
  //   1) Support usage (allocation) threshold and not collection usage threshold
  //   2) Support collection usage threshold and not usage (allocation) threshold
  //   3) Support neither usage (allocation) nor collection usage thresholds
  // For example, when the G1 collector is managing memory:
  //     ---Pool----  -Usage-  -Collection-
  //     G1 Eden       false      true
  //     G1 Old Gen    true       true
  //     G1 Survivor   false      true
  private static final List<MemoryPoolMXBean> HEAP_BEANS =
      unmodifiableList(ManagementFactory.getMemoryPoolMXBeans().stream()
          .filter(b -> b.getType().equals(MemoryType.HEAP))
          .collect(toList()));
  private static final List<MemoryPoolInfo> POOL_INFO =
      unmodifiableList(HEAP_BEANS.stream()
          .filter(memoryPoolMXBean -> memoryPoolMXBean.isUsageThresholdSupported() || memoryPoolMXBean.isCollectionUsageThresholdSupported())
          .map(MemoryPoolInfo::new)
          .collect(toList()));

  private static final Long MAX_DIRECT_MEMORY_SIZE = getMaxDirectMemory();

  private static final byte[] LOCK = new byte[0];
  private static volatile ScheduledExecutorService pollingExecutor = null;
  private static ScheduledFuture<?> pollingFuture = null;
  private static Duration pollingInterval = null;
  private static Duration dereferenceInterval = Duration.ofSeconds(10L);
  private static ScheduledFuture<?> dereferenceFuture = null;

  private static final List<WrappedMemoryListener> REGISTERED_LISTENERS = new ArrayList<>();
  /** Holds a {@code Threshold} instance for each active, managed usage threshold. */
  private static final NavigableMap<Float, Set<Threshold>> ACTIVE_USAGE_THRESHOLDS = new TreeMap<>();
  /** Holds a {@code Threshold} instance for each active, managed collection threshold. */
  private static final NavigableMap<Float, Set<Threshold>> ACTIVE_COLLECTION_THRESHOLDS = new TreeMap<>();

  private MemoryMonitor() {
    // Prevent instantiation
  }

  /**
   * Registers a listener to receive notifications when the heap threshold limit established by
   * {@link #setUsageThreshold} or collection threshold limit established by {@link #setCollectionThreshold}
   * is exceeded.
   * @param listener the listener to receive notifications
   * @return an auto-closable {@code Handle} through which the listener may be deregistered
   * @see MemoryMXBean
   * @see MemoryPoolMXBean
   * @see NotificationEmitter
   */
  public static Handle registerListener(NotificationListener listener) {
    return registerListener(new WrappedMemoryListener(listener));
  }

  /**
   * Registers a listener with optional filter and handback token to receive notifications when the heap
   * threshold limit established by {@link #setUsageThreshold} or collection threshold limit established
   * by {@link #setCollectionThreshold} is exceeded.
   * @param listener the listener to receive notifications
   * @param filter   a {@code NotificationFilter} instance, possibly {@code null}, used to limit the events
   *                 presented to {@code listener}
   * @param handback reference to an object to pass, unaltered, to the
   *                 {@link NotificationListener#handleNotification} method
   * @return an auto-closable {@code Handle} through which the listener may be deregistered
   * @see MemoryMXBean
   * @see MemoryPoolMXBean
   * @see NotificationEmitter
   */
  public static Handle registerListener(NotificationListener listener, NotificationFilter filter, Object handback) {
    return registerListener(new WrappedMemoryListener(listener, filter, handback));
  }

  /**
   * Removes listeners previously registered using {@link #registerListener}.
   * This method de-registers all registrations of {@code listener}.
   * <b>Use of {@link Handle#close()} is preferred.</b>
   * @param listener the listener to remove
   * @throws ListenerNotFoundException if the listener is not registered
   */
  public static void deregisterListener(NotificationListener listener) throws ListenerNotFoundException {
    if (!deregisterListener(wrappedListener ->
        wrappedListener.matches(listener), WrappedMemoryListener::deregisterAll)) {
      throw new ListenerNotFoundException("Listener " + listener + " not registered");
    }
  }

  /**
   * Removes a listener/filter/handback pairing previously registered using {@link #registerListener}.
   * <b>Use of {@link Handle#close()} is preferred.</b>
   * @param listener the listener to remove
   * @param filter a {@code NotificationFilter} instance with which {@code listener} was registered
   * @param handback reference to a handback object with which {@code listener} was registered
   * @throws ListenerNotFoundException if the listener is not registered
   */
  public static void deregisterListener(NotificationListener listener, NotificationFilter filter, Object handback)
      throws ListenerNotFoundException {
    if (!deregisterListener(wrappedListener ->
            wrappedListener.matches(listener, filter, handback), WrappedMemoryListener::deregister)) {
      throw new ListenerNotFoundException("Listener " + listener + " not registered with filter "
          + filter + " and handback " + handback);
    }
  }

  /**
   * Sets the usage (allocation) threshold for monitored heap memory pools.  The
   * threshold is set for all heap pools supporting usage threshold monitoring.
   * <b>Use of {@link Handle#setUsageThreshold(float)} is preferred.</b>
   * @param percentage the usage (allocation) threshold, as a percentage of the maximum capacity,
   *                   over which a notification is generated; less than zero, the
   *                   threshold is reset to the value observed when this class was loaded;
   *                   if equal to zero, usage threshold checking is disabled
   * @throws IllegalArgumentException if {@code percentage} is greater than 100.0
   * @see Handle#setUsageThreshold(float)
   * @see MemoryPoolMXBean#setUsageThreshold(long)
   */
  public static void setUsageThreshold(float percentage) throws IllegalArgumentException {
    setThreshold(percentage, ThresholdType.USAGE);
  }

  /**
   * Sets the collection usage threshold for monitored heap memory pools.  The
   * threshold is set for all heap pools supporting collection threshold monitoring.
   * <b>Use of {@link Handle#setCollectionThreshold(float)} is preferred.</b>
   * @param percentage the collection usage threshold, as a percentage of the maximum capacity,
   *                   over which a notification is generated; less than zero, the
   *                   threshold is reset to the value observed when this class was loaded;
   *                   if equal to zero, usage threshold checking is disabled
   * @throws IllegalArgumentException if {@code percentage} is greater than 100.0
   * @see Handle#setCollectionThreshold(float)
   * @see MemoryPoolMXBean#setCollectionUsageThreshold(long)
   */
  public static void setCollectionThreshold(float percentage) throws IllegalArgumentException {
    setThreshold(percentage, ThresholdType.COLLECTION);
  }

  /**
   * Sets the polling interval used for usage logging.  At each interval, a message is logged
   * containing the sum of the peak usage values for each heap memory pool and the current usage
   * of each off-heap buffer pool.  When logged, the peak usage is reset.
   * @param interval the interval between logging messages; if {@code null}, polling is disabled
   * @see MemoryPoolMXBean#resetPeakUsage()
   */
  public static void setPolling(Duration interval) {
    synchronized (LOCK) {
      if (pollingFuture != null) {
        if (!pollingInterval.equals(interval)) {
          // Changing interval or terminating usage reporting
          pollingFuture.cancel(false);
          pollingFuture = null;
          pollingInterval = null;
          if (interval == null) {
            LOGGER.info("Memory usage reporting disabled");
          }
        } else {
          LOGGER.info("Memory usage reporting already enabled with interval set to {}", interval);
          return;
        }
      }

      if (interval != null) {
        long nanos = interval.toNanos();
        pollingFuture = executor().scheduleAtFixedRate(MemoryMonitor::recordPeakUsage, 0L, nanos, TimeUnit.NANOSECONDS);
        pollingInterval = interval;
        LOGGER.info("Memory usage reporting enabled; interval set to {}", interval);
      } else {
        discardExecutor();
      }
    }
  }

  // package-private for testing
  static List<NotificationListener> getListeners() {
    synchronized (LOCK) {
      return unmodifiableList(new ArrayList<>(
          REGISTERED_LISTENERS.stream().map(l -> l.delegate).collect(toList())));
    }
  }

  // package-private for testing
  static Map<Float, Integer> getUsageThresholdCount() {
    synchronized (LOCK) {
      return ACTIVE_USAGE_THRESHOLDS.entrySet().stream()
          .collect(toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }
  }

  // package-private for testing
  static Map<Float, Integer> getCollectionThresholdCount() {
    synchronized (LOCK) {
      return ACTIVE_COLLECTION_THRESHOLDS.entrySet().stream()
          .collect(toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }
  }

  // package-private for testing
  static Duration setDereferenceInterval(Duration interval) {
    synchronized (LOCK) {
      Duration current = dereferenceInterval;
      dereferenceInterval = Objects.requireNonNull(interval);
      return current;
    }
  }

  /**
   * Removes one or more {@link WrappedMemoryListener} instances from the {@link MemoryMXBean} listener registry.
   * @param matcher the {@code Predicate} used to match the {@code WrappedMemoryListener} instances to de-register
   * @param deRegistrar the function used to de-register the {@code WrappedMemoryListener}
   * @return {@code true} if one or more {@code WrappedMemoryListener} instances were de-registered;
   *        {@code false} otherwise
   */
  private static boolean deregisterListener(Predicate<WrappedMemoryListener> matcher, ListenerConsumer deRegistrar) {
    boolean found = false;
    synchronized (LOCK) {
      for (Iterator<WrappedMemoryListener> iterator = REGISTERED_LISTENERS.iterator(); iterator.hasNext(); ) {
        WrappedMemoryListener wrappedListener = iterator.next();
        if (matcher.test(wrappedListener)) {
          try {
            deRegistrar.accept(wrappedListener);
            found = true;
            iterator.remove();
            wrappedListener.resetThresholds();    // Clear any thresholds set via Handle
            LOGGER.debug("Deregistered {} as a heap usage/collection listener", wrappedListener.delegate);
          } catch (ListenerNotFoundException e) {
            LOGGER.warn("Failed to deregister {} as a heap usage/collection listener", wrappedListener, e);
          }
        }
      }

      if (REGISTERED_LISTENERS.isEmpty()) {
        if (dereferenceFuture != null) {
          dereferenceFuture.cancel(true);
          dereferenceFuture = null;
        }
        discardExecutor();
      }
    }
    return found;
  }

  private interface ListenerConsumer {
    void accept(WrappedMemoryListener wrappedMemoryListener) throws ListenerNotFoundException;
  }

  /**
   * Registers the supplied {@code WrappedMemoryListener} with the {@code MemoryMXBean} {@link #MEMORY_BEAN}.
   * @param listener the {@code WrappedMemoryListener} to register
   * @return an auto-closable {@code Handle} through which the listener may be deregistered
   */
  private static Handle registerListener(WrappedMemoryListener listener) {
    synchronized (LOCK) {
      listener.register((NotificationEmitter)MEMORY_BEAN);
      REGISTERED_LISTENERS.add(listener);
    }
    LOGGER.debug("Registered {} as a usage threshold listener", listener.delegate);
    return listener.getHandle();
  }

  /**
   * Threshold-specific field and method references.
   */
  private enum ThresholdType {
    USAGE(ACTIVE_USAGE_THRESHOLDS,
        poolInfo -> poolInfo.initialUsageThreshold,
        MemoryPoolInfo::getUsageThreshold,
        MemoryPoolInfo::setUsageThreshold),
    COLLECTION(ACTIVE_COLLECTION_THRESHOLDS,
        poolInfo -> poolInfo.initialCollectionUsageThreshold,
        MemoryPoolInfo::getCollectionUsageThreshold,
        MemoryPoolInfo::setCollectionUsageThreshold);

    private final NavigableMap<Float, Set<Threshold>> thresholdTrackingMap;
    private final Function<MemoryPoolInfo, OptionalLong> initialThresholdGetter;
    private final ToLongFunction<MemoryPoolInfo> thresholdGetter;
    private final ObjLongConsumer<MemoryPoolInfo> thresholdSetter;

    ThresholdType(NavigableMap<Float, Set<Threshold>> thresholdTrackingMap,
                  Function<MemoryPoolInfo, OptionalLong> initialThresholdGetter,
                  ToLongFunction<MemoryPoolInfo> thresholdGetter,
                  ObjLongConsumer<MemoryPoolInfo> thresholdSetter) {
      this.thresholdTrackingMap = thresholdTrackingMap;
      this.initialThresholdGetter = initialThresholdGetter;
      this.thresholdGetter = thresholdGetter;
      this.thresholdSetter = thresholdSetter;
    }

    public NavigableMap<Float, Set<Threshold>> thresholdTrackingMap() {
      return thresholdTrackingMap;
    }

    public Function<MemoryPoolInfo, OptionalLong> initialThresholdGetter() {
      return initialThresholdGetter;
    }

    public ToLongFunction<MemoryPoolInfo> thresholdGetter() {
      return thresholdGetter;
    }

    public ObjLongConsumer<MemoryPoolInfo> thresholdSetter() {
      return thresholdSetter;
    }
  }

  /**
   * Sets the usage or collection threshold for monitored heap memory pools.
   *
   * @param percentage    the allocation or collection threshold, as a percentage of the maximum capacity,
   *                      over which a notification is generated; less than zero, the
   *                      threshold is reset to the value observed when this class was loaded;
   *                      if equal to zero, usage threshold checking is disabled
   * @param thresholdType the {@code ThresholdType} enum constant indicating a usage or collection threshold
   * @return a {@link ThresholdAction} value indicating the threshold set action taken base on {@code percentage}
   * @throws IllegalArgumentException if {@code percentage} is greater than 100.0
   */
  private static ThresholdAction setThreshold(float percentage, ThresholdType thresholdType)
      throws IllegalArgumentException {
    if (percentage > 100.0) {
      throw new IllegalArgumentException("percentage must be no more than 100.0");
    }

    synchronized (LOCK) {
      Float lowestThreshold = ofNullable(thresholdType.thresholdTrackingMap().firstEntry()).map(Map.Entry::getKey).orElse(null);
      LOGGER.trace("Setting {} threshold to {}%; current lowest threshold {}%",
          thresholdType.name(), percentage, lowestThreshold);

      if (percentage < 0.0) {
        // Reset threshold to previous value
        if (lowestThreshold == null) {
          // No managed threshold, reset to initial value
          POOL_INFO.forEach(poolInfo ->
              thresholdType.initialThresholdGetter().apply(poolInfo).ifPresent(threshold -> {
                if (threshold != thresholdType.thresholdGetter().applyAsLong(poolInfo)) {
                  thresholdType.thresholdSetter().accept(poolInfo, threshold);
                }
              }));
        } else {
          // Use the lowest managed threshold
          POOL_INFO.forEach(poolInfo ->
              thresholdType.thresholdSetter().accept(poolInfo, calculateThreshold(lowestThreshold, poolInfo.getUsage())));
        }
        return ThresholdAction.RESET;

      } else if (percentage == 0.0) {
        // Turn off threshold checking
        if (lowestThreshold == null) {
          // No managed threshold, set to "off" threshold
          POOL_INFO.forEach(poolInfo -> thresholdType.thresholdSetter().accept(poolInfo, 0L));
        } else {
          // Use the lowest managed threshold
          POOL_INFO.forEach(poolInfo ->
              thresholdType.thresholdSetter().accept(poolInfo, calculateThreshold(lowestThreshold, poolInfo.getUsage())));
        }
        return ThresholdAction.OFF;

      } else {
        // Positive threshold; set a new threshold
        if (lowestThreshold == null || percentage < lowestThreshold) {
          POOL_INFO.forEach(poolInfo -> thresholdType.thresholdSetter().accept(poolInfo,
              calculateThreshold(percentage, poolInfo.getUsage())));
        }
        return ThresholdAction.SET;
      }
    }
  }

  /**
   * Calculates a percentage-based threshold value for the specified {@code MemoryUsage}.
   * @param percentage the threshold as a percentage
   * @param memoryUsage the {@code MemoryUsage} over which the threshold is calculated
   * @return the calculated threshold
   */
  private static long calculateThreshold(float percentage, MemoryUsage memoryUsage) {
    long maxUsage = (memoryUsage == null ? -1L : memoryUsage.getMax());
    long maxMemory = (maxUsage == -1L ? Runtime.getRuntime().maxMemory() : maxUsage);
    return (long)Math.ceil(maxMemory * percentage / 100.0F);
  }

  /**
   * Emit logging records showing memory usage statistics for heap, off-heap, and GC.
   */
  private static void recordPeakUsage() {
    int validPools = 0;
    long totalInit = 0;
    long totalUsed = 0;
    long totalCommitted = 0;
    long totalMax = 0;
    int monitoredPools = 0;
    long monitoredUsed = 0;
    long monitoredMax = 0;
    long monitoredUsageThresholdExceededCount = 0;
    long monitoredCollectionThresholdExceededCount = 0;

    // Accumulate the heap statistics
    for (MemoryPoolMXBean poolBean : HEAP_BEANS) {
      MemoryUsage peakUsage = poolBean.getPeakUsage();

      if (peakUsage != null) {
        validPools++;
        totalInit += peakUsage.getInit();
        totalUsed += peakUsage.getUsed();
        totalCommitted += peakUsage.getCommitted();
        totalMax += peakUsage.getMax();
        if (poolBean.isUsageThresholdSupported()) {
          monitoredPools++;
          monitoredUsed += peakUsage.getUsed();
          monitoredMax += peakUsage.getMax();
          monitoredUsageThresholdExceededCount += poolBean.getUsageThresholdCount();
        }
        if (poolBean.isCollectionUsageThresholdSupported()) {
          monitoredCollectionThresholdExceededCount += poolBean.getCollectionUsageThresholdCount();
        }

        poolBean.resetPeakUsage();
      }
    }

    // Emit message summarizing heap usage
    LOGGER.info("[HEAP(pools={})] init={}, used={}, committed={}, max={}, used/max={}%;" +
            " monitored(pools={}): used={}, max={} used/max={}%; usageExceeded={}; collectionExceeded={}",
        validPools,
        formatSize(totalInit), formatSize(totalUsed), formatSize(totalCommitted), formatSize(totalMax),
        formatPercentage(totalUsed, totalMax),
        monitoredPools, formatSize(monitoredUsed), formatSize(monitoredMax),
        formatPercentage(monitoredUsed, monitoredMax),
        monitoredUsageThresholdExceededCount, monitoredCollectionThresholdExceededCount);

    // Emit messages recounting GC activity summary
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (bean.getCollectionCount() != 0) {
        LOGGER.info("[{}] collection: count={}, time={}; memoryPools={}",
            bean.getName(), bean.getCollectionCount(), Duration.ofMillis(bean.getCollectionTime()),
            Arrays.toString(bean.getMemoryPoolNames()));
      }
    }

    // Emit messages about off-heap use
    for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if (pool.getCount() != 0) {
        if (pool.getName().equalsIgnoreCase("direct")) {
          LOGGER.info("[{}] count={}, used={}, capacity={}, max={}, used/max={}%, capacity/max={}%",
              pool.getName(), pool.getCount(), formatSize(pool.getMemoryUsed()), formatSize(pool.getTotalCapacity()),
              (MAX_DIRECT_MEMORY_SIZE == null ? "?" : formatSize(MAX_DIRECT_MEMORY_SIZE)),
              (MAX_DIRECT_MEMORY_SIZE == null ? "?" : formatPercentage(pool.getMemoryUsed(), MAX_DIRECT_MEMORY_SIZE)),
              (MAX_DIRECT_MEMORY_SIZE == null ? "?" : formatPercentage(pool.getTotalCapacity(), MAX_DIRECT_MEMORY_SIZE)));
        } else {
          LOGGER.info("[{}] count={}, used={}, capacity={}",
              pool.getName(), pool.getCount(), formatSize(pool.getMemoryUsed()), formatSize(pool.getTotalCapacity()));
        }
      }
    }
  }

  private static String formatPercentage(long numerator, long denominator) {
    return String.format("%.1f", ((numerator * 100.0F) / denominator));
  }

  /**
   * Gets the {@code ScheduledExecutorService} to use for polling memory usage.
   * @return a new or the current {@code ScheduledExecutorService}
   */
  private static ScheduledExecutorService executor() {
    ScheduledExecutorService executor = pollingExecutor;
    if (executor == null) {
      synchronized (LOCK) {
        executor = pollingExecutor;
        if (executor == null) {
          int group = SCHEDULER_GROUP.incrementAndGet();
          executor = pollingExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger threadId = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
              Thread thread = new Thread(r, String.format("MemoryMonitor-%d-%04d", group, threadId.incrementAndGet()));
              thread.setDaemon(true);
              return thread;
            }
          });
        }
      }
    }
    return executor;
  }
  private static final AtomicInteger SCHEDULER_GROUP = new AtomicInteger(0);

  /**
   * Shut down and discard the polling executor if no longer needed.
   */
  private static void discardExecutor() {
    synchronized (LOCK) {
      if (pollingExecutor != null && pollingInterval == null && REGISTERED_LISTENERS.isEmpty()) {
        pollingExecutor.shutdownNow();
        boolean finished;
        do {
          try {
            finished = pollingExecutor.awaitTermination(100L, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            finished = true;
            Thread.currentThread().interrupt();
          }
        } while (!finished);
        pollingExecutor = null;
      }
    }
  }

  /**
   * Enables {@link Handle} dereference tracking for use in auto-closure of dereferenced
   * {@link WrappedMemoryListener} instances.
   */
  private static void enableDereferenceTracking() {
    synchronized (LOCK) {
      if (dereferenceFuture == null) {
        long interval = dereferenceInterval.toNanos();
        dereferenceFuture = executor().scheduleAtFixedRate(MemoryMonitor::removeDereferencedHandles, interval, interval, TimeUnit.NANOSECONDS);
      }
    }
  }

  /**
   * Scans the {@link #REGISTERED_LISTENERS} collection and closes any for which the {@link Handle} is
   * no longer strongly referenced.
   */
  private static void removeDereferencedHandles() {
    synchronized (LOCK) {
      new ArrayList<>(REGISTERED_LISTENERS).stream()
          .filter(listener -> listener.getHandle() == null)
          .forEach(WrappedMemoryListener::close);
    }
  }

  /**
   * Gets the maximum amount of direct memory available for allocation.  The value is obtained from
   * the JVM if possible; if not, the value configured using {@code -XX:MaxDirectMemorySize} is used;
   * if absent, the JVM-specific default, if known, if used.
   * @return the configured/defaulted maximum allocatable direct off-heap memory available; {@code null}
   *      if the value cannot be determined
   */
  private static Long getMaxDirectMemory() {
    MemoryInfo memoryInfo = MemoryInfo.getInstance();

    String maxDirectMemorySource;
    String maxDirectMemoryFault = null;

    MemoryInfo.MaxDirectMemoryInfo maxDirectMemoryInfo = memoryInfo.effectiveMaxDirectMemoryInfo();
    long maxDirectMemory = ofNullable(maxDirectMemoryInfo.maxDirectMemory()).orElse(-1L);
    if (maxDirectMemory == -1) {
      maxDirectMemoryFault = maxDirectMemoryInfo.maxDirectMemoryAccessFault();
      maxDirectMemory = memoryInfo.configuredMaxDirectMemory();
      if (maxDirectMemory == -1) {
        // MaxDirectMemorySize not specified; max heap is the default from Java 8 onward
        maxDirectMemory = Runtime.getRuntime().maxMemory();
        maxDirectMemorySource = "Runtime.maxMemory()";
      } else {
        maxDirectMemorySource = memoryInfo.configuredMaxDirectMemoryInfo().valueSource();
      }
    } else {
      maxDirectMemorySource = maxDirectMemoryInfo.valueSource();
    }

    if (maxDirectMemory == -1) {
      LOGGER.debug("Unable to determine MaxDirectMemorySize: {}", maxDirectMemoryFault);
      return null;
    } else {
      LOGGER.debug("MaxDirectMemorySize {} obtained from {}", formatSize(maxDirectMemory), maxDirectMemorySource);
      return maxDirectMemory;
    }
  }

  private enum ThresholdAction {
    /* Indicates the threshold was set to its initial value. */
    RESET,
    /* Indicates the threshold was set to ZERO. */
    OFF,
    /* Indicates a new threshold value was set. */
    SET
  }

  /**
   * Wraps a {@link NotificationListener} for a heap memory pool to permit logging receipt of notifications.
   */
  private static final class WrappedMemoryListener implements NotificationListener {
    private final NotificationListener delegate;
    private final NotificationFilter delegateFilter;
    private final Object delegateHandback;
    private final AtomicReference<NotificationEmitter> registeredEmitter = new AtomicReference<>();

    /**
     * Strong reference to the {@code Handle} instance for this listener.  Non-null until either
     * {@link #setUsageThreshold} or {@link #setCollectionThreshold} is used.
     */
    private final AtomicReference<Handle> pinnedHandle = new AtomicReference<>();
    /**
     * Weak reference to the {@code Handle} instance to permit closure on dereference.
     */
    private final AtomicReference<WeakReference<Handle>> exposedHandle = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Threshold usageThreshold;
    private volatile Threshold collectionThreshold;

    WrappedMemoryListener(NotificationListener delegate) {
      this(delegate, null, null);
    }

    WrappedMemoryListener(NotificationListener delegate, NotificationFilter delegateFilter, Object delegateHandback) {
      this.delegate = delegate;
      this.delegateFilter = delegateFilter;
      this.delegateHandback = delegateHandback;
      HandleImpl handle = new HandleImpl(this);
      this.pinnedHandle.set(handle);
      this.exposedHandle.set(new WeakReference<>(handle));
    }

    private Handle getHandle() {
      return exposedHandle.get().get();
    }

    public void setUsageThreshold(float percentage) throws IllegalArgumentException {
      synchronized (LOCK) {
        removeCurrent(ACTIVE_USAGE_THRESHOLDS);
        if (ThresholdAction.SET == setThreshold(percentage, ThresholdType.USAGE)) {
          this.usageThreshold = trackThreshold(ACTIVE_USAGE_THRESHOLDS, percentage);
        }
        pinnedHandle.set(null);
        enableDereferenceTracking();
      }
    }

    public void setCollectionThreshold(float percentage) throws IllegalArgumentException {
      synchronized (LOCK) {
        removeCurrent(ACTIVE_COLLECTION_THRESHOLDS);
        if (ThresholdAction.SET == setThreshold(percentage, ThresholdType.COLLECTION)) {
          this.collectionThreshold = trackThreshold(ACTIVE_COLLECTION_THRESHOLDS, percentage);
        }
        pinnedHandle.set(null);
        enableDereferenceTracking();
      }
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
      /*
       * Log threshold notifications before passing it along to the real listener.
       */
      String type = notification.getType();
      if (type.equals(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)
          || type.equals(MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED)) {
        MemoryNotificationInfo memoryNotification =
            MemoryNotificationInfo.from((CompositeData)notification.getUserData());
        MemoryUsage poolUsage = memoryNotification.getUsage();

        // Logging a threshold notification -- a WARN-level event
        LOGGER.warn("MemoryNotificationInfo {[{}], {}Count={}, init={}, used={}, committed={}, max={}, used/max={}%}",
            memoryNotification.getPoolName(),
            (type.equalsIgnoreCase(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED) ? "usage" : "collection"),
            memoryNotification.getCount(),
            formatSize(poolUsage.getInit()), formatSize(poolUsage.getUsed()), formatSize(poolUsage.getCommitted()),
            formatSize(poolUsage.getMax()), formatPercentage(poolUsage.getUsed(), poolUsage.getMax()));

        /*
         * Present the notification to the listener iff the type-specific, active Threshold
         * is met or exceeded for this listener OR there is no active Threshold of either type.
         */
        if (usageThreshold == null && collectionThreshold == null) {
          LOGGER.trace("No Handle-based thresholds active; presenting notification to listener");
          delegate.handleNotification(notification, handback);
        } else {
          Threshold threshold;
          if (type.equals(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)) {
            threshold = usageThreshold;
            LOGGER.trace("Usage active usage threshold {}", threshold);
          } else {
            threshold = collectionThreshold;
            LOGGER.trace("Usage active collection threshold {}", threshold);
          }
          if (threshold != null && poolUsage.getUsed() >= calculateThreshold(threshold.getPercentage(), poolUsage)) {
            delegate.handleNotification(notification, handback);
          } else {
            LOGGER.trace("Notification threshold not met");
          }
        }

      } else {
        delegate.handleNotification(notification, handback);
      }
    }

    public boolean isClosed() {
      return closed.get();
    }

    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          deregisterListener(delegate);
        } catch (ListenerNotFoundException ignored) {
        }
        resetThresholds();
      }
    }

    void register(NotificationEmitter emitter) {
      if (registeredEmitter.compareAndSet(null, emitter)) {
        emitter.addNotificationListener(this, delegateFilter, delegateHandback);
      } else {
        throw new IllegalStateException("Listener already registered with a NotificationEmitter");
      }
    }

    void deregisterAll() throws ListenerNotFoundException {
      NotificationEmitter emitter = registeredEmitter.get();
      if (emitter != null && registeredEmitter.compareAndSet(emitter, null)) {
        emitter.removeNotificationListener(this);
      } else {
        throw new IllegalStateException("Listener not registered with a NotificationEmitter");
      }
    }

    void deregister() throws ListenerNotFoundException {
      NotificationEmitter emitter = registeredEmitter.get();
      if (emitter != null && registeredEmitter.compareAndSet(emitter, null)) {
        emitter.removeNotificationListener(this, delegateFilter, delegateHandback);
      } else {
        throw new IllegalStateException("Listener not registered with a NotificationEmitter");
      }
    }

    boolean matches(NotificationListener listener) {
      return this.delegate == listener;
    }

    boolean matches(NotificationListener listener, NotificationFilter filter, Object handback) {
      return this.delegate == listener && this.delegateFilter == filter && this.delegateHandback == handback;
    }

    /**
     * Removes {@code this} {@code Handle} and any dead/GC's entries from the supplied {@code Threshold} map.
     * <p>
     * Must synchronize against {@link #LOCK} when calling this method.
     * @param thresholdsMap the map tracking {@code Threshold} instances
     */
    private void removeCurrent(Map<Float, Set<Threshold>> thresholdsMap) {
      Iterator<Set<Threshold>> entryIterator = thresholdsMap.values().iterator();
      while (entryIterator.hasNext()) {
        Set<Threshold> thresholds = entryIterator.next();
        thresholds.removeIf(t -> !t.isLive() || t.listener() == this);
        if (thresholds.isEmpty()) {
          entryIterator.remove();
        }
      }
    }

    /**
     * Adds {@code this} {@code Handle} to the supplied {@code Threshold} map at the specified usage percentage.
     * @param thresholdsMap the map tracking the {@code Threshold} instances
     * @param percentage    the usage threshold
     * @return the {@code Threshold} instance added to {@code thresholdsMap}
     */
    private Threshold trackThreshold(Map<Float, Set<Threshold>> thresholdsMap, float percentage) {
      Threshold threshold = new Threshold(percentage, this);
      thresholdsMap.compute(percentage, (p, thresholdSet) -> {
        if (thresholdSet == null) {
          thresholdSet = new HashSet<>();
        } else {
          thresholdSet.removeIf(t -> t.listener() == this);
        }
        thresholdSet.add(threshold);
        return thresholdSet;
      });
      return threshold;
    }

    private void resetThresholds() {
      synchronized (LOCK) {
        if (usageThreshold != null) {
          setUsageThreshold(0.0F);
          usageThreshold = null;
        }
        if (collectionThreshold != null) {
          setCollectionThreshold(0.0F);
          collectionThreshold = null;
        }
      }
    }

    @Override
    public String toString() {
      return "WrappedMemoryListener{" +
          "delegate=" + delegate +
          ", delegateFilter=" + delegateFilter +
          ", delegateHandback=" + delegateHandback +
          '}';
    }
  }

  /**
   * Represents an active threshold request through a {@link Handle}.
   */
  private static final class Threshold {
    private final float percentage;
    private final WrappedMemoryListener listener;

    private Threshold(float percentage, WrappedMemoryListener listener) {
      this.percentage = percentage;
      this.listener = listener;
    }

    public float getPercentage() {
      return percentage;
    }

    public WrappedMemoryListener listener() {
      return listener;
    }

    public boolean isLive() {
      Handle handle = listener.getHandle();
      return handle != null && !handle.isClosed();
    }

    @Override
    public String toString() {
      return "Threshold{" +
          "percentage=" + getPercentage() +
          "%, listener=" + listener +
          '}';
    }
  }

  private static final class MemoryPoolInfo {
    private final MemoryPoolMXBean poolBean;
    private final OptionalLong initialUsageThreshold;
    private final OptionalLong initialCollectionUsageThreshold;

    private MemoryPoolInfo(MemoryPoolMXBean poolBean) {
      this.poolBean = poolBean;
      if (poolBean.isUsageThresholdSupported()) {
        this.initialUsageThreshold = OptionalLong.of(poolBean.getUsageThreshold());
      } else {
        this.initialUsageThreshold = OptionalLong.empty();
      }
      if (poolBean.isCollectionUsageThresholdSupported()) {
        this.initialCollectionUsageThreshold = OptionalLong.of(poolBean.getCollectionUsageThreshold());
      } else {
        this.initialCollectionUsageThreshold = OptionalLong.empty();
      }
    }

     private MemoryUsage getUsage() {
      return poolBean.getUsage();
    }

     private long getUsageThreshold() {
      return poolBean.getUsageThreshold();
    }

    /**
     * Sets the usage (allocation) threshold for this {@code MemoryPoolMXBean}, if supported.
     * @param usageThreshold the usage (allocation) threshold for {@code poolBean}
     */
    private void setUsageThreshold(long usageThreshold) {
      if (initialUsageThreshold.isPresent()) {
        long currentUsageThreshold = poolBean.getUsageThreshold();
        poolBean.setUsageThreshold(usageThreshold);
        LOGGER.debug("Changed [{}] allocation usage threshold from {} to {}",
            poolBean.getName(),
            formatSize(currentUsageThreshold), formatSize(usageThreshold));
      }
    }

     private long getCollectionUsageThreshold() {
      return poolBean.getCollectionUsageThreshold();
    }

    /**
     * Sets the collection threshold for this {@code MemoryPoolMXBean}, if supported.
     * @param collectionUsageThreshold the collection threshold for {@code poolBean}
     */
    private void setCollectionUsageThreshold(long collectionUsageThreshold) {
      if (initialCollectionUsageThreshold.isPresent()) {
        long currentCollectionUsageThreshold = poolBean.getCollectionUsageThreshold();
        poolBean.setCollectionUsageThreshold(collectionUsageThreshold);
        LOGGER.debug("Changed [{}] collection threshold from {} to {}",
            poolBean.getName(),
            formatSize(currentCollectionUsageThreshold), formatSize(collectionUsageThreshold));
      }
    }
  }

  /**
   * Represents a {@code NotificationListener} registered with the
   * {@link #registerListener(NotificationListener)} or
   * {@link #registerListener(NotificationListener, NotificationFilter, Object)} methods.
   * Closing an instance of this class de-registers the {@code NotificationListener}.
   */
  public interface Handle extends AutoCloseable {
    /**
     * Sets a <i>managed</i>, percentage-based memory usage threshold.  Notifications
     * for the {@code NotificationListener} represented by this {@code Handle} are
     * received only when the specified threshold percentage is met or exceeded.
     * <p>
     * If no threshold is set using either this method or the {@link #setCollectionThreshold}
     * method, the thresholds set using the {@link MemoryMonitor#setUsageThreshold} and
     * {@link MemoryMonitor#setCollectionThreshold} methods are used.
     * @param percentage the usage (allocation) threshold, as a percentage of the maximum capacity,
     *                   over which a notification is generated; less than zero, the
     *                   threshold is reset to the value observed when this class was loaded;
     *                   if equal to zero, usage threshold checking is disabled
     * @throws IllegalArgumentException if {@code percentage} is greater than 100.0
     * @see MemoryPoolMXBean#setUsageThreshold(long)
     */
    void setUsageThreshold(float percentage) throws IllegalArgumentException;

    /**
     * Sets a <i>managed</i>, percentage-based memory collection threshold.  Notifications
     * for the {@code NotificationListener} represented by this {@code Handle} are
     * received only when the specified threshold percentage is met or exceeded.
     * <p>
     * If no threshold is set using either this method or the {@link #setUsageThreshold}
     * method, the thresholds set using the {@link MemoryMonitor#setUsageThreshold} and
     * {@link MemoryMonitor#setCollectionThreshold} methods are used.
     * @param percentage the collection usage threshold, as a percentage of the maximum capacity,
     *                   over which a notification is generated; less than zero, the
     *                   threshold is reset to the value observed when this class was loaded;
     *                   if equal to zero, usage threshold checking is disabled
     * @throws IllegalArgumentException if {@code percentage} is greater than 100.0
     * @see MemoryPoolMXBean#setCollectionUsageThreshold(long)
     */
    void setCollectionThreshold(float percentage) throws IllegalArgumentException;

    /**
     * Indicates if this {@code Handle} is closed.
     * @return {@code true} if this {@code Handle} is closed
     */
    boolean isClosed();

    /**
     * Closes and de-registers this {@code Handle}.
     */
    @Override
    void close();
  }

  /**
   * Implementation of {@link Handle} returned for listener registration.
   */
  private static final class HandleImpl implements Handle {
    private final WrappedMemoryListener wrappedListener;

    private HandleImpl(WrappedMemoryListener wrappedListener) {
      this.wrappedListener = wrappedListener;
    }

    @Override
    public void setUsageThreshold(float percentage) throws IllegalArgumentException {
      wrappedListener.setUsageThreshold(percentage);
    }

    @Override
    public void setCollectionThreshold(float percentage) throws IllegalArgumentException {
      wrappedListener.setCollectionThreshold(percentage);
    }

    @Override
    public boolean isClosed() {
      return wrappedListener.isClosed();
    }

    @Override
    public void close() {
      wrappedListener.close();
    }
  }
}
