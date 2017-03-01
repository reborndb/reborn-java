/**
 * @(#)RoundRobinJedisPool.java, 2014-11-30.
 * 
 * Copyright (c) 2015 Reborndb Org.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.reborndb.reborn;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisException;

/**
 * A round robin connection pool for connecting multiple reborn proxies based on
 * Jedis and Curator.
 * 
 * @author Apache9
 * @see https://github.com/xetorthio/jedis
 * @see http://curator.apache.org/
 */
public class RoundRobinJedisPool implements JedisResourcePool {

    private static final Logger LOG = Logger.getLogger(RoundRobinJedisPool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String JSON_NAME_REBORN_PROXY_ADDR = "addr";

    private static final String JSON_NAME_REBORN_PROXY_STATE = "state";

    private static final String REBORN_PROXY_STATE_ONLINE = "online";

    private static final int CURATOR_RETRY_BASE_SLEEP_MS = 100;

    private static final int CURATOR_RETRY_MAX_SLEEP_MS = 30 * 1000;

    private static final int JEDIS_POOL_TIMEOUT_UNSET = -1;

    private static final ImmutableSet<PathChildrenCacheEvent.Type> RESET_TYPES = Sets
            .immutableEnumSet(PathChildrenCacheEvent.Type.CHILD_ADDED,
                    PathChildrenCacheEvent.Type.CHILD_UPDATED,
                    PathChildrenCacheEvent.Type.CHILD_REMOVED);

    private final CuratorFramework curatorClient;

    private final boolean closeCurator;

    private final PathChildrenCache watcher;

    private static final class PooledObject {
        public final String addr;

        public final JedisPool pool;

        public PooledObject(String addr, JedisPool pool) {
            this.addr = addr;
            this.pool = pool;
        }

		@Override
		public String toString() {
			return "PooledObject [addr=" + addr + ", pool=" + pool + "]";
		}

    }

    private volatile ImmutableList<PooledObject> pools = ImmutableList.of();
    
    private final AtomicInteger nextIdx = new AtomicInteger(-1);

    private final JedisPoolConfig poolConfig;

    private final int timeout;
    
    /**  
     *   Codis or Reborn password
     */
    private final String password;
    
    /**  
     *   the value of pools when zk state change
     */
    private ImmutableList<PooledObject> changePools = pools;
    
    /**  
     *   current zk connection state 
     *   <p>
     *   if state == {@code ConnectionState.CONNECTED} || state == {@code ConnectionState.RECONNECTED , pools = changePools;
     */
    private volatile ConnectionState currentConnectionState;

    /**
     * Create a RoundRobinJedisPool with default timeout.
     * <p>
     * We create a CuratorFramework with infinite retry number. If you do not
     * like the behavior, use the other constructor that allow you pass a
     * CuratorFramework created by yourself.
     * 
     * @param zkAddr
     *            ZooKeeper connect string. e.g., "zk1:2181"
     * @param zkSessionTimeoutMs
     *            ZooKeeper session timeout in ms
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.,
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @see #RoundRobinJedisPool(String, int, String, JedisPoolConfig, String)
     */
    public RoundRobinJedisPool(String zkAddr, int zkSessionTimeoutMs, String zkPath,
            JedisPoolConfig poolConfig) {
        this(zkAddr, zkSessionTimeoutMs, zkPath, poolConfig, null);
    }
    
    /**
     * Create a RoundRobinJedisPool with default timeout.
     * <p>
     * We create a CuratorFramework with infinite retry number. If you do not
     * like the behavior, use the other constructor that allow you pass a
     * CuratorFramework created by yourself.
     * 
     * @param zkAddr
     *            ZooKeeper connect string. e.g., "zk1:2181"
     * @param zkSessionTimeoutMs
     *            ZooKeeper session timeout in ms
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.,
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @param password      
     * 			  codis or reborn password    
     * @see #RoundRobinJedisPool(String, int, String, JedisPoolConfig, int , String)
     */
    public RoundRobinJedisPool(String zkAddr, int zkSessionTimeoutMs, String zkPath,
            JedisPoolConfig poolConfig , String password) {
        this(zkAddr, zkSessionTimeoutMs, zkPath, poolConfig, JEDIS_POOL_TIMEOUT_UNSET , password);
    }

    /**
     * Create a RoundRobinJedisPool.
     * <p>
     * We create a CuratorFramework with infinite retry number. If you do not
     * like the behavior, use the other constructor that allow you pass a
     * CuratorFramework created by yourself.
     * 
     * @param zkAddr
     *            ZooKeeper connect string. e.g., "zk1:2181"
     * @param zkSessionTimeoutMs
     *            ZooKeeper session timeout in ms
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.,
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @param timeout
     *            timeout of JedisPool
     * @see #RoundRobinJedisPool(CuratorFramework, boolean, String,
     *      JedisPoolConfig, int)
     */
    public RoundRobinJedisPool(String zkAddr, int zkSessionTimeoutMs, String zkPath,
            JedisPoolConfig poolConfig, int timeout) {
       this(zkAddr, zkSessionTimeoutMs, zkPath, poolConfig , timeout , null);
    }
    
    /**
     * Create a RoundRobinJedisPool.
     * <p>
     * We create a CuratorFramework with infinite retry number. If you do not
     * like the behavior, use the other constructor that allow you pass a
     * CuratorFramework created by yourself.
     * 
     * @param zkAddr
     *            ZooKeeper connect string. e.g., "zk1:2181"
     * @param zkSessionTimeoutMs
     *            ZooKeeper session timeout in ms
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.,
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @param timeout
     *            timeout of JedisPool
     * @param password
     *            codis or reborn password
     * @see #RoundRobinJedisPool(CuratorFramework, boolean, String,
     *      JedisPoolConfig, int , String)
     */
    public RoundRobinJedisPool(String zkAddr, int zkSessionTimeoutMs, String zkPath,
            JedisPoolConfig poolConfig, int timeout , String password) {
        this(CuratorFrameworkFactory
                .builder()
                .connectString(zkAddr)
                .sessionTimeoutMs(zkSessionTimeoutMs)
                .retryPolicy(
                        new BoundedExponentialBackoffRetryUntilElapsed(CURATOR_RETRY_BASE_SLEEP_MS,
                                CURATOR_RETRY_MAX_SLEEP_MS, -1L)).build(), true, zkPath,
                poolConfig, timeout , password);
    }

    /**
     * Create a RoundRobinJedisPool with default timeout.
     * 
     * @param curatorClient
     *            We will start it if it has not started yet.
     * @param closeCurator
     *            Whether to close the curatorClient passed in when close.
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     */
    public RoundRobinJedisPool(CuratorFramework curatorClient, boolean closeCurator, String zkPath,
            JedisPoolConfig poolConfig) {
        this(curatorClient, closeCurator, zkPath, poolConfig , null);
    }
    
    /**
     * Create a RoundRobinJedisPool with default timeout.
     * 
     * @param curatorClient
     *            We will start it if it has not started yet.
     * @param closeCurator
     *            Whether to close the curatorClient passed in when close.
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @param password
     *            codis or reborn password
     */
    public RoundRobinJedisPool(CuratorFramework curatorClient, boolean closeCurator, String zkPath,
            JedisPoolConfig poolConfig , String password) {
        this(curatorClient, closeCurator, zkPath, poolConfig, JEDIS_POOL_TIMEOUT_UNSET , password);
    }

    /**
     * Create a RoundRobinJedisPool.
     * 
     * @param curatorClient
     *            We will start it if it has not started yet.
     * @param closeCurator
     *            Whether to close the curatorClient passed in when close.
     * @param zkPath
     *            the reborn proxy dir on ZooKeeper. e.g.
     *            "/zk/reborn/db_xxx/proxy"
     * @param poolConfig
     *            same as JedisPool
     * @param timeout
     *            timeout of JedisPool
     */
    public RoundRobinJedisPool(CuratorFramework curatorClient, boolean closeCurator, String zkPath,
            JedisPoolConfig poolConfig, int timeout , String password) {
        this.poolConfig = poolConfig;
        this.timeout = timeout;
        this.curatorClient = curatorClient;
        this.closeCurator = closeCurator;
        this.password = password;
        curatorClient.getConnectionStateListenable().addListener(new ConnectionStateListener() {
			
			@Override
			public void stateChanged(CuratorFramework client, ConnectionState newState) {
				currentConnectionState = newState;
			}
		});
        watcher = new PathChildrenCache(curatorClient, zkPath, true);
        watcher.getListenable().addListener(new PathChildrenCacheListener() {

            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception {
                StringBuilder sb = new StringBuilder("zookeeper event received: type=")
                        .append(event.getType());
                if (event.getData() != null) {
                    ChildData data = event.getData();
                    sb.append(", path=").append(data.getPath()).append(", stat=")
                            .append(data.getStat());
                }
                LOG.info(sb.toString());
                if (RESET_TYPES.contains(event.getType())) {
                    resetPools();
                }
            }
        });
        // we need to get the initial data so client must be started
        if (curatorClient.getState() == CuratorFrameworkState.LATENT) {
            curatorClient.start();
        }
        try {
            watcher.start(StartMode.BUILD_INITIAL_CACHE);
        } catch (Exception e) {
            throw new JedisException(e);
        }
        resetPools();
    }

    private void resetPools() {
        ImmutableList<PooledObject> pools = this.changePools;
        Map<String, PooledObject> addr2Pool = Maps.newHashMapWithExpectedSize(pools.size());
        for (PooledObject pool: pools) {
            addr2Pool.put(pool.addr, pool);
        }
        ImmutableList.Builder<PooledObject> builder = ImmutableList.builder();
        for (ChildData childData: watcher.getCurrentData()) {
            try {
                JsonNode proxyInfo = MAPPER.readTree(childData.getData());
                if (!REBORN_PROXY_STATE_ONLINE.equals(proxyInfo.get(JSON_NAME_REBORN_PROXY_STATE)
                        .asText())) {
                    continue;
                }
                String addr = proxyInfo.get(JSON_NAME_REBORN_PROXY_ADDR).asText();
                PooledObject pool = addr2Pool.remove(addr);
                if (pool == null) {
                    LOG.info("Add new proxy: " + addr);
                    String[] hostAndPort = addr.split(":");
                    String host = hostAndPort[0];
                    int port = Integer.parseInt(hostAndPort[1]);
                    if (timeout == JEDIS_POOL_TIMEOUT_UNSET) {
                        pool = new PooledObject(addr, new JedisPool(poolConfig, host, port , Protocol.DEFAULT_TIMEOUT , password));
                    } else {
                        pool = new PooledObject(addr,
                                new JedisPool(poolConfig, host, port, timeout , password));
                    }
                }
                builder.add(pool);
            } catch (Exception e) {
                LOG.warn("parse " + childData.getPath() + " failed", e);
            }
        }
        this.changePools = builder.build();
        changeForCurrentPool();
        for (PooledObject pool: addr2Pool.values()) {
            LOG.info("Remove proxy: " + pool.addr);
            pool.pool.close();
        }
    }

    private void changeForCurrentPool() {
    	// if zk connection is CONNECTED or RECONNECTED , compare pools and copyPools
    	if(currentConnectionState == ConnectionState.CONNECTED || currentConnectionState == ConnectionState.RECONNECTED) {
    		pools = changePools;
    	}
    	LOG.info("all pools: " + this.pools);
	}

	@Override
    public Jedis getResource() {
        ImmutableList<PooledObject> pools = this.pools;
        if (pools.isEmpty()) {
            throw new JedisException("Proxy list empty");
        }
        for (;;) {
            int current = nextIdx.get();
            int next = current >= pools.size() - 1 ? 0 : current + 1;
            if (nextIdx.compareAndSet(current, next)) {
                return pools.get(next).pool.getResource();
            }
        }
    }
    
    @Override
    public void close() {
        try {
            Closeables.close(watcher, true);
        } catch (IOException e) {
            LOG.fatal("IOException should not have been thrown", e);
        }
        if (closeCurator) {
            curatorClient.close();
        }
        List<PooledObject> pools = this.pools;
        this.pools = ImmutableList.of();
        for (PooledObject pool: pools) {
            pool.pool.close();
        }
    }
}
