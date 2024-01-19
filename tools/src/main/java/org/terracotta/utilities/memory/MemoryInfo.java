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
package org.terracotta.utilities.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Singleton class providing access to JVM memory information.
 * <p>
 * This class uses terminology described in the following:
 * <ul>
 *   <li><a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/MemoryMXBean.html">java.lang.management.MemoryMXBean</a></li>
 *   <li><a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/MemoryType.html">java.lang.management.MemoryType</a></li>
 *   <li><a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/BufferPoolMXBean.html">java.lang.management.BufferPoolMXBean</a></li>
 * </ul>
 *
 * <dl>
 *   <dt>heap memory</dt><dd>Java managed memory holding object instances and arrays</dd>
 *   <dt>non-heap memory</dt><dd>Java managed memory holding other than object instances and arrays</dd>
 *   <dt>direct memory</dt><dd><i>unmanaged</i>, off-heap memory allocated using
 *   <a href="https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#allocateDirect-int-">java.nio.ByteBuffer.allocateDirect</a></dd>
 *   <dt>mapped memory</dt><dd><i>unmanaged</i>, off-heap memory mapped to a region of a file created using
 *   <a href="https://docs.oracle.com/javase/8/docs/api/java/nio/channels/FileChannel.html#map-java.nio.channels.FileChannel.MapMode-long-long-">java.nio.channels.FileChannel.map</a></dd>
 * </dl>
 *
 * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/MemoryPoolMXBean.html">java.lang.management.MemoryPoolMXBean</a>
 * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/BufferPoolMXBean.html">java.lang.management.BufferPoolMXBean</a>
 */
public final class MemoryInfo {

  /**
   * Property-based switch to <i>disable</i> the pre-fetch of {@code MemoryPoolMXBean} instances.
   * This value defaults to {@code false}. Specify {@code true} to cause each call needing
   * {@code MemoryPoolMXBean} instances to fetch the instances.
   * <p>
   * Javadoc for java.lang.management.ManagementFactory.getMemoryPoolMXBeans indicates pools can be added
   * and removed during operation by the JVM.  Comments in sun.management.MemoryImpl.getMemoryPools indicates
   * that dynamic additions and removals are not expected/supported.
   */
  private static final boolean PRE_FETCH_DISABLED =
      Boolean.getBoolean("org.terracotta.utilities.disablePreFetchMemoryPoolBeans");

  /**
   * The name of the <i>mapped</i> memory buffer pool.
   */
  private static final String BUFFER_POOL_MAPPED = "mapped";

  /**
   * The name of the <i>direct</i> memory buffer pool.
   */
  private static final String BUFFER_POOL_DIRECT = "direct";

  /**
   * The information for <i>effective</i> max direct memory value.
   */
  private final MaxDirectMemoryInfo effectiveMaxDirectMemory;
  /**
   * The information for the <i>declared</i> max direct memory value.
   */
  private final MaxDirectMemoryInfo configuredMaxDirectMemory;

  private final List<BufferPoolMXBean> bufferPoolBeans;

  private final Supplier<List<MemoryPoolMXBean>> memoryPoolMXBeanSupplier;

  private static final MemoryInfo INSTANCE = new MemoryInfo();

  /**
   * Gets the singleton {@code MemoryInfo} instance.
   *
   * @return the {@code MemoryInfo} instance
   */
  public static MemoryInfo getInstance() {
    return INSTANCE;
  }

  private MemoryInfo() {
    this.effectiveMaxDirectMemory = getMaxDirectMemory();
    this.configuredMaxDirectMemory = getMaxDirectMemoryArg();
    this.bufferPoolBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    if (PRE_FETCH_DISABLED) {
      this.memoryPoolMXBeanSupplier = () -> Collections.unmodifiableList(ManagementFactory.getMemoryPoolMXBeans());
    } else {
      List<MemoryPoolMXBean> memoryPoolMXBeans = Collections.unmodifiableList(ManagementFactory.getMemoryPoolMXBeans());
      this.memoryPoolMXBeanSupplier = () -> memoryPoolMXBeans;
    }
  }

