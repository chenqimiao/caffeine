package com.github.benmanes.caffeine.my;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * @author Qimiao Chen
 * @date 2021-08-28 17:59
 **/
public class MyCacheTest {

    private static final LoadingCache<String, String> CACHE = Caffeine.newBuilder()
            // 最大容量
            .maximumSize(1000L)
            // 读取后，缓存失效时间，单位秒
            .expireAfterAccess(1000L, TimeUnit.SECONDS)
            // 最后一次写入开始计时
            .refreshAfterWrite(1000L, TimeUnit.SECONDS)
            .build(new CacheLoader<String, String>() {
                @Nullable
                @Override
                public String load(String key) throws Exception {
                    return key;
                }
            });

    public static void main(String[] args) {
        CACHE.get("A");
        CACHE.get("A");
    }

}
