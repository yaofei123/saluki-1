/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.zuul.netty.connectionpool;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.consul.discovery.ConsulServer;

import com.quancheng.saluki.client.config.IClientConfig;
import com.quancheng.saluki.loadbalancer.Server;
import com.quancheng.saluki.loadbalancer.ServerStats;
import com.quancheng.saluki.spectator.api.Counter;
import com.netflix.zuul.exception.OutboundErrorType;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.passport.PassportState;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Promise;

/**
 * User: michaels@netflix.com Date: 7/8/16 Time: 1:09 PM
 */
public class PerServerConnectionPool implements IConnectionPool {
  private ConcurrentHashMap<EventLoop, Deque<PooledConnection>> connectionsPerEventLoop =
      new ConcurrentHashMap<>();

  private final Server server;
  private final ServerStats stats;
  private final NettyClientConnectionFactory connectionFactory;
  private final PooledConnectionFactory pooledConnectionFactory;
  private final ConnectionPoolConfig config;
  private final IClientConfig niwsClientConfig;


  private final Counter createNewConnCounter;
  private final Counter createConnSucceededCounter;
  private final Counter createConnFailedCounter;

  private final Counter requestConnCounter;
  private final Counter reuseConnCounter;
  private final Counter connTakenFromPoolIsNotOpen;
  private final Counter maxConnsPerHostExceededCounter;
  private final AtomicInteger connsInPool;
  private final AtomicInteger connsInUse;

  /**
   * This is the count of connections currently in progress of being established. They will only be
   * added to connsInUse _after_ establishment has completed.
   */
  private final AtomicInteger connCreationsInProgress;

  private static final Logger LOG = LoggerFactory.getLogger(PerServerConnectionPool.class);


  public PerServerConnectionPool(Server server, ServerStats stats,
      NettyClientConnectionFactory connectionFactory,
      PooledConnectionFactory pooledConnectionFactory, ConnectionPoolConfig config,
      IClientConfig niwsClientConfig, Counter createNewConnCounter,
      Counter createConnSucceededCounter, Counter createConnFailedCounter,
      Counter requestConnCounter, Counter reuseConnCounter, Counter connTakenFromPoolIsNotOpen,
      Counter maxConnsPerHostExceededCounter, AtomicInteger connsInPool, AtomicInteger connsInUse) {
    this.server = server;
    this.stats = stats;
    this.connectionFactory = connectionFactory;
    this.pooledConnectionFactory = pooledConnectionFactory;
    this.config = config;
    this.niwsClientConfig = niwsClientConfig;
    this.createNewConnCounter = createNewConnCounter;
    this.createConnSucceededCounter = createConnSucceededCounter;
    this.createConnFailedCounter = createConnFailedCounter;
    this.requestConnCounter = requestConnCounter;
    this.reuseConnCounter = reuseConnCounter;
    this.connTakenFromPoolIsNotOpen = connTakenFromPoolIsNotOpen;
    this.maxConnsPerHostExceededCounter = maxConnsPerHostExceededCounter;
    this.connsInPool = connsInPool;
    this.connsInUse = connsInUse;

    this.connCreationsInProgress = new AtomicInteger(0);
  }

  @Override
  public ConnectionPoolConfig getConfig() {
    return this.config;
  }

  public IClientConfig getNiwsClientConfig() {
    return niwsClientConfig;
  }

