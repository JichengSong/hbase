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

package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.net.UnknownHostException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.metrics.ScanMetrics;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.DNS;

/**该类可以重试scanner的操作,如create,next等. 被HTable生成的ResultScanner使用.
 * Retries scanner operations such as create, next, etc.
 * Used by {@link ResultScanner}s made by {@link HTable}.
 */
public class ScannerCallable extends ServerCallable<Result[]> {
  public static final String LOG_SCANNER_LATENCY_CUTOFF	//日志(截断)延迟时间
    = "hbase.client.log.scanner.latency.cutoff";
  public static final String LOG_SCANNER_ACTIVITY = "hbase.client.log.scanner.activity";
  private static final Log LOG = LogFactory.getLog(ScannerCallable.class);
  private long scannerId = -1L;				//scannerId,HRegionServer用其标志一个scanner
  private boolean instantiated = false;		//connection是否已经实例化.
  private boolean closed = false;			//该ScannerCallable是否已经被close
  private Scan scan;						//
  private int caching = 1;
  private ScanMetrics scanMetrics;
  private boolean logScannerActivity = false;//是否记录scanner的活动日志
  private int logCutOffLatency = 1000;	     //日志延迟时间
  private static String myAddress;
  static {
    try {
      myAddress = DNS.getDefaultHost("default", "default");
    } catch (UnknownHostException uhe) {
      LOG.error("cannot determine my address", uhe);
    }
  }

  // indicate if it is a remote server call
  private boolean isRegionServerRemote = true;

  /**
   * @param connection which connection
   * @param tableName table callable is on
   * @param scan the scan to execute
   * @param scanMetrics the ScanMetrics to used, if it is null, ScannerCallable
   * won't collect metrics
   */
  public ScannerCallable (HConnection connection, byte [] tableName, Scan scan,
    ScanMetrics scanMetrics) {
    super(connection, tableName, scan.getStartRow());
    this.scan = scan;
    this.scanMetrics = scanMetrics;
    Configuration conf = connection.getConfiguration();
    logScannerActivity = conf.getBoolean(LOG_SCANNER_ACTIVITY, false);
    logCutOffLatency = conf.getInt(LOG_SCANNER_LATENCY_CUTOFF, 1000);
  }

  /**重写ServerCallable.connect(boolean)方法,withoutRetries()和withRetries()方法都会用到该方法
   * @param reload force reload of server location
   * @throws IOException
   */
  @Override
  public void connect(boolean reload) throws IOException {
    if (!instantiated || reload) {
      super.connect(reload);
      checkIfRegionServerIsRemote();
      instantiated = true;
    }
    // 检查retry的间隔时间，需要进行retry时，HConnectionManager将会将reload参数置true以请求实例化connection
    // check how often we retry.
    // HConnectionManager will call instantiateServer with reload==true
    // if and only if for retries.
    if (reload && this.scanMetrics != null) {
      this.scanMetrics.countOfRPCRetries.inc();
      if (isRegionServerRemote) {
        this.scanMetrics.countOfRemoteRPCRetries.inc();
      }
    }
  }

  /**检查RegionServer是否远程server
   * compare the local machine hostname with region server's hostname
   * to decide if hbase client connects to a remote region server
   */
  private void checkIfRegionServerIsRemote() {
    if (this.location.getHostname().equalsIgnoreCase(myAddress)) {
      isRegionServerRemote = false;
    } else {
      isRegionServerRemote = true;
    }
  }

