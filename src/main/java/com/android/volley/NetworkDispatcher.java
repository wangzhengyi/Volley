package com.android.volley;

import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.BlockingQueue;

/** 调度网络请求线程. */
public class NetworkDispatcher extends Thread{
    /** 网络请求队列. */
    private final BlockingQueue<Request<?>> mQueue;

    /** 封装了HurlStack的网络类，其performRequest方法是单个request请求真正执行的地方. */
    private final Network mNetwork;

    /** 缓存类，存储请求结果的缓存。 */
    private final Cache mCache;

    /** 请求结果传递类。 */
    private final ResponseDelivery mDelivery;

    /** 暂停线程的标志位，替换Thread自身的stop方法. */
    private volatile boolean mQuit = false;

    /** 构造网络请求调度线程类。 */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
                             Network network, Cache cache, ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /** 强制停止当前调度线程。 */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            long startTimeMs = SystemClock.elapsedRealtime();
            Request<?> request;
            try {
                // 使用BlockingQueue实现了生产者-消费者模型.
                // 消费者是该调度线程。
                // 生产者是request网络请求.
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                if (request.isCanceled()) {
                    continue;
                }

                addTrafficStatsTag(request);

                // 真正执行网络请求的地方.
                NetworkResponse networkResponse = mNetwork.performRequest(request);

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // 在当前线程中解析网络结果.
                // 不同的Request实现的parseNetworkResponse是不同的(例如StringRequest和JsonRequest).
                Response<?> response = request.parseNetworkResponse(networkResponse);

                //
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                }

                // 将网络请求结果进行传递.
                // ResponseDelivery调用顺序如下:
                // ResponseDelivery.postResponse==>ResponseDeliveryRunnable[Runnable]->run
                // ==>Request->deliverResponse==>用户设置的Listener回调接口
                request.markDelivered();
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
                volleyError.printStackTrace();
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyError volleyError = new VolleyError(e);
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                mDelivery.postError(request, volleyError);
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }

    private void addTrafficStatsTag(Request<?> request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }
}
