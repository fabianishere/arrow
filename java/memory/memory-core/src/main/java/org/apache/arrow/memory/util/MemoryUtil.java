/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.memory.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;

import sun.misc.Unsafe;

/**
 * Utilities for memory related operations.
 */
public class MemoryUtil {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MemoryUtil.class);

  private static final Constructor<?> DIRECT_BUFFER_CONSTRUCTOR;
  /**
   * The unsafe object from which to access the off-heap memory.
   */
  public static final Unsafe UNSAFE;

  /**
   * The start offset of array data relative to the start address of the array object.
   */
  public static final long BYTE_ARRAY_BASE_OFFSET;

  /**
   * The offset of the address and capacity field with the {@link java.nio.ByteBuffer} object.
   */
  static final long BYTE_BUFFER_ADDRESS_OFFSET;
  static final long BYTE_BUFFER_CAPACITY_OFFSET;

  /**
   * If the native byte order is little-endian.
   */
  public static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  /**
   * The offset of the cleaner field with the DirectByteBuffer class.
   */
  static final long DIRECT_BUFFER_CLEANER_OFFSET;

  static {
    try {
      // try to get the unsafe object
      final Object maybeUnsafe = AccessController.doPrivileged(new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          try {
            final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            return unsafeField.get(null);
          } catch (Throwable e) {
            return e;
          }
        }
      });

      if (maybeUnsafe instanceof Throwable) {
        throw (Throwable) maybeUnsafe;
      }

      UNSAFE = (Unsafe) maybeUnsafe;

      // get the offset of the data inside a byte array object
      BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);

      // get the offset of the address field in a java.nio.Buffer object
      Field addressField = java.nio.Buffer.class.getDeclaredField("address");
      BYTE_BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(addressField);

      Field capacityField = java.nio.Buffer.class.getDeclaredField("capacity");
      BYTE_BUFFER_CAPACITY_OFFSET = UNSAFE.objectFieldOffset(capacityField);

      final ByteBuffer direct = ByteBuffer.allocateDirect(1);

      // get the offsets for the fields of a direct ByteBuffer
      final Class<?> directCls = direct.getClass();
      long cleanerOffset;
      try {
        cleanerOffset = UNSAFE.objectFieldOffset(directCls.getDeclaredField("cleaner"));
      } catch (NoSuchFieldException ignored) {
        cleanerOffset = -1;
      }
      DIRECT_BUFFER_CLEANER_OFFSET = cleanerOffset;

      Constructor<?> directBufferConstructor;
      Throwable directBufferFailureReason = null;
      long address = -1;
      try {

        final Object maybeDirectBufferConstructor =
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
              @Override
              public Object run() {
                try {
                  final Constructor<?> constructor =
                      direct.getClass().getDeclaredConstructor(long.class, int.class);
                  constructor.setAccessible(true);
                  return constructor;
                } catch (NoSuchMethodException | SecurityException e) {
                  return e;
                } catch (RuntimeException e) {
                  // JDK 9+ can throw an inaccessible object exception here
                  if ("java.lang.reflect.InaccessibleObjectException".equals(e.getClass().getName())) {
                    return e;
                  }

                  throw e;
                }
              }
            });

        if (maybeDirectBufferConstructor instanceof Constructor<?>) {
          address = UNSAFE.allocateMemory(1);
          // try to use the constructor now
          try {
            ((Constructor<?>) maybeDirectBufferConstructor).newInstance(address, 1);
            directBufferConstructor = (Constructor<?>) maybeDirectBufferConstructor;
          } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            directBufferConstructor = null;
            directBufferFailureReason = e;
          }
        } else {
          directBufferConstructor = null;
          directBufferFailureReason = (Throwable) maybeDirectBufferConstructor;
        }


        if (directBufferConstructor != null || DIRECT_BUFFER_CLEANER_OFFSET != -1) {
          logger.debug("direct buffer constructor: available");
        } else {
          logger.warn("direct buffer constructor: unavailable", directBufferFailureReason);
        }

      } finally {
        if (address != -1) {
          UNSAFE.freeMemory(address);
        }
      }

      DIRECT_BUFFER_CONSTRUCTOR = directBufferConstructor;
    } catch (Throwable e) {
      // This exception will get swallowed, but it's necessary for the static analysis that ensures
      // the static fields above get initialized
      final RuntimeException failure = new RuntimeException(
          "Failed to initialize MemoryUtil. Was Java started with " +
              "`--add-opens=java.base/java.nio=ALL-UNNAMED`? " +
              "(See https://arrow.apache.org/docs/java/install.html)", e);
      failure.printStackTrace();
      throw failure;
    }
  }

  /**
   * Given a {@link ByteBuffer}, gets the address the underlying memory space.
   *
   * @param buf the byte buffer.
   * @return address of the underlying memory.
   */
  public static long getByteBufferAddress(ByteBuffer buf) {
    return UNSAFE.getLong(buf, BYTE_BUFFER_ADDRESS_OFFSET);
  }

  private MemoryUtil() {
  }

  /**
   * Create nio byte buffer.
   */
  public static ByteBuffer directBuffer(long address, int capacity) {
    if (DIRECT_BUFFER_CONSTRUCTOR != null) {
      if (capacity < 0) {
        throw new IllegalArgumentException("Capacity is negative, has to be positive or 0");
      }
      try {
        return (ByteBuffer) DIRECT_BUFFER_CONSTRUCTOR.newInstance(address, capacity);
      } catch (Throwable cause) {
        throw new Error(cause);
      }
    } else if (DIRECT_BUFFER_CLEANER_OFFSET != -1) {
      ByteBuffer direct = ByteBuffer.allocateDirect(1);
      final Unsafe unsafe = UNSAFE;

      unsafe.putLong(direct, BYTE_BUFFER_ADDRESS_OFFSET, address);
      unsafe.putInt(direct, BYTE_BUFFER_CAPACITY_OFFSET, capacity);
      unsafe.putObject(direct, DIRECT_BUFFER_CLEANER_OFFSET, null);

      direct.limit(capacity);
      return direct;
    }

    throw new UnsupportedOperationException(
        "sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not available");
  }
}
