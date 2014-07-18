/**
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
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Split size is the number of regions that are on this server that all are
 * of the same table, cubed, times 2x the region flush size OR the maximum
 * region split size, whichever is smaller.  For example, if the flush size
 * is 128M, then after two flushes (256MB) we will split which will make two regions
 * that will split when their size is 2^3 * 128M*2 = 2048M.  If one of these
 * regions splits, then there are three regions and now the split size is
 * 3^3 * 128M*2 =  6912M, and so on until we reach the configured
 * maximum filesize and then from there on out, we'll use that.
 */
public class IncreasingToUpperBoundRegionSplitPolicy
extends ConstantSizeRegionSplitPolicy {
  static final Log LOG =
    LogFactory.getLog(IncreasingToUpperBoundRegionSplitPolicy.class);
  private long initialSize;

  @Override
  protected void configureForRegion(HRegion region) {
    super.configureForRegion(region);
    Configuration conf = getConf();
    this.initialSize = conf.getLong("hbase.increasing.policy.initial.size", -1);
    if (this.initialSize > 0) {
      return;
    }
    HTableDescriptor desc = region.getTableDesc();
    if (desc != null) {
      this.initialSize = 2*desc.getMemStoreFlushSize();
    }
    if (this.initialSize <= 0) {
      this.initialSize = 2*conf.getLong(HConstants.HREGION_MEMSTORE_FLUSH_SIZE,
        HTableDescriptor.DEFAULT_MEMSTORE_FLUSH_SIZE);
    }
  }
  /**检查是否应该split*/
  @Override
  protected boolean shouldSplit() {
    if (region.shouldForceSplit()) return true;//1.如果是强制split(也即有split request)，返回true.
    boolean foundABigStore = false;
    // Get count of regions that have the same common table as this.region
    int tableRegionsCount = getCountOfCommonTableRegions();//2.1计算当前rs上拥有当前table的region数目
    // Get size to check                                   //2.2:返回Math.main(tableRegionsCount*tableRegionsCount*MEMSTORE_FLUSHSIZE，
    long sizeToCheck = getSizeToCheck(tableRegionsCount);  //MAX_FILESIZE);其中MEMTORE_FLUSHSIZE MAX_FILESIZE在创建时指定，如果未指定，则
    //3.判断是否可以split									   //取hbase.hregion.memstore.flush.size(默认128M),hbase.hregion.max.filesize(默认10G)  		
    for (Store store : region.getStores().values()) {
      // If any of the stores is unable to split (eg they contain reference files)
      // then don't split
      if ((!store.canSplit())) {//3.1如果该region的任何一个store不能split，返回false;(如果一个store所拥有的storefiles里，
        return false;		    //任一个storfile属于reference store file,说明该store file被open()了，现在不能split)
      }
      							//3.2 若有store的大小大约2.2得到的值，foundABigStore=true.
      // Mark if any store is big enough
      long size = store.getSize();
      if (size > sizeToCheck) {
        LOG.debug("ShouldSplit because " + store.getColumnFamilyName() +
          " size=" + size + ", sizeToCheck=" + sizeToCheck +
          ", regionsWithCommonTable=" + tableRegionsCount);
        foundABigStore = true;
      }
    }
    //4.返回foundABigStore
    return foundABigStore;
  }

  /**
   * @return Region max size or <code>count of regions squared * flushsize, which ever is
   * smaller; guard against there being zero regions on this server.
   */
  protected long getSizeToCheck(final int tableRegionsCount) {
    // safety check for 100 to avoid numerical overflow in extreme cases
    return tableRegionsCount == 0 || tableRegionsCount > 100 ? getDesiredMaxFileSize():
      Math.min(getDesiredMaxFileSize(),
        this.initialSize * tableRegionsCount * tableRegionsCount * tableRegionsCount);
  }

  /**
   * @return Count of regions on this server that share the table this.region
   * belongs to
   */
  private int getCountOfCommonTableRegions() {
    RegionServerServices rss = this.region.getRegionServerServices();
    // Can be null in tests
    if (rss == null) return 0;
    byte [] tablename = this.region.getTableDesc().getName();
    int tableRegionsCount = 0;
    try {
      List<HRegion> hri = rss.getOnlineRegions(tablename);
      tableRegionsCount = hri == null || hri.isEmpty()? 0: hri.size();
    } catch (IOException e) {
      LOG.debug("Failed getOnlineRegions " + Bytes.toString(tablename), e);
    }
    return tableRegionsCount;
  }
}
