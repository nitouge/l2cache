package com.coy.l2cache.cache.spring;

import com.coy.l2cache.cache.Cache;
import com.coy.l2cache.cache.config.CacheConfig;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.concurrent.Callable;

/**
 * L2Cache Spring Cache
 *
 * @author chenck
 * @date 2020/7/6 11:24
 */
public class L2CacheSpringCache extends AbstractValueAdaptingCache {

    /**
     * 缓存实例id
     */
    private final String instanceId;
    /**
     * 缓存名字
     */
    private final String cacheName;
    /**
     * l2cache
     */
    private final Cache l2cache;

    /**
     * Create an {@code AbstractValueAdaptingCache} with the given setting.
     *
     * @param cacheName the name of the cache
     * @param l2cache   l2cache
     */
    protected L2CacheSpringCache(String cacheName, CacheConfig cacheConfig, Cache l2cache) {
        super(cacheConfig.isAllowNullValues());
        Assert.notNull(cacheConfig, "cacheConfig must not be null");
        Assert.notNull(cacheConfig.getInstanceId(), "cache instance id must not be null");
        Assert.notNull(cacheName, "cacheName must not be null");
        Assert.notNull(l2cache, "l2cache must not be null");
        this.instanceId = cacheConfig.getInstanceId();
        this.cacheName = cacheName;
        this.l2cache = l2cache;
    }

    @Override
    public String getName() {
        return this.cacheName;
    }

    @Override
    public Cache getNativeCache() {
        return this.l2cache;
    }

    @Override
    protected Object lookup(Object key) {
        return this.l2cache.get(key);
    }

    /**
     * @Cacheable(sync=false) 进入此方法
     * 并发场景：未做同步控制，所以存在多个线程同时加载数据的情况，即可能存在缓存击穿的情况
     */
    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        return toValueWrapper(this.l2cache.get(key));
    }

    /**
     * @Cacheable(sync=true) 进入此方法
     * 并发场景：仅一个线程加载数据，其他线程均阻塞
     * 注：借助Callable入参，可以实现不同缓存调用不同的加载数据逻辑的目的。
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return this.l2cache.get(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        this.l2cache.put(key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return toValueWrapper(this.l2cache.putIfAbsent(key, value));
    }

    @Override
    public void evict(Object key) {
        this.l2cache.evict(key);
    }

    @Override
    public void clear() {
        this.l2cache.clear();
    }
}