  /**
   * Gets the maximum amount of direct, off-heap memory that can be used in this JVM.
   * <p>
   * If the returned value is {@code -1}, the {@link #effectiveMaxDirectMemoryInfo()} method may be used
   * to obtain additional detail.
   *
   * @return the amount of off-heap memory available in this JVM; this is either the value specified using
   * the {@code MaxDirectMemorySize} JVM option or the default calculated during JVM initialization;
   * a value of {@code -1} is returned if the max direct memory is unavailable
   * @see #effectiveMaxDirectMemoryInfo()
   */
  public long effectiveMaxDirectMemory() {
    return maxDirectMemory(effectiveMaxDirectMemory);
  }

  /**
   * Gets the {@link MaxDirectMemoryInfo} instance describing maximum amount of direct, off-heap memory
   * that can be used in this JVM.
   * @return the {@code MaxDirectMemoryInfo} instance for direct, off-heap memory
   */
  public MaxDirectMemoryInfo effectiveMaxDirectMemoryInfo() {
    return effectiveMaxDirectMemory;
  }

  /**
   * Gets the amount of direct, off-heap memory specified using the {@code MaxDirectMemorySize} JVM option.
   * <p>
   * If the returned value is {@code -1}, the {@link #configuredMaxDirectMemoryInfo()} method may be used
   * to obtain additional detail.
   *
   * @return the amount of off-heap memory specified with the {@code MaxDirectMemorySIze} option;
   * a value of {@code -1} is returned if the {@code MaxDirectMemorySize} option was not specified
   * or if the specified value could was not understood
   * @see #configuredMaxDirectMemoryInfo()
   */
  public long configuredMaxDirectMemory() {
    return maxDirectMemory(configuredMaxDirectMemory);
  }

  /**
   * Gets the {@link MaxDirectMemoryInfo} instance describing the max direct memory value specified by
   * JVM option.
   * @return the {@code MaxDirectMemoryInfo} instance describing the max direct, off-heap memory as
   *    configured by JVM option
   */
  public MaxDirectMemoryInfo configuredMaxDirectMemoryInfo() {
    return configuredMaxDirectMemory;
  }

  /**
   * Gets the list of {@code MemoryPoolMXBean} instances.
   * @return an immutable list of {@code MemoryPoolMXBean} instances
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/management/ManagementFactory.html#getMemoryPoolMXBeans--">java.lang.management.ManagementFactory.getMemoryPoolMXBeans</a>
   */
  public List<MemoryPoolMXBean> memoryPoolMXBeans() {
    /*
     * Javadoc for java.lang.management.ManagementFactory.getMemoryPoolMXBeans indicates
     * pools can be added and removed during operation by the JVM.  Comments in
     * sun.management.MemoryImpl.getMemoryPools indicates that dynamic additions and
     * removals are not expected/supported.
     */
    return memoryPoolMXBeanSupplier.get();
  }

  /**
   * Gets the total heap memory currently in use across all heap memory pools.
   * @return the number of bytes of in-use heap memory
   */
  public long heapInUse() {
    return memoryPoolMXBeans().stream()
        .filter(b -> b.getType() == MemoryType.HEAP)
        .filter(MemoryPoolMXBean::isValid)
        .map(MemoryPoolMXBean::getUsage)
        .mapToLong(MemoryUsage::getUsed)
        .sum();
  }

  /**
   * Gets the amount of off-heap memory in use.
   * This sum includes all off-heap memory types including {@code direct}
   * and {@code mapped} and possibly other types depending on the JVM.
   *
   * @return the sum of all off-heap memory in use
   */
  public long offHeapMemoryInUse() {
    return bufferPoolMemory(null);
  }

  /**
   * Gets the number of all off-heap memory buffers in use.
   * This count includes all off-heap memory types including {@code direct}
   * and {@code mapped} and possibly other types depending on the JVM.
   * @return the number of off-heap buffers in use
   */
  public long offHeapBufferCount() {
    return bufferPoolCount(null);
  }

