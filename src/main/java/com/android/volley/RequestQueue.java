package com.android.volley;

import android.os.Handler;
import android.os.Looper;
import android.widget.ListAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request dispatch queue with a thread pool of dispatchers.
 */
public class RequestQueue {


    /**
     * Callback interface for completed requests.
     */
    public static interface RequestFinishedListener<T> {
        void onRequestFinished(Request<T> request);
    }

    /**
     * Used for generating monotonically-increasing sequence numbers for requests.
     */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests = new HashMap<>();

    /**
     * The set of all requests currently being processed by this requestQueue.
     */
    private final Set<Request<?>> mCurrentRequests = new HashSet<>();

    /**
     * The cache triage queue.
     */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue = new PriorityBlockingQueue<>();

    /**
     * The queue of requests that are actually going out to the network.
     */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue = new PriorityBlockingQueue<>();

    /**
     * Number of network request dispatcher threads to start.
     */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /**
     * Cache interface for retrieving and storing responses.
     */
    private final Cache mCache;

    /**
     * Network interface for performing requests.
     */
    private final Network mNetwork;

    private final ResponseDelivery mDelivery;

    /**
     * The network dispatchers.
     */
    private NetworkDispatcher[] mDispatchers;

    /**
     * The cache dispatcher.
     */
    private CacheDispatcher mCacheDispatcher;

    private List<RequestFinishedListener> mFinishedListeners = new ArrayList<>();

    public <T> void finish(Request request) {

    }

    public RequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    public RequestQueue(Cache cache, Network network, int threadPoolSize) {
        this(cache, network, threadPoolSize,
                new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Creates the worker pool.
     * @param cache A cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     * @param delivery A ResponseDelivery interface for posting responses and errors
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize,
                        ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new NetworkDispatcher[threadPoolSize];
        mDelivery = delivery;
    }

    public static interface RequestFinishedListener<T> {
        public void onRequestFinished(Request<T> request);
    }
}
