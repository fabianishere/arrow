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

package org.apache.arrow.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.arrow.memory.util.MemoryUtil;
import org.junit.Test;

public class TestOpens {
  /** Instantiating the RootAllocator should poke MemoryUtil, but should not fail anymore on JDK16+. */
  @Test
  public void testMemoryUtilDoesNotFail() {
    // This test is configured by Maven to run WITHOUT add-opens. Previously, this would fail on JDK16+
    // (where JEP396 means that add-opens is required to access JDK internals).
    assertDoesNotThrow(() -> {
      BufferAllocator allocator = new RootAllocator();
      allocator.close();
    });
  }

  /** Creating a direct buffer via MemoryUtil does not work on JDK16+ due to illegal reflective access. */
  @Test
  public void testMemoryUtilDirectBufferFails() {
    // This test is configured by Maven to run WITHOUT add-opens. Previously, this would fail on JDK16+
    // (where JEP396 means that add-opens is required to access JDK internals).
    assertThrows(UnsupportedOperationException.class, () -> {
      MemoryUtil.directBuffer(0, 10);
    });
  }
}