  /**
   * Gets the amount of off-heap memory in use for direct {@code ByteBuffer}s.
   *
   * @return the amount of direct off-heap memory in use
   */
  public long directMemoryInUse() {
    return bufferPoolMemory(BUFFER_POOL_DIRECT);
  }

  /**
   * Gets the number of directly allocated off-heap buffers.
   * @return the number of direct, off-heap buffers
   */
  public long directBufferCount() {
    return bufferPoolCount(BUFFER_POOL_DIRECT);
  }

  /**
   * Gets the amount of off-heap memory in use for mapped {@code ByteBuffer}s.
   *
   * @return the amount of mapped off-heap memory in use
   */
  public long mappedMemoryInUse() {
    return bufferPoolMemory(BUFFER_POOL_MAPPED);
  }

  /**
   * Gets the number of off-heap buffers used for mapped file buffers.
   * @return the number of mapped buffers in use
   */
  public long mappedBufferCount() {
    return bufferPoolCount(BUFFER_POOL_MAPPED);
  }

  /**
   * Formats a {@code long} memory byte-size value into a human-readable memory size.
   *
   * @param size the memory size, in bytes, to format
   * @return a memory size using base 2 size units
   * @see <a href="https://stackoverflow.com/a/24805871/1814086">
   * How can I convert byte size into a human-readable format in Java?</a>
   */
  public static String formatSize(long size) {
    if (size < 0) {
      return Long.toString(size);
    } else if (size < 1024) {
      return size + " B";
    } else {
      int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
      return String.format("%.1f %siB", (double)size / (1L << (z * 10)), " KMGTPE".charAt(z));
    }
  }

  private long bufferPoolMemory(String pool) {
    return bufferPoolBeans(pool).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
  }

  private long bufferPoolCount(String pool) {
    return bufferPoolBeans(pool).mapToLong(BufferPoolMXBean::getCount).sum();
  }

  private Stream<BufferPoolMXBean> bufferPoolBeans(String pool) {
    Stream<BufferPoolMXBean> bufferPoolMXBeanStream = bufferPoolBeans.stream();
    if (pool != null) {
      bufferPoolMXBeanStream = bufferPoolMXBeanStream.filter(
          bufferPoolMXBean -> bufferPoolMXBean.getName().equals(pool));
    }
    return bufferPoolMXBeanStream;
  }

  private static long maxDirectMemory(MaxDirectMemoryInfo memory) {
    if (memory.maxDirectMemoryAccessFault == null) {
      return (memory.maxDirectMemory == null ? -1 : memory.maxDirectMemory);
    } else {
      return -1L;
    }
  }

  /**
   * Obtains the max direct memory limit from the JVM.
   *
   * @return a new {@code MaxDirectMemoryInfo} instance describing the JVM's max direct memory value
   */
  private static MaxDirectMemoryInfo getMaxDirectMemory() {
    Throwable maxDirectMemoryFault = null;
    Long maxDirectMemory = null;
    Class<?> vmClass = null;
    try {
      vmClass = Class.forName("jdk.internal.misc.VM");
    } catch (ClassNotFoundException e) {
      try {
        vmClass = Class.forName("sun.misc.VM");
      } catch (ClassNotFoundException e1) {
        maxDirectMemoryFault = e;
      }
    }
    if (vmClass != null) {
      try {
        maxDirectMemory = (Long)vmClass.getDeclaredMethod("maxDirectMemory").invoke(null);
      } catch (IllegalAccessException e) {
        if (vmClass.getName().startsWith("jdk") && e.getMessage().contains(" does not export ")) {
          System.err.format("Unable to access %s.maxDirectMemory(): " +
                  "Access to maxDirectMemory() requires the java option '--add-exports %s/%s=ALL-UNNAMED'%n",
              vmClass.getName(), getModuleName(vmClass), vmClass.getPackage().getName());
        }
        maxDirectMemoryFault = e;
      } catch (NoSuchMethodException | InvocationTargetException e) {
        maxDirectMemoryFault = e;
      }
    }
    String vmClassName = vmClass == null ? "<unavailable>" : vmClass.getName();
    return new MaxDirectMemoryInfo(maxDirectMemory, vmClassName, maxDirectMemoryFault);
  }


