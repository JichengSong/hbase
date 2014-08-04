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

package org.apache.hadoop.hbase.filter;

import org.apache.hadoop.hbase.io.HbaseObjectWritable;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;

import com.google.common.base.Preconditions;
/**这是一个通用的可以通过比较来进行过滤的过滤器
 * This is a generic filter to be used to filter by comparison.  It takes an
 * operator (equal, greater, not equal, etc) and a byte [] comparator.
 * <p>
 * To filter by row key, use {@link RowFilter}.
 * <p>
 * To filter by column qualifier, use {@link QualifierFilter}.
 * <p>
 * To filter by value, use {@link SingleColumnValueFilter}.
 * <p>
 * These filters can be wrapped with {@link SkipFilter} and {@link WhileMatchFilter}
 * to add more control.
 * <p>
 * Multiple filters can be combined using {@link FilterList}.
 */
public abstract class CompareFilter extends FilterBase {

  /** Comparison operators. */
  public enum CompareOp {
    /** less than */
    LESS,
    /** less than or equal to */
    LESS_OR_EQUAL,
    /** equals */
    EQUAL,
    /** not equal */
    NOT_EQUAL,
    /** greater than or equal to */
    GREATER_OR_EQUAL,
    /** greater than */
    GREATER,
    /** no operation */
    NO_OP,
  }

  protected CompareOp compareOp;
  protected WritableByteArrayComparable comparator;

  /**
   * Writable constructor, do not use.
   */
  public CompareFilter() {
  }

  /**CompareFilter构造函数，compareOp为比较操作符,comparator为比较器/比较者.
   * Constructor.
   * @param compareOp the compare op for row matching
   * @param comparator the comparator for row matching
   */
  public CompareFilter(final CompareOp compareOp,
      final WritableByteArrayComparable comparator) {
    this.compareOp = compareOp;
    this.comparator = comparator;
  }

  /**
   * @return operator
   */
  public CompareOp getOperator() {
    return compareOp;
  }

  /**
   * @return the comparator
   */
  public WritableByteArrayComparable getComparator() {
    return comparator;
  }
  /**进行比较*/
  protected boolean doCompare(final CompareOp compareOp,
      final WritableByteArrayComparable comparator, final byte [] data,
      final int offset, final int length) {
    if (compareOp == CompareOp.NO_OP) {
      return true;
    }
    int compareResult = comparator.compareTo(data, offset, length);
    switch (compareOp) {
      case LESS:
        return compareResult <= 0;
      case LESS_OR_EQUAL:
        return compareResult < 0;
      case EQUAL:
        return compareResult != 0;
      case NOT_EQUAL:
        return compareResult == 0;
      case GREATER_OR_EQUAL:
        return compareResult > 0;
      case GREATER:
        return compareResult >= 0;
      default:
        throw new RuntimeException("Unknown Compare op " +
          compareOp.name());
    }
  }
  /**从参数里抽取compareOp和comparator,放到一个list作为结果返回*/
  public static ArrayList extractArguments(ArrayList<byte []> filterArguments) {
    Preconditions.checkArgument(filterArguments.size() == 2,
                                "Expected 2 but got: %s", filterArguments.size());
    CompareOp compareOp = ParseFilter.createCompareOp(filterArguments.get(0));
    WritableByteArrayComparable comparator = ParseFilter.createComparator(
      ParseFilter.removeQuotesFromByteArray(filterArguments.get(1)));

    if (comparator instanceof RegexStringComparator ||
        comparator instanceof SubstringComparator) {
      if (compareOp != CompareOp.EQUAL &&
          compareOp != CompareOp.NOT_EQUAL) {
        throw new IllegalArgumentException ("A regexstring comparator and substring comparator" +
                                            " can only be used with EQUAL and NOT_EQUAL");
      }
    }
    ArrayList arguments = new ArrayList();
    arguments.add(compareOp);
    arguments.add(comparator);
    return arguments;
  }
  /**从数据输入流里读取compareOp和comparator对象*/
  public void readFields(DataInput in) throws IOException {
    compareOp = CompareOp.valueOf(in.readUTF());
    comparator = (WritableByteArrayComparable)
      HbaseObjectWritable.readObject(in, null);
  }
  /**将compareOp和comparator写入流*/
  public void write(DataOutput out) throws IOException {
    out.writeUTF(compareOp.name());
    HbaseObjectWritable.writeObject(out, comparator,
      WritableByteArrayComparable.class, null);
  }

  @Override
  public String toString() {
    return String.format("%s (%s, %s)",
        this.getClass().getSimpleName(),
        this.compareOp.name(),
        Bytes.toStringBinary(this.comparator.getValue()));
  }
}
