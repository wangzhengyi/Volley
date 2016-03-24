package com.android.volley;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Request请求调度队列. */
@SuppressWarnings("unused")
public class RequestQueue {
    /**
     * Callback interface for completed requests.
     */
    public interface RequestFinishedListener<T> {
        void onRequestFinished(Request<T> request);
    }

    /** 为每一个request申请独立的序列号. */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /** 保存所有被加入到当前队列的request集合. */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /**
     * The cache triage queue.
     */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** 存储需要进行网络通信的request的存储队列. */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** RequestQueue默认开启的网络线程的数量. */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /**
     * Cache interface for retrieving and storing responses.
     */
    private final Cache mCache;

    /** 封装request网络请求的Network类. */
    private final Network mNetwork;

    /** 网络请求传输结果实现类. */
    private final ResponseDelivery mDelivery;

    /** 网络请求线程数组. */
    private NetworkDispatcher[] mDispatchers;

    /** 缓存线程 */
    private CacheDispatcher mCacheDispatcher;

    private List<RequestFinishedListener> mFinishedListeners =
            new ArrayList<RequestFinishedListener>();

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

    /** 开启request的缓存线程和多个网络请求线程 */
    public void start() {
        // 关闭所有正在运行的缓存线程和网络请求线程.
        stop();
        // Create the cache dispatcher and start it
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // 默认开启DEFAULT_NETWORK_THREAD_POOL_SIZE(4)个线程来执行request网络请求.
        for (int i = 0; i < mDispatchers.length; i ++) {
            // 将NetworkDispatcher线程与mNetworkQueue这个队列进行绑定.
            // NetworkDispatcher会使用生产者-消费者模型从mNetworkQueue获取request请求，并执行.
            NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }
    }

    /** 停止所有的缓存线程和网络请求线程. */
    private void stop() {
        if (mCacheDispatcher != null) {
            mCacheDispatcher.quit();
        }

        for (NetworkDispatcher dispatcher : mDispatchers) {
            if (dispatcher != null) {
                dispatcher.quit();
            }
        }
    }

    /** 将Request请求加入到调度队列中. */
    public <T> Request<?> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        // 分配request唯一的序列号.
        request.setSequence(getSequenceNumber());

        // request不允许缓存,则直接将request加入到mNetworkQueue当中
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
            return request;
        }

        // Insert request into stage if there's already a request with the same cache key in flight.
        synchronized (mWaitingRequests) {
            String cacheKey = request.getCacheKey();
            if (mWaitingRequests.containsKey(cacheKey)) {
                // There is already a request in flight. Queue up.
                Queue<Request<?>> stageRequests = mWaitingRequests.get(cacheKey);
                if (stageRequests == null) {
                    stageRequests = new LinkedList<Request<?>>();
                }
                stageRequests.add(request);
                mWaitingRequests.put(cacheKey, stageRequests);
            } else {
                // Insert 'null' queue for this cacheKey, indicating there is now a request in
                // flight.
                mWaitingRequests.put(cacheKey, null);
                mCacheQueue.add(request);
            }
            return request;
        }
    }

    /** 提供request请求序列号. */
    private int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }

    <T> void finish(Request<T> request) {
        // Remove from the set of requests currently being processed.
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        synchronized (mFinishedListeners) {
            for (RequestFinishedListener<T> listener : mFinishedListeners) {
                listener.onRequestFinished(request);
            }
        }

        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                String cacheKey = request.getCacheKey();
                Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }

    public <T> void addRequestFinishedListener(RequestFinishedListener<T> listener) {
        synchronized (mFinishedListeners) {
            mFinishedListeners.add(listener);
        }
    }

    public <T> void removeRequestFinishedListener(RequestFinishedListener<T> listener) {
        synchronized (mFinishedListeners) {
            mFinishedListeners.remove(listener);
        }
    }
}
