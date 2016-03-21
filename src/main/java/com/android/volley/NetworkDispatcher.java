package com.android.volley;

import android.os.*;
import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 */
public class NetworkDispatcher extends Thread{
    /**
     * The queue of requests to service.
     */
    private final BlockingQueue<Request<?>> mQueue;

    /**
     * The network interface for processing requests.
     */
    private final Network mNetwork;

    /**
     * The cache to write to.
     */
    private final Cache mCache;

    /**
     * For posting responses and errors.
     */
    private final ResponseDelivery mDelivery;

    /**
     * Used for telling us to die.
     */
    private volatile  boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.
     * @param queue Queue of incoming requests for triage
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
                             Network network, Cache cache, ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            long startTimeMs = SystemClock.elapsedRealtime();
            Request<?> request;
            try {
                // Take a request from the queue.
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                request.addMarker("network-queue-take");

                if (request.isCanceled()) {
                    request.finish("network-discard-cancelled");
                    continue;
                }

                addTrafficStatsTag(request);

                // Perform the network request.
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");

                
            } catch (VolleyError volleyError) {
                volleyError.printStackTrace();
            }
        }
    }
}
