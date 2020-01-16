/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.transport.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.transport.common.Callback;
import com.linkedin.pinot.transport.common.NoneType;
import com.linkedin.pinot.transport.metrics.NettyClientMetrics;
import com.linkedin.pinot.transport.pool.KeyedPool;
import com.linkedin.pinot.transport.pool.PooledResourceManager;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;


public class PooledNettyClientResourceManager implements PooledResourceManager<ServerInstance, NettyClientConnection> {

  protected static Logger LOGGER = LoggerFactory.getLogger(PooledNettyClientResourceManager.class);

  private KeyedPool<ServerInstance, NettyClientConnection> _pool;
  private final EventLoopGroup _eventLoop;
  private final NettyClientMetrics _metrics;
  private final Timer _timer;

  public PooledNettyClientResourceManager(EventLoopGroup eventLoop, Timer timer, NettyClientMetrics metrics) {
    _eventLoop = eventLoop;
    _metrics = metrics;
    _timer = timer;
  }

  public void setPool(KeyedPool<ServerInstance, NettyClientConnection> pool) {
    _pool = pool;
  }

  @Override
  public NettyClientConnection create(ServerInstance key) {
    NettyClientConnection conn = new PooledClientConnection(_pool, key, _eventLoop, _timer, _metrics);
    conn.connect();
    // At this point, we have already waited for a connection to complete. Whether it succeeds or fails,
    // we should return the object to the pool. It is possible to return null if the connection attempt
    // fails (i.e. we get back a false return above), but then the pool starts to initiate new connections
    // to the host to maintain the minimum pool size. We don't want to DOS the server continuously
    // trying to create new connections. A connection is always validated before the pool returns it
    // from the idle list to the user.
    return conn;
  }

  @Override
  public boolean destroy(ServerInstance key, boolean isBad, NettyClientConnection resource) {

    LOGGER.info("Destroying client connection to server :" + key + " isBad:"+ isBad);
    boolean closed = false;
    try {
      resource.close();
      closed = true;
    } catch (InterruptedException e) {
      LOGGER.error("Got interrupted exception when closing resource", e);
    }

    return closed;
  }

  @Override
  public boolean validate(ServerInstance key, NettyClientConnection resource) {
    if ( null != resource)
      return resource.validate();
    return false;
  }

  /**
   * Pool aware NettyTCPClientConnection
   *
   */
  public class PooledClientConnection extends NettyTCPClientConnection implements Callback<NoneType> {
    private final KeyedPool<ServerInstance, NettyClientConnection> _pool;

    public PooledClientConnection(KeyedPool<ServerInstance, NettyClientConnection> pool, ServerInstance server,
        EventLoopGroup eventGroup, Timer timer, NettyClientMetrics metric) {
      super(server, eventGroup, timer, metric);
      _pool = pool;
      init();
    }

    public void init() {
      setRequestCallback(this);
    }

    @Override
    public void onSuccess(NoneType arg0) {
      /**
       * We got the response successfully. Time to checkin back to the pool.
       */
      _pool.checkinObject(getServer(), this);
    }

    @Override
    public void onError(Throwable arg0) {
      if (isSelfClose()) {
        LOGGER.info("Got notified on self-close to {}", _server );
      } else {
        LOGGER.error("Destroying connection {} due to error", _server, arg0);
      }
      /**
       * We got error. Time to discard this connection.
       */
      _pool.destroyObject(getServer(), this);
    }
  }
}