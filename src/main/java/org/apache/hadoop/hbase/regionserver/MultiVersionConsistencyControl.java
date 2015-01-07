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

import java.util.LinkedList;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

/**
 * Manages the read/write consistency within memstore. This provides
 * an interface for readers to determine what entries to ignore, and
 * a mechanism for writers to obtain new write numbers, then "commit"
 * the new writes for readers to read (thus forming atomic transactions).
 */
public class MultiVersionConsistencyControl {
  private volatile long memstoreRead = 0;//用于记录当前全局刻读的readPoint.
  private volatile long memstoreWrite = 0;//用于记录当前全局最大的writePoint,根据它为下一个事物生成新的writePoint

  private final Object readWaiters = new Object();

  // This is the pending queue of writes.
  private final LinkedList<WriteEntry> writeQueue =
      new LinkedList<WriteEntry>();
  //ThreadLocal类型的perThreadReadPoint 为了保证每个客户端请求能够记录自己发起请求时刻的readPoint。
  private static final ThreadLocal<Long> perThreadReadPoint =
      new ThreadLocal<Long>() {
       @Override
      protected
       Long initialValue() {
         return Long.MAX_VALUE;
       }
  };

  /**
   * Default constructor. Initializes the memstoreRead/Write points to 0.
   */
  public MultiVersionConsistencyControl() {
    this.memstoreRead = this.memstoreWrite = 0;
  }

  /**
   * Initializes the memstoreRead/Write points appropriately.
   * @param startPoint
   */
  public void initialize(long startPoint) {
    synchronized (writeQueue) {
      if (this.memstoreWrite != this.memstoreRead) {
        throw new RuntimeException("Already used this mvcc. Too late to initialize");
      }

      this.memstoreRead = this.memstoreWrite = startPoint;
    }
  }

  /**
   * Get this thread's read point. Used primarily by the memstore scanner to
   * know which values to skip (ie: have not been completed/committed to
   * memstore).
   */
  public static long getThreadReadPoint() {
      return perThreadReadPoint.get();
  }

  /**
   * Set the thread read point to the given value. The thread MVCC
   * is used by the Memstore scanner so it knows which values to skip.
   * Give it a value of 0 if you want everything.
   */
  public static void setThreadReadPoint(long readPoint) {
    perThreadReadPoint.set(readPoint);
  }

  /**
   * Set the thread MVCC read point to whatever the current read point is in
   * this particular instance of MVCC.  Returns the new thread read point value.
   */
  public static long resetThreadReadPoint(MultiVersionConsistencyControl mvcc) {
    perThreadReadPoint.set(mvcc.memstoreReadPoint());
    return getThreadReadPoint();
  }

  /**
   * Set the thread MVCC read point to 0 (include everything).
   */
  public static void resetThreadReadPoint() {
    perThreadReadPoint.set(0L);
  }
  /**1.这个是MVCC类的核心方法:开始更新一个操作,将memstoreWrite加1，创建writeEntry并插入到writeQueue,并返回writeEntry对象*/
  public WriteEntry beginMemstoreInsert() {
    synchronized (writeQueue) {
      long nextWriteNumber = ++memstoreWrite;
      WriteEntry e = new WriteEntry(nextWriteNumber);
      writeQueue.add(e);
      return e;
    }
  }
  /**2.这个是MVCC类的核心方法:完成当前更新操作，将writeEntry对象标记为可读*/
  public void completeMemstoreInsert(WriteEntry e) {
    advanceMemstore(e);//从writeQueue里移除队首开始已经完成的连续的writeEntry元素，最后哦偶将memstoreRead更新为最新已完成的memstoreWrite
    waitForRead(e);    //阻塞当前线程，直到memstoreRead等于当前writeEntry的memstoreWrite.至此表明当前WriteEntry之前的所有更新事物都已经完成.
  }
  /**从头遍历writeQueue,移除从队首开始连续的已经完成的writeEntry,*/
  boolean advanceMemstore(WriteEntry e) {
    synchronized (writeQueue) {
      e.markCompleted();

      long nextReadValue = -1;
      boolean ranOnce=false;
      while (!writeQueue.isEmpty()) {
        ranOnce=true;
        WriteEntry queueFirst = writeQueue.getFirst();
        //
        if (nextReadValue > 0) {//如果netReadValue+1和队首的writeNumber不一致，说明writeQueue有问题,抛出异常
          if (nextReadValue+1 != queueFirst.getWriteNumber()) {
            throw new RuntimeException("invariant in completeMemstoreInsert violated, prev: "
                + nextReadValue + " next: " + queueFirst.getWriteNumber());
          }
        }
        //如果queueFirst已经完成,将其从writeQueue移除,用queueFirst.writeNumber更新netReadValue
        if (queueFirst.isCompleted()) {
          nextReadValue = queueFirst.getWriteNumber();
          writeQueue.removeFirst();
        } else {//否则，退出while循环.
          break;
        }
      }

      if (!ranOnce) {
        throw new RuntimeException("never was a first");
      }
      //nextReadValue>0说明有新完成的writeEntry,唤醒readWaiters
      if (nextReadValue > 0) {
        synchronized (readWaiters) {
          memstoreRead = nextReadValue;
          readWaiters.notifyAll();
        }
      }
      if (memstoreRead >= e.getWriteNumber()) {
        return true;
      }
      return false;
    }
  }

  /**
   * Wait for the global readPoint to advance upto (advance upto：是大于或等于)
   * the specified transaction number. 阻塞当前线程，直到memstoreRead等于当前writeEntry的memstoreWrite.至此表明当前WriteEntry之前的所有更新事物都已经完成.
   */
  public void waitForRead(WriteEntry e) {
    boolean interrupted = false;
    synchronized (readWaiters) {
      while (memstoreRead < e.getWriteNumber()) {
        try {
          readWaiters.wait(0);
        } catch (InterruptedException ie) {
          // We were interrupted... finish the loop -- i.e. cleanup --and then
          // on our way out, reset the interrupt flag.
          interrupted = true;
        }
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  public long memstoreReadPoint() {
    return memstoreRead;
  }


  public static class WriteEntry {
    private long writeNumber;
    private boolean completed = false;
    WriteEntry(long writeNumber) {
      this.writeNumber = writeNumber;
    }
    void markCompleted() {
      this.completed = true;
    }
    boolean isCompleted() {
      return this.completed;
    }
    long getWriteNumber() {
      return this.writeNumber;
    }
  }

  public static final long FIXED_SIZE = ClassSize.align(
      ClassSize.OBJECT +
      2 * Bytes.SIZEOF_LONG +
      2 * ClassSize.REFERENCE);

}