  /**
   * Gets the value of the {@code MaxDirectMemorySize} JVM option, if specified.
   *
   * @return a new {@code MaxDirectMemoryInfo} instance describing the {@code MaxDirectMemorySize} value
   */
  @SuppressWarnings("fallthrough")
  @SuppressFBWarnings({ "SF_SWITCH_NO_DEFAULT", "SF_SWITCH_FALLTHROUGH" })
  private static MaxDirectMemoryInfo getMaxDirectMemoryArg() {
    String argId = "-XX:MaxDirectMemorySize=";
    List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    try {
      String maxDirectMemoryArg = null;
      for (String arg : inputArguments) {
        if (arg.startsWith(argId)) {
          maxDirectMemoryArg = arg.substring(argId.length());
        }
      }
      if (maxDirectMemoryArg != null) {
        Matcher matcher = Pattern.compile("([0-9]+)([kKmMgGtT]?)").matcher(maxDirectMemoryArg);
        if (matcher.matches()) {
          long maxDirectMemory = Long.decode(matcher.group(1));
          if (!matcher.group(2).isEmpty()) {
            char scale = matcher.group(2).toLowerCase().charAt(0);
            switch (scale) {
              case 't':
                maxDirectMemory *= 1024;
              case 'g':
                maxDirectMemory *= 1024;
              case 'm':
                maxDirectMemory *= 1024;
              case 'k':
                maxDirectMemory *= 1024;
                break;
            }
          }
          return new MaxDirectMemoryInfo(maxDirectMemory, "<-XX:MaxDirectMemorySize>", null);
        } else {
          return new MaxDirectMemoryInfo(null, "<-XX:MaxDirectMemorySize>",
              new IllegalStateException("Unexpected MaxDirectMemorySize value: " + maxDirectMemoryArg));
        }
      } else {
        return new MaxDirectMemoryInfo(null, "<unavailable>", null);
      }
    } catch (IllegalStateException | IndexOutOfBoundsException | NumberFormatException e) {
      return new MaxDirectMemoryInfo(null, "<-XX:MaxDirectMemorySize>", e);
    }
  }

  private static String getModuleName(Class<?> clazz) {
    try {
      @SuppressWarnings("JavaReflectionMemberAccess")
      Object module = Class.class.getDeclaredMethod("getModule").invoke(clazz);
      return (String)module.getClass().getDeclaredMethod("getName").invoke(module);
    } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      return null;
    }
  }

  /**
   * Details of a max direct memory specification.
   */
  public static class MaxDirectMemoryInfo {
    private final Long maxDirectMemory;
    private final String valueSource;
    private final Throwable maxDirectMemoryAccessFault;

    private MaxDirectMemoryInfo(Long maxDirectMemory, String valueSource, Throwable maxDirectMemoryAccessFault) {
      this.maxDirectMemory = maxDirectMemory;
      this.valueSource = valueSource;
      this.maxDirectMemoryAccessFault = maxDirectMemoryAccessFault;
    }

    /**
     * Gets the max direct memory value, in bytes.
     *
     * @return the max direct memory value; may be {@code null} if the value is not available
     */
    public Long maxDirectMemory() {
      return this.maxDirectMemory;
    }

    /**
     * Gets the name of the source of the max direct memory value.
     *
     * @return the source name; the name of an internal JVM class if the "live" max direct memory value
     * was obtained, otherwise a constant indicating the option from which the value was interpreted
     */
    public String valueSource() {
      return this.valueSource;
    }

    /**
     * Gets the reason the max direct memory value is not available.
     *
     * @return {@code null} if a mex direct memory is available, otherwise the {@code toString} of the exception
     * describing the access fault
     */
    public String maxDirectMemoryAccessFault() {
      return (this.maxDirectMemoryAccessFault == null ? "" : this.maxDirectMemoryAccessFault.toString());
    }
  }
}
