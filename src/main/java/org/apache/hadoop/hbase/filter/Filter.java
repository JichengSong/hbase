/*
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

package org.apache.hadoop.hbase.filter;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.io.Writable;

import java.util.List;

/**Filter是一个直接作用于regionserver端的对行和列进行过滤的接口.
 * Interface for row and column filters directly applied within the regionserver.
 *
 * A filter can expect the following call sequence:
 * <ul>调用顺序:reset()->filterAllRemaining()->filterRowKey()->filterKeyValue()->filterRow(List)->filterRow()
 *   <li> {@link #reset()} : reset the filter state before filtering a new row. </li>
 *   <li> {@link #filterAllRemaining()}: true means row scan is over; false means keep going. </li>
 *   <li> {@link #filterRowKey(byte[],int,int)}: true means drop this row; false means include.</li>
 *   <li> {@link #filterKeyValue(KeyValue)}: decides whether to include or exclude this KeyValue.
 *        See {@link ReturnCode}. </li>
 *   <li> {@link #transform(KeyValue)}: if the KeyValue is included, let the filter transform the
 *        KeyValue. </li>
 *   <li> {@link #filterRow(List)}: allows direct modification of the final list to be submitted
 *   <li> {@link #filterRow()}: last chance to drop entire row based on the sequence of
 *        filter calls. Eg: filter a row if it doesn't contain a specified column. </li>
 * </ul>
 *
 * Filter instances are created one per region/scan.  This interface replaces
 * the old RowFilterInterface.
 *
 * When implementing your own filters, consider inheriting {@link FilterBase} to help
 * you reduce boilerplate.
 * 
 * @see FilterBase
 */
public interface Filter extends Writable {
  /**在对新的一行进行filter之前，重置filter.
   * Reset the state of the filter between rows.
   */
  public void reset();

  /**根据row key对一行数据进行过滤.
   * Filters a row based on the row key. If this returns true, the entire
   * row will be excluded.  If false, each KeyValue in the row will be
   * passed to {@link #filterKeyValue(KeyValue)} below.
   *
   * @param buffer buffer containing row key
   * @param offset offset into buffer where row key starts
   * @param length length of the row key
   * @return true, remove entire row, false, include the row (maybe).
   */
  public boolean filterRowKey(byte [] buffer, int offset, int length);

  /**如果返回true,表明对该行的filter操作已经执行结束:true to end scan,false to continue。
   * If this returns true, the scan will terminate.
   *
   * @return true to end scan, false to continue.
   */
  public boolean filterAllRemaining();

  /**一种基于column family,column qualifier,以及/或value进行过滤的方法.
   * A way to filter based on the column family, column qualifier and/or the
   * column value. Return code is described below.  This allows filters to
   * filter only certain number of columns, then terminate without matching ever
   * column.
   *如果filter返回了ReturnCode.NEXT_ROW,在调用reset()方法之前，该filterKeyValue方法将一直返回ReturnCode.NEXT_ROW.
   * If your filter returns <code>ReturnCode.NEXT_ROW</code>, it should return
   * <code>ReturnCode.NEXT_ROW</code> until {@link #reset()} is called
   * just in case the caller calls for the next row.
   *
   * @param v the KeyValue in question
   * @return code as described below
   * @see Filter.ReturnCode
   */
  public ReturnCode filterKeyValue(final KeyValue v);

  /**给filter一个改变传过来的KeyValue的机会. transformed KeyValue是最终返回给用户的KeyValue.
   * Give the filter a chance to transform the passed KeyValue.
   * If the KeyValue is changed a new KeyValue object must be returned.
   * @see org.apache.hadoop.hbase.KeyValue#shallowCopy()
   *
   * The transformed KeyValue is what is eventually returned to the
   * client. Most filters will return the passed KeyValue unchanged.
   * @see org.apache.hadoop.hbase.filter.KeyOnlyFilter#transform(KeyValue)
   * for an example of a transformation.
   *
   * @param v the KeyValue in question
   * @return the changed KeyValue
   */
  public KeyValue transform(final KeyValue v);

  /**filterValue方法的返回值码.
   * Return codes for filterValue().
   */
  public enum ReturnCode {
    /**
     * Include the KeyValue
     */
    INCLUDE,
    /**
     * Include the KeyValue and seek to the next column skipping older versions.
     */
    INCLUDE_AND_NEXT_COL,
    /**
     * Skip this KeyValue
     */
    SKIP,
    /**
     * Skip this column. Go to the next column in this row.
     */
    NEXT_COL,
    /**
     * Done with columns, skip to next row. Note that filterRow() will
     * still be called.
     */
    NEXT_ROW,
    /**
     * Seek to next key which is given as hint by the filter.
     */
    SEEK_NEXT_USING_HINT,
}

  /**用于对一组将要提交的KeyValue进行更改.
   * Chance to alter the list of keyvalues to be submitted.
   * Modifications to the list will carry on
   * @param kvs the list of keyvalues to be filtered
   */
  public void filterRow(List<KeyValue> kvs);

  /**如果当前filter使用了filterRow(List)方法，则返回true.
   * @return True if this filter actively uses filterRow(List).
   * Primarily used to check for conflicts with scans(such as scans
   * that do not read a full row at a time)
   */
  public boolean hasFilterRow();

  /**这是在前面filterKeyValue(KeyValue)的基础上,过滤掉一行数据的最后一次机会.
   * Last chance to veto row based on previous {@link #filterKeyValue(KeyValue)}
   * calls. The filter needs to retain state then return a particular value for
   * this call if they wish to exclude a row if a certain column is missing
   * (for example).
   * @return true to exclude row, false to include row.
   */
  public boolean filterRow();

  /**如果filter返回SEEK_NET_USING_HINT,它同样应该返回他必须要seek的下一个key.
   * If the filter returns the match code SEEK_NEXT_USING_HINT, then
   * it should also tell which is the next key it must seek to.
   * After receiving the match code SEEK_NEXT_USING_HINT, the QueryMatcher would
   * call this function to find out which key it must next seek to.
   * @return KeyValue which must be next seeked. return null if the filter is
   * not sure which key to seek to next.
   */
  public KeyValue getNextKeyHint(final KeyValue currentKV);
}
