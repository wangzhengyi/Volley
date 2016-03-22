package com.android.volley;

import android.os.*;
import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 */
public class CacheDispatcher extends Thread{
    /**
     * The queue of requests coming in for triage.
     */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /**
     * The queue of requests going out to the network.
     */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /**
     * The cache to read from.
     */
    private final Cache mCache;

    /**
     * For posting responses.
     */
    private final ResponseDelivery mDelivery;

    /**
     * Used for telling us to die.
     */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        mCache.initialize();

        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                final Request<?> request = mCacheQueue.take();
                request.addMarker("cache-queue-take");

                // If the request has been canceled, don't bother dispatching it.
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // Attempt to retrieve this item from cache.
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    request.addMarker("cache-miss");
                    mNetworkQueue.put(request);
                    continue;
                }

                // If it is completely expired, just send it to the network
                if (entry.isExpired()) {
                    request.addMarker("cache-hit-expired");
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                request.addMarker("cache-hit");
                Response<?> response = request.parseNetworkResponse(new NetworkResponse(entry.data,
                        entry.responseHeaders));
                request.addMarker("cache-hit-parsed");

                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to alse send the request to the network for
                    // refreshing.
                    request.addMarker("cache-hit-refresh-needed");
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
                continue;
            }
        }
    }
}
