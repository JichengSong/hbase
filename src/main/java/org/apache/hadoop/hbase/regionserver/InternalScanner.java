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

import org.apache.hadoop.hbase.KeyValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**(1)内部scanner和客户端的scanner不同,因为他们在HStoreKeys和byte[]上操作而不是RowResults.
 * 因为内部scanner和数据的物理存储紧密相关,因此也更容易根据数据的物理存储方式与之交互.对StoredMaps结果数据进行merge比对RowResults更容易.
 * Internal scanners differ from client-side scanners in that they operate on
 * HStoreKeys and byte[] instead of RowResults. This is because they are
 * actually close to how the data is physically stored, and therefore it is more
 * convenient to interact with them that way. It is also much easier to merge
 * the results across SortedMaps than RowResults.(2).另外,我们要确定scanner是否在通配符匹配，如果是,我们需要忽略timestamp以保证
 * <p>Additionally, we need to be able to determine if the scanner is doing//我们能获取所有的family 成员,因为他们最后一次更新
 * wildcard column matches (when only a column family is specified or if a //时间可能不同.
 * column regex is specified) or if multiple members of the same column family
 * were specified. If so, we need to ignore the timestamp to ensure that we get
 * all the family members, as they may have been last updated at different
 * times.
 */
public interface InternalScanner extends Closeable {
  /**获取下一行的值，结果放到results里;如果还有更多数据，返回true,如果scan结束了，返回false.
   * Grab the next row's worth of values.
   * @param results return output array
   * @return true if more rows exist after this one, false if scanner is done
   * @throws IOException e
   */
  public boolean next(List<KeyValue> results) throws IOException;
  
  /**获取下一行的值，结果放到results里;如果还有更多数据，返回true,如果scan结束了，返回false.
   * Grab the next row's worth of values.
   * @param results return output array
   * @param metric the metric name
   * @return true if more rows exist after this one, false if scanner is done
   * @throws IOException e
   */
  public boolean next(List<KeyValue> results, String metric) throws IOException;

  /**获取下一行的值，结果放到results里;如果还有更多数据，返回true,如果scan结束了，返回false.
   * Grab the next row's worth of values with a limit on the number of values
   * to return.
   * @param result return output array
   * @param limit limit on row count to get
   * @return true if more rows exist after this one, false if scanner is done
   * @throws IOException e
   */
  public boolean next(List<KeyValue> result, int limit) throws IOException;
  
  /**获取下一行的值，结果放到results里;如果还有更多数据，返回true,如果scan结束了，返回false.
   * Grab the next row's worth of values with a limit on the number of values
   * to return.
   * @param result return output array
   * @param limit limit on row count to get
   * @param metric the metric name
   * @return true if more rows exist after this one, false if scanner is done
   * @throws IOException e
   */
  public boolean next(List<KeyValue> result, int limit, String metric) throws IOException;

  /**
   * Closes the scanner and releases any resources it has allocated
   * @throws IOException
   */
  public void close() throws IOException;
}
