package com.coy.l2cache.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * 对 valueLoader 进行包装，以便目标方法执行完后，先put到redis，再发送缓存同步消息，此方式不会对level2Cache造成污染
 *
 * @author chenck
 * @date 2020/9/23 11:14
 */
public class ValueLoaderWarpper implements Callable {

    private static final Logger logger = LoggerFactory.getLogger(ValueLoaderWarpper.class);

    private final String cacheName;
    private final Object key;
    /**
     * 记录是否被调用
     */
    private boolean call;

    private Callable<?> valueLoader;

    public ValueLoaderWarpper(String cacheName, Object key, Callable<?> valueLoader) {
        this.cacheName = cacheName;
        this.key = key;
        this.valueLoader = valueLoader;
    }

    @Override
    public Object call() throws Exception {
        if (null == valueLoader) {
            logger.warn("[LoadFunction] valueLoader is null, return null, cacheName={}, key={}", cacheName, key);
            return null;
        }
        call = true;
        Object tempValue = valueLoader.call();
        logger.debug("[LoadFunction] valueLoader.call, cacheName={}, key={}, value={}", cacheName, key, tempValue);
        return tempValue;
    }

    /**
     * 是否被调用
     */
    public boolean isCall() {
        return this.call;
    }
}
