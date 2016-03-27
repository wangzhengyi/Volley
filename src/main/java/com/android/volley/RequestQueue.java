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

    /** 维护了一个等待请求的集合,如果一个请求正在被处理并且可以被缓存,后续的相同 url 的请求,将进入此等待队列 */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /** 保存所有被加入到当前队列的request集合. */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /** 与缓存线程(CacheDispatcher)绑定的缓存队列. */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** 存储需要进行网络通信的request的存储队列. */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** RequestQueue默认开启的网络线程的数量. */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /** Disk缓存实现类. */
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
        // 开启缓存线程.
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // 默认开启DEFAULT_NETWORK_THREAD_POOL_SIZE(4)个线程来执行request网络请求.
        for (int i = 0; i < mDispatchers.length; i ++) {
            // 将NetworkDispatcher线程与mNetworkQueue这个队列进行绑定.
            // NetworkDispatcher会使用生产者-消费者模型从mNetworkQueue获取request请求,并执行.
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
                // 表示RequestQueue正在调度过该Request,因为后续相同的Request先入队列,排队等待执行.
                Queue<Request<?>> stageRequests = mWaitingRequests.get(cacheKey);
                if (stageRequests == null) {
                    stageRequests = new LinkedList<Request<?>>();
                }
                stageRequests.add(request);
                mWaitingRequests.put(cacheKey, stageRequests);
            } else {
                // 将Request加入到等待Map中,表示Request正在执行.
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

    /** 该方法的调用时机为:参数Request将请求结果回调给用户接口时,会调用该方法告知此Request已经结束. */
    <T> void finish(Request<T> request) {
        // 从正在执行的Request队列中删除指定的request.
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        // 观察者模式,通知Observer该request请求结束.
        synchronized (mFinishedListeners) {
            for (RequestFinishedListener<T> listener : mFinishedListeners) {
                listener.onRequestFinished(request);
            }
        }

        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                // 因为当前Request已经正常结束,而且该request是可以缓存的,所以这时需要直接把正在等待的所有相同
                // url的Request全部加入到缓存队列中,从缓存系统读取结果后回调用户接口.
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
