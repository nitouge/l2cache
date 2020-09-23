package com.coy.l2cache.cache;

import com.coy.l2cache.CacheConfig;
import com.coy.l2cache.CacheSpec;
import com.coy.l2cache.CacheSyncPolicy;
import com.coy.l2cache.consts.CacheConsts;
import com.coy.l2cache.consts.CacheType;
import com.coy.l2cache.content.CacheSupport;
import com.coy.l2cache.content.NullValue;
import com.coy.l2cache.load.CacheLoader;
import com.coy.l2cache.load.LoadFunction;
import com.coy.l2cache.schedule.RefreshExpiredCacheTask;
import com.coy.l2cache.schedule.RefreshSupport;
import com.coy.l2cache.sync.CacheMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine Cache
 *
 * @author chenck
 * @date 2020/6/29 16:37
 */
public class CaffeineCache extends AbstractAdaptingCache implements Level1Cache {

    private static final Logger logger = LoggerFactory.getLogger(CaffeineCache.class);

    /**
     * caffeine config
     */
    private final CacheConfig.Caffeine caffeine;
    /**
     * 缓存加载器，用于异步加载缓存
     */
    private final CacheLoader cacheLoader;
    /**
     * 缓存同步策略
     */
    private final CacheSyncPolicy cacheSyncPolicy;
    /**
     * L1 Caffeine Cache
     */
    private final Cache<Object, Object> caffeineCache;
    /**
     * 存放NullValue的key，用于控制NullValue对象的有效时间
     */
    private Cache<Object, Integer> nullValueCache;

    public CaffeineCache(String cacheName, CacheConfig cacheConfig, CacheLoader cacheLoader, CacheSyncPolicy cacheSyncPolicy,
                         Cache<Object, Object> caffeineCache) {
        super(cacheName, cacheConfig);
        this.caffeine = cacheConfig.getCaffeine();
        this.cacheLoader = cacheLoader;
        this.cacheSyncPolicy = cacheSyncPolicy;
        this.caffeineCache = caffeineCache;

        if (this.caffeine.isAutoRefreshExpireCache()) {
            // 定期刷新过期的缓存
            RefreshSupport.getInstance(this.caffeine.getRefreshPoolSize())
                    .scheduleWithFixedDelay(new RefreshExpiredCacheTask(this), 5,
                            this.caffeine.getRefreshPeriod(), TimeUnit.SECONDS);
        }
        if (this.isAllowNullValues()) {
            this.nullValueCache = Caffeine.newBuilder()
                    .expireAfterWrite(cacheConfig.getNullValueExpireTimeSeconds(), TimeUnit.SECONDS)
                    .maximumSize(cacheConfig.getNullValueMaxSize())
                    .build();
            cacheLoader.setNullValueCache(this.nullValueCache);
            logger.info("[CaffeineCache] NullValueCache init success, cacheName={}, expireTime={} s, maxSize={}", this.getCacheName(), cacheConfig.getNullValueExpireTimeSeconds(), cacheConfig.getNullValueMaxSize());
        }
    }

    @Override
    public String getCacheType() {
        return CacheType.CAFFEINE.name().toLowerCase();
    }

    @Override
    public Cache<Object, Object> getActualCache() {
        return this.caffeineCache;
    }

    @Override
    public CacheSyncPolicy getCacheSyncPolicy() {
        return this.cacheSyncPolicy;
    }

    @Override
    public CacheLoader getCacheLoader() {
        return this.cacheLoader;
    }

    @Override
    public boolean isLoadingCache() {
        return this.caffeineCache instanceof LoadingCache && null != this.cacheLoader;
    }

    @Override
    public Object get(Object key) {
        if (isLoadingCache()) {
            // 如果是refreshAfterWrite策略，则只会阻塞加载数据的线程，其他线程返回旧值（如果是异步加载，则所有线程都返回旧值）
            Object value = ((LoadingCache) this.caffeineCache).get(key);
            logger.debug("[CaffeineCache] LoadingCache.get cache, cacheName={}, key={}, value={}", this.getCacheName(), key, value);
            return fromStoreValue(value);
        }
        return fromStoreValue(this.caffeineCache.getIfPresent(key));
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (isLoadingCache()) {
            // 将Callable设置到自定义CacheLoader中，以便在load()中执行具体的业务方法来加载数据
            this.cacheLoader.addValueLoader(key, valueLoader);

            Object value = ((LoadingCache) this.caffeineCache).get(key);
            logger.debug("[CaffeineCache] LoadingCache.get(key, callable) cache, cacheName={}, key={}, value={}", this.getCacheName(), key, value);
            return (T) fromStoreValue(value);
        }

        // 同步加载数据，仅一个线程加载数据，其他线程均阻塞
        Object value = this.caffeineCache.get(key, new LoadFunction(this.getInstanceId(), this.getCacheType(), this.getCacheName(),
                null, this.getCacheSyncPolicy(), valueLoader, this.isAllowNullValues(), this.nullValueCache));
        logger.debug("[CaffeineCache] Cache.get(key, callable) cache, cacheName={}, key={}, value={}", this.getCacheName(), key, value);
        return (T) fromStoreValue(value);
    }