  public Server getServer() {
    return server;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  private void resetIdleStateHandler(final Channel ch, ConnectionPoolConfig connectionPoolConfig) {
    ch.pipeline().remove("idleStateHandler");
    ch.pipeline().addBefore("originNettyLogger", "idleStateHandler",
        new IdleStateHandler(0, 0, connectionPoolConfig.getIdleTimeout(), TimeUnit.MILLISECONDS));
  }

  /** function to run when a connection is acquired before returning it to caller. */
  private void onAcquire(final PooledConnection conn, String httpMethod, String uriStr,
      int attemptNum, CurrentPassport passport) {
    passport.setOnChannel(conn.getChannel());
    resetIdleStateHandler(conn.getChannel(), conn.getConfig());

    conn.setInUse();
    if (LOG.isDebugEnabled())
      LOG.debug("PooledConnection acquired: " + conn.toString());
  }

  @Override
  public Promise<PooledConnection> acquire(EventLoop eventLoop, Object key, String httpMethod,
      String uri, int attemptNum, CurrentPassport passport) {
    requestConnCounter.increment();

    Promise<PooledConnection> promise = eventLoop.newPromise();

    // Try getting a connection from the pool.
    final PooledConnection conn = tryGettingFromConnectionPool(eventLoop);
    if (conn != null) {
      // There was a pooled connection available, so use this one.
      conn.startRequestTimer();
      stats.incrementActiveRequestsCount();
      conn.incrementUsageCount();
      conn.getChannel().read();
      onAcquire(conn, httpMethod, uri, attemptNum, passport);
      promise.setSuccess(conn);
    } else {
      // connection pool empty, create new connection using client connection factory.
      tryMakingNewConnection(eventLoop, promise, httpMethod, uri, attemptNum, passport);
    }

    return promise;
  }

  public PooledConnection tryGettingFromConnectionPool(EventLoop eventLoop) {
    PooledConnection conn;
    Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);
    while ((conn = connections.poll()) != null) {

      conn.setInPool(false);

      /* Check that the connection is still open. */
      if ((conn.isActive() && conn.getChannel().isOpen())) {
        reuseConnCounter.increment();
        connsInUse.incrementAndGet();
        connsInPool.decrementAndGet();
        return conn;
      } else {
        connTakenFromPoolIsNotOpen.increment();
        connsInPool.decrementAndGet();
        conn.close();
      }
    }
    return null;
  }

  protected Deque<PooledConnection> getPoolForEventLoop(EventLoop eventLoop) {
    // We don't want to block under any circumstances, so can't use CHM.computeIfAbsent().
    // Instead we accept the slight inefficiency of an unnecessary instantiation of a
    // ConcurrentLinkedDeque.

    Deque<PooledConnection> pool = connectionsPerEventLoop.get(eventLoop);
    if (pool == null) {
      pool = new ConcurrentLinkedDeque<>();
      connectionsPerEventLoop.putIfAbsent(eventLoop, pool);
    }
    return pool;
  }

  private void tryMakingNewConnection(final EventLoop eventLoop,
      final Promise<PooledConnection> promise, final String httpMethod, final String uri,
      final int attemptNum, final CurrentPassport passport) {
    // Enforce MaxConnectionsPerHost config.
    int maxConnectionsPerHost = config.maxConnectionsPerHost();
    int openAndOpeningConnectionCount =
        stats.getOpenConnectionsCount() + connCreationsInProgress.get();
    if (maxConnectionsPerHost != -1 && openAndOpeningConnectionCount >= maxConnectionsPerHost) {
      maxConnsPerHostExceededCounter.increment();
      promise.setFailure(new OriginConnectException("maxConnectionsPerHost=" + maxConnectionsPerHost
          + ", connectionsPerHost=" + openAndOpeningConnectionCount,
          OutboundErrorType.ORIGIN_SERVER_MAX_CONNS));
      LOG.warn("Unable to create new connection because at MaxConnectionsPerHost! "
          + "maxConnectionsPerHost=" + maxConnectionsPerHost + ", connectionsPerHost="
          + openAndOpeningConnectionCount + ", host=" + server.getHost() + "origin="
          + config.getOriginName());
      return;
    }

    try {
      createNewConnCounter.increment();
      connCreationsInProgress.incrementAndGet();
      passport.add(PassportState.ORIGIN_CH_CONNECTING);

      // Choose to use either IP or hostname.
      String host = getHostFromServer(server);
      int port = getPortFromServer(server);
      final ChannelFuture cf = connectionFactory.connect(eventLoop, host, port, passport);

      if (cf.isDone()) {
        handleConnectCompletion(cf, promise, httpMethod, uri, attemptNum, passport);
      } else {
        cf.addListener(future -> {
          try {
            handleConnectCompletion((ChannelFuture) future, promise, httpMethod, uri, attemptNum,
                passport);
          } catch (Throwable e) {
            promise.setFailure(e);
            LOG.warn("Error creating new connection! " + "origin=" + config.getOriginName()
                + ", host=" + server.getHost());
          }
        });
      }
    } catch (Throwable e) {
      promise.setFailure(e);
    }
  }

