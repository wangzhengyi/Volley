package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.support.v4.util.LruCache;

/** Lru算法的L1缓存实现类. */
@SuppressWarnings("unused")
public class ImageLruCache implements ImageLoader.ImageCache {
    private LruCache<String, Bitmap> mLruCache;

    public ImageLruCache() {
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheSize = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };
    }

    @Override
    public Bitmap getBitmap(String url) {
        return mLruCache.get(url);
    }

    @Override
    public void putBitmap(String url, Bitmap bitmap) {
        mLruCache.put(url, bitmap);
    }
}