    @Override
    public void put(Object key, Object value) {
        if (!isAllowNullValues() && value == null) {
            caffeineCache.invalidate(key);
            return;
        }
        caffeineCache.put(key, toStoreValue(value));

        // 允许null值，且值为空，则记录到nullValueCache，用于淘汰NullValue
        if (this.isAllowNullValues() && (value == null || value instanceof NullValue)) {
            if (null != nullValueCache) {
                nullValueCache.put(key, 1);
            }
        }

        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createMessage(key, CacheConsts.CACHE_REFRESH));
        }
    }

    @Override
    public void evict(Object key) {
        logger.debug("[CaffeineCache] evict cache, cacheName={}, key={}", this.getCacheName(), key);
        caffeineCache.invalidate(key);
        if (null != nullValueCache) {
            nullValueCache.invalidate(key);
        }
        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createMessage(key, CacheConsts.CACHE_CLEAR));
        }
    }

    @Override
    public void clear() {
        logger.debug("[CaffeineCache] clear cache, cacheName={}", this.getCacheName());
        caffeineCache.invalidateAll();
        if (null != nullValueCache) {
            nullValueCache.invalidateAll();
        }
        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createMessage(null, CacheConsts.CACHE_CLEAR));
        }
    }

    @Override
    public boolean isExists(Object key) {
        boolean rslt = caffeineCache.asMap().containsKey(key);
        logger.debug("[CaffeineCache] key is exists, cacheName={}, key={}, rslt={}", this.getCacheName(), key, rslt);
        return rslt;
    }

    @Override
    public void clearLocalCache(Object key) {
        logger.info("[CaffeineCache] clear local cache, cacheName={}, key={}", this.getCacheName(), key);
        if (key == null) {
            caffeineCache.invalidateAll();
            if (null != nullValueCache) {
                nullValueCache.invalidateAll();
            }
        } else {
            caffeineCache.invalidate(key);
            if (null != nullValueCache) {
                nullValueCache.invalidate(key);
            }
        }
    }

    @Override
    public void refresh(Object key) {
        if (isLoadingCache()) {
            logger.debug("[CaffeineCache] refresh cache, cacheName={}, key={}", this.getCacheName(), key);
            ((LoadingCache) caffeineCache).refresh(key);
        }
    }

    @Override
    public void refreshAll() {
        if (isLoadingCache()) {
            LoadingCache loadingCache = (LoadingCache) caffeineCache;
            for (Object key : loadingCache.asMap().keySet()) {
                logger.debug("[CaffeineCache] refreshAll cache, cacheName={}, key={}", this.getCacheName(), key);
                loadingCache.refresh(key);
            }
        }
    }

    @Override
    public void refreshExpireCache(Object key) {
        if (isLoadingCache()) {
            logger.debug("[CaffeineCache] refreshExpireCache, cacheName={}, key={}", this.getCacheName(), key);
            // 通过LoadingCache.get(key)来刷新过期缓存
            ((LoadingCache) caffeineCache).get(key);
        }
    }

    @Override
    public void refreshAllExpireCache() {
        if (isLoadingCache()) {
            LoadingCache loadingCache = (LoadingCache) caffeineCache;
            Object value = null;
            for (Object key : loadingCache.asMap().keySet()) {
                logger.debug("[CaffeineCache] refreshAllExpireCache, cacheName={}, key={}", this.getCacheName(), key);
                value = loadingCache.get(key);// 通过LoadingCache.get(key)来刷新过期缓存

                if (null == value) {
                    continue;
                }
                if (value instanceof NullValue) {
                    if (null == nullValueCache) {
                        continue;
                    }
                    // getIfPresent 触发淘汰
                    Object nullValue = nullValueCache.getIfPresent(key);
                    if (null != nullValue) {
                        continue;
                    }
                    logger.info("[CaffeineCache] refreshAllExpireCache invalidate NullValue, cacheName={}, key={}", this.getCacheName(), key);
                    loadingCache.invalidate(key);
                    if (null != cacheSyncPolicy) {
                        cacheSyncPolicy.publish(createMessage(key, CacheConsts.CACHE_CLEAR));
                    }
                }
            }
            if (null != nullValueCache) {
                logger.debug("[CaffeineCache] refreshAllExpireCache number of NullValue, cacheName={}, size={}", this.getCacheName(), nullValueCache.asMap().size());
            }
        }
    }

    private CacheMessage createMessage(Object key, String optType) {
        return new CacheMessage()
                .setInstanceId(this.getInstanceId())
                .setCacheType(this.getCacheType())
                .setCacheName(this.getCacheName())
                .setKey(key)
                .setOptType(optType);
    }
}