  private String getHostFromServer(Server server) {
    if (server instanceof ConsulServer) {
      ConsulServer discoveryServer = (ConsulServer) server;
      return discoveryServer.getHost();
    } else {
      return server.getHost();
    }
  }

  private int getPortFromServer(Server server) {
    if (server instanceof ConsulServer) {
      ConsulServer discoveryServer = (ConsulServer) server;
      return discoveryServer.getPort();
    } else {
      return server.getPort();
    }
  }

  private void handleConnectCompletion(final ChannelFuture cf,
      final Promise<PooledConnection> callerPromise, final String httpMethod, final String uri,
      final int attemptNum, final CurrentPassport passport) {
    connCreationsInProgress.decrementAndGet();

    if (cf.isSuccess()) {

      passport.add(PassportState.ORIGIN_CH_CONNECTED);

      stats.incrementOpenConnectionsCount();
      stats.incrementActiveRequestsCount();
      createConnSucceededCounter.increment();
      connsInUse.incrementAndGet();

      final PooledConnection conn = pooledConnectionFactory.create(cf.channel());

      conn.incrementUsageCount();
      conn.startRequestTimer();
      conn.getChannel().read();
      onAcquire(conn, httpMethod, uri, attemptNum, passport);
      callerPromise.setSuccess(conn);
    } else {
      stats.incrementSuccessiveConnectionFailureCount();
      stats.addToFailureCount();
      createConnFailedCounter.increment();
      callerPromise.setFailure(
          new OriginConnectException(cf.cause().getMessage(), OutboundErrorType.CONNECT_ERROR));
    }
  }

  @Override
  public boolean release(PooledConnection conn) {
    if (conn == null) {
      return false;
    }
    if (conn.isInPool()) {
      return false;
    }

    // Get the eventloop for this channel.
    EventLoop eventLoop = conn.getChannel().eventLoop();
    Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);

    CurrentPassport passport = CurrentPassport.fromChannel(conn.getChannel());

    // Discard conn if already at least above waterline in the pool already for this server.
    int poolWaterline = config.perServerWaterline();
    if (poolWaterline > -1 && connections.size() >= poolWaterline) {
      conn.close();
      conn.setInPool(false);
      return false;
    }
    // Attempt to return connection to the pool.
    else if (connections.offer(conn)) {
      conn.setInPool(true);
      connsInPool.incrementAndGet();
      passport.add(PassportState.ORIGIN_CH_POOL_RETURNED);
      return true;
    } else {
      // If the pool is full, then close the conn and discard.
      conn.close();
      conn.setInPool(false);
      return false;
    }
  }

  @Override
  public boolean remove(PooledConnection conn) {
    if (conn == null) {
      return false;
    }
    if (!conn.isInPool()) {
      return false;
    }

    // Get the eventloop for this channel.
    EventLoop eventLoop = conn.getChannel().eventLoop();

    // Attempt to return connection to the pool.
    Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);
    if (connections.remove(conn)) {
      conn.setInPool(false);
      connsInPool.decrementAndGet();
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void shutdown() {
    for (Deque<PooledConnection> connections : connectionsPerEventLoop.values()) {
      for (PooledConnection conn : connections) {
        conn.close();
      }
    }
  }

  @Override
  public int getConnsInPool() {
    return connsInPool.get();
  }

  @Override
  public int getConnsInUse() {
    return connsInUse.get();
  }

}
