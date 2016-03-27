package com.android.volley;

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/** 线程,用来调度可以走缓存的Request请求. */
public class CacheDispatcher extends Thread{
    /** 可以走Disk缓存的request请求队列. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** 需要走网络的request请求队列. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** DiskBasedCache缓存实现类. */
    private final Cache mCache;

    /** 网络请求结果传递类. */
    private final ResponseDelivery mDelivery;

    /** 用来停止线程的标志位. */
    private volatile boolean mQuit = false;

    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /** 通过标记位机制强行停止CacheDispatcher线程. */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // 初始化DiskBasedCache缓存类.
        mCache.initialize();

        while (true) {
            try {
                // 从缓存队列中获取request请求.(缓存队列实现了生产者-消费者队列模型)
                final Request<?> request = mCacheQueue.take();

                // 判断请求是否被取消
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // 从缓存系统中获取request请求结果Cache.Entry.
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    // 如果缓存系统中没有该缓存请求,则将request加入到网络请求队列中.
                    // 由于NetworkQueue跟NetworkDispatcher线程关联,并且也是生产者-消费者队列,
                    // 所以这里添加request请求就相当于将request执行网络请求.
                    mNetworkQueue.put(request);
                    continue;
                }

                // 判断缓存结果是否过期.
                if (entry.isExpired()) {
                    request.setCacheEntry(entry);
                    // 过期的缓存需要重新执行request请求.
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                Response<?> response = request.parseNetworkResponse(new NetworkResponse(entry.data,
                        entry.responseHeaders));

                // 判断Request请求结果是否新鲜?
                if (!entry.refreshNeeded()) {
                    // 请求结果新鲜,则直接将请求结果分发,进行异步回调用户接口.
                    mDelivery.postResponse(request, response);
                } else {
                    // 请求结果不新鲜,但是同样还是将缓存结果返回给用户,并且同时执行网络请求,刷新Request网络结果缓存.
                    request.setCacheEntry(entry);

                    response.intermediate = true;

                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                if (mQuit) {
                    return;
                }
            }
        }
    }
}
