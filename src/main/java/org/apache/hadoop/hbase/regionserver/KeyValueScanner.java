/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.SortedSet;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Scan;

/**扫描器,能返回下一个KeyValue.
 * Scanner that returns the next KeyValue.
 */
public interface KeyValueScanner {
  /**查找下一个KeyValue，但是不迭代(ps:连续多次peek返回的都是同一个KeyValue)
   * Look at the next KeyValue in this scanner, but do not iterate scanner.
   * @return the next KeyValue
   */
  public KeyValue peek();

  /**返回scanner的下一个KeyValue,迭代scanner.(联系两次next返回的是两个相临的KeyValue)
   * Return the next KeyValue in this scanner, iterating the scanner
   * @return the next KeyValue
   */
  public KeyValue next() throws IOException;

  /**将scanner的当前扫描光标移动到给定的KeyValue(或给定的KeyValue之后)
   * Seek the scanner at or after the specified KeyValue.
   * @param key seek value
   * @return true if scanner has values left, false if end of scanner
   */
  public boolean seek(KeyValue key) throws IOException;

  /**重新移动scanner的扫描光标到给定的KeyValue(或给定的KeyValue之后).这个方法确保给定的keyvalue在当前光标(current postion)之后产生时，才进行seek操作
   * Reseek the scanner at or after the specified KeyValue.
   * This method is guaranteed to seek at or after the required key only if the
   * key comes after the current position of the scanner. Should not be used
   * to seek to a key which may come before the current position.
   * @param key seek value (should be non-null)
   * @return true if scanner has values left, false if end of scanner
   */
  public boolean reseek(KeyValue key) throws IOException;

  /**获取当前KeyValueScanner相关的sequence id. 这西药对比多个file以找到最新的数据. sequence id 越小，表明该数据越旧.
   * Get the sequence id associated with this KeyValueScanner. This is required
   * for comparing multiple files to find out which one has the latest data.
   * The default implementation for this would be to return 0. A file having
   * lower sequence id will be considered to be the older one.
   */
  public long getSequenceID();

  /**
   * Close the KeyValue scanner.
   */
  public void close();

  /**是否允许过滤掉该scanner. (根据Bloom filter ，timestamp范围等)
   * Allows to filter out scanners (both StoreFile and memstore) that we don't
   * want to use based on criteria such as Bloom filters and timestamp ranges.
   * @param scan the scan that we are selecting scanners for
   * @param columns the set of columns in the current column family, or null if
   *          not specified by the scan
   * @param oldestUnexpiredTS the oldest timestamp we are interested in for
   *          this query, based on TTL
   * @return true if the scanner should be included in the query
   */
  public boolean shouldUseScanner(Scan scan, SortedSet<byte[]> columns,
      long oldestUnexpiredTS);

  // "Lazy scanner" optimizations

  /**
   * Similar to {@link #seek} (or {@link #reseek} if forward is true) but only
   * does a seek operation after checking that it is really necessary for the
   * row/column combination specified by the kv parameter. This function was
   * added to avoid unnecessary disk seeks by checking row-column Bloom filters
   * before a seek on multi-column get/scan queries, and to optimize by looking
   * up more recent files first.
   * @param forward do a forward-only "reseek" instead of a random-access seek
   * @param useBloom whether to enable multi-column Bloom filter optimization
   */
  public boolean requestSeek(KeyValue kv, boolean forward, boolean useBloom)
      throws IOException;

  
  /**
   * We optimize our store scanners by checking the most recent store file
   * first, so we sometimes pretend we have done a seek but delay it until the
   * store scanner bubbles up to the top of the key-value heap. This method is
   * then used to ensure the top store file scanner has done a seek operation.
   */
  public boolean realSeekDone();

  /**
   * Does the real seek operation in case it was skipped by
   * seekToRowCol(KeyValue, boolean) (TODO: Whats this?). Note that this function should
   * be never called on scanners that always do real seek operations (i.e. most
   * of the scanners). The easiest way to achieve this is to call
   * {@link #realSeekDone()} first.
   */
  public void enforceSeek() throws IOException;

  /**是否为file scanner.(非file scanner,就是memory scanner)
   * @return true if this is a file scanner. Otherwise a memory scanner is
   *         assumed.
   */
  public boolean isFileScanner();  
}
