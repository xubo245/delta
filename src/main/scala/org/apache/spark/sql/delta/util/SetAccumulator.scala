/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.util

import java.util.Collections

import org.apache.spark.util.AccumulatorV2

/**
 * Accumulator to collect distinct elements as a set.
 */
class SetAccumulator[T] extends AccumulatorV2[T, java.util.Set[T]] {
  private val _set = Collections.synchronizedSet(new java.util.HashSet[T]())

  override def isZero: Boolean = _set.isEmpty

  override def reset(): Unit = _set.clear()

  override def add(v: T): Unit = _set.add(v)

  override def merge(other: AccumulatorV2[T, java.util.Set[T]]): Unit = _set.addAll(other.value)

  override def value: java.util.Set[T] = _set

  override def copy(): AccumulatorV2[T, java.util.Set[T]] = {
    val newAcc = new SetAccumulator[T]()
    newAcc._set.addAll(_set)
    newAcc
  }
}