  /**这部分是重点,ClientScanner封装的ScannerCallable对象进行next()等操作时，要通过call()方法向region server发起远程调用
   * @see java.util.concurrent.Callable#call()
   */
  public Result [] call() throws IOException {
    if (scannerId != -1L && closed) {		 //1.如果该ScannerCallable的close标识为true，且scannerId已经被初始化，则关闭scanner。
      close();
    } else if (scannerId == -1L && !closed) {//2.如果ScannerCallable的close标识为false,且scannerId未被初始化，则打开scanner,初始化scannerId.
      this.scannerId = openScanner();		 //  oepnScanner将向regionserver发起rpc请求，在server端打开一个scanner.参HRegion.getScanner
    } else {								 //3.scannser已经被打开,且未关闭
      Result [] rrs = null;
      try {
        incRPCcallsMetrics();//inc metrics
        long timestamp = System.currentTimeMillis();
        rrs = server.next(scannerId, caching);//(3.1).向HRegionServer发起rpc请求:获取scannerId描述的scanner的下一行内容(Result).
        if (logScannerActivity) { //如果开启了scanner活动日志,写log.
          long now = System.currentTimeMillis();
          if (now - timestamp > logCutOffLatency) {
            int rows = rrs == null ? 0 : rrs.length;
            LOG.info("Took " + (now-timestamp) + "ms to fetch "
              + rows + " rows from scanner=" + scannerId);
          }
        }
        updateResultsMetrics(rrs);//update mectirc result.
      } catch (IOException e) {	  //各种异常处理
        if (logScannerActivity) {
          LOG.info("Got exception in fetching from scanner="
            + scannerId, e);
        }
        IOException ioe = null;
        if (e instanceof RemoteException) {
          ioe = RemoteExceptionHandler.decodeRemoteException((RemoteException)e);
        }
        if (ioe == null) throw new IOException(e);
        if (logScannerActivity && (ioe instanceof UnknownScannerException)) {
          try {
            HRegionLocation location =
              connection.relocateRegion(tableName, scan.getStartRow());
            LOG.info("Scanner=" + scannerId
              + " expired, current region location is " + location.toString()
              + " ip:" + location.getServerAddress().getBindAddress());
          } catch (Throwable t) {
            LOG.info("Failed to relocate region", t);
          }
        }
        if (ioe instanceof NotServingRegionException) {
          // Throw a DNRE so that we break out of cycle of calling NSRE
          // when what we need is to open scanner against new location.
          // Attach NSRE to signal client that it needs to resetup scanner.
          if (this.scanMetrics != null) {
            this.scanMetrics.countOfNSRE.inc();
          }
          throw new DoNotRetryIOException("Reset scanner", ioe);
        } else if (ioe instanceof RegionServerStoppedException) {
          // Throw a DNRE so that we break out of cycle of calling RSSE
          // when what we need is to open scanner against new location.
          // Attach RSSE to signal client that it needs to resetup scanner.
          throw new DoNotRetryIOException("Reset scanner", ioe);
        } else {
          // The outer layers will retry
          throw ioe;
        }
      }
      return rrs;				//(3.2)返回结果
    }
    return null;
  }

  private void incRPCcallsMetrics() {
    if (this.scanMetrics == null) {
      return;
    }
    this.scanMetrics.countOfRPCcalls.inc();
    if (isRegionServerRemote) {
      this.scanMetrics.countOfRemoteRPCcalls.inc();
    }
  }

  private void updateResultsMetrics(Result[] rrs) {
    if (this.scanMetrics == null || rrs == null) {
      return;
    }
    for (Result rr : rrs) {
      this.scanMetrics.countOfBytesInResults.inc(rr.getBytes().getLength());
      if (isRegionServerRemote) {
        this.scanMetrics.countOfBytesInRemoteResults.inc(
          rr.getBytes().getLength());
      }
    }
  }

  private void close() {
    if (this.scannerId == -1L) {
      return;
    }
    try {
      incRPCcallsMetrics();
      this.server.close(this.scannerId);
    } catch (IOException e) {
      LOG.warn("Ignore, probably already closed", e);
    }
    this.scannerId = -1L;
  }
  /**打开scanner**/
  protected long openScanner() throws IOException {
    incRPCcallsMetrics();
    long id = this.server.openScanner(this.location.getRegionInfo().getRegionName(),
       this.scan);
    if (logScannerActivity) {
      LOG.info("Open scanner=" + id + " for scan=" + scan.toString()
        + " on region " + this.location.toString() + " ip:"
        + this.location.getServerAddress().getBindAddress());
    }
    return id;
  }

  protected Scan getScan() {
    return scan;
  }

  /**
   * Call this when the next invocation of call should close the scanner
   */
  public void setClose() {
    this.closed = true;
  }

  /**
   * @return the HRegionInfo for the current region
   */
  public HRegionInfo getHRegionInfo() {
    if (!instantiated) {
      return null;
    }
    return location.getRegionInfo();
  }

  /**
   * Get the number of rows that will be fetched on next
   * @return the number of rows for caching
   */
  public int getCaching() {
    return caching;
  }

  /**
   * Set the number of rows that will be fetched on next
   * @param caching the number of rows for caching
   */
  public void setCaching(int caching) {
    this.caching = caching;
  }
}
