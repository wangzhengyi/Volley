package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import java.util.HashMap;
import java.util.LinkedList;

@SuppressWarnings({"unused", "StringBufferReplaceableByString"})
public class ImageLoader {
    /**
     * 关联用来调用ImageLoader的RequestQueue.
     */
    private final RequestQueue mRequestQueue;

    /** 图片内存缓存接口实现类. */
    private final ImageCache mCache;

    /** 存储同一时间执行的相同CacheKey的BatchedImageRequest集合. */
    private final HashMap<String, BatchedImageRequest> mInFlightRequests =
            new HashMap<String, BatchedImageRequest>();

    private final HashMap<String, BatchedImageRequest> mBatchedResponses =
            new HashMap<String, BatchedImageRequest>();

    /** 获取主线程的Handler. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());


    private Runnable mRunnable;

    /** 定义图片K1缓存接口,即将图片的内存缓存工作交给用户来实现. */
    public interface ImageCache {
        Bitmap getBitmap(String url);
        void putBitmap(String url, Bitmap bitmap);
    }

    /** 构造一个ImageLoader. */
    public ImageLoader(RequestQueue queue, ImageCache imageCache) {
        mRequestQueue = queue;
        mCache = imageCache;
    }

    /** 构造网络图片请求成功和失败的回调接口. */
    public static ImageListener getImageListener(final ImageView view, final int defaultImageResId,
                                                 final int errorImageResId) {
        return new ImageListener() {
            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                if (response.getBitmap() != null) {
                    view.setImageBitmap(response.getBitmap());
                } else if (defaultImageResId != 0) {
                    view.setImageResource(defaultImageResId);
                }
            }

            @Override
            public void onErrorResponse(VolleyError error) {
                if (errorImageResId != 0) {
                    view.setImageResource(errorImageResId);
                }
            }
        };
    }

    public ImageContainer get(String requestUrl, ImageListener imageListener,
                               int maxWidth, int maxHeight, ScaleType scaleType) {
        // 判断当前方法是否在UI线程中执行.如果不是,则抛出异常.
        throwIfNotOnMainThread();

        final String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight, scaleType);

        // 从L1级缓存中根据key获取对应的Bitmap.
        Bitmap cacheBitmap = mCache.getBitmap(cacheKey);
        if (cacheBitmap != null) {
            // L1缓存命中,通过缓存命中的Bitmap构造ImageContainer,并调用imageListener的响应成功接口.
            ImageContainer container = new ImageContainer(cacheBitmap, requestUrl, null, null);
            // 注意:因为目前是在UI线程中,因此这里是调用onResponse方法,并非回调.
            imageListener.onResponse(container, true);
            return container;
        }

        ImageContainer imageContainer =
                new ImageContainer(null, requestUrl, cacheKey, imageListener);
        // L1缓存命中失败,则先需要对ImageView设置默认图片.然后通过子线程拉取网络图片,进行显示.
        imageListener.onResponse(imageContainer, true);

        // 检查cacheKey对应的ImageRequest请求是否正在运行.
        BatchedImageRequest request = mInFlightRequests.get(cacheKey);
        if (request != null) {
            // 相同的ImageRequest正在运行,不需要同时运行相同的ImageRequest.
            // 只需要将其对应的ImageContainer加入到BatchedImageRequest的mContainers集合中.
            // 当正在执行的ImageRequest结束后,会查看当前有多少正在阻塞的ImageRequest,
            // 然后对其mContainers集合进行回调.
            request.addContainer(imageContainer);
            return imageContainer;
        }

        // L1缓存没命中,还是需要构造ImageRequest,通过RequestQueue的调度来获取网络图片
        // 获取方法可能是:L2缓存(ps:Disk缓存)或者HTTP网络请求.
        Request<Bitmap> newRequest =
                makeImageRequest(requestUrl, maxWidth, maxHeight, scaleType, cacheKey);
        mRequestQueue.add(newRequest);
        mInFlightRequests.put(cacheKey, new BatchedImageRequest(newRequest, imageContainer));

        return imageContainer;
    }

    /** 构造L1缓存的key值. */
    private String getCacheKey(String url, int maxWidth, int maxHeight, ScaleType scaleType) {
        return new StringBuilder(url.length() + 12).append("#W").append(maxWidth)
                .append("#H").append(maxHeight).append("#S").append(scaleType.ordinal()).append(url)
                .toString();
    }

    public boolean isCached(String requestUrl, int maxWidth, int maxHeight) {
        return isCached(requestUrl, maxWidth, maxHeight, ScaleType.CENTER_INSIDE);
    }

    private boolean isCached(String requestUrl, int maxWidth, int maxHeight, ScaleType scaleType) {
        throwIfNotOnMainThread();

        String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight, scaleType);
        return mCache.getBitmap(cacheKey) != null;
    }


    /** 当L1缓存没有命中时,构造ImageRequest,通过ImageRequest和RequestQueue获取图片. */
    protected Request<Bitmap> makeImageRequest(final String requestUrl, int maxWidth, int maxHeight,
                                               ScaleType scaleType, final String cacheKey) {
        return new ImageRequest(requestUrl, new Response.Listener<Bitmap>() {
            @Override
            public void onResponse(Bitmap response) {
                onGetImageSuccess(cacheKey, response);
            }
        }, maxWidth, maxHeight, scaleType, Bitmap.Config.RGB_565, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                onGetImageError(cacheKey, error);
            }
        });
    }

    /** 图片请求失败回调.运行在UI线程中. */
    private void onGetImageError(String cacheKey, VolleyError error) {
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);
        if (request != null) {
            request.setError(error);
            batchResponse(cacheKey, request);
        }
    }

    /** 图片请求成功回调.运行在UI线程中. */
    protected void onGetImageSuccess(String cacheKey, Bitmap response) {
        // 增加L1缓存的键值对.
        mCache.putBitmap(cacheKey, response);

        // 同一时间内最初的ImageRequest执行成功后,回调这段时间阻塞的相同ImageRequest对应的成功回调接口.
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);
        if (request != null) {
            request.mResponseBitmap = response;
            // 将阻塞的ImageRequest进行结果分发.
            batchResponse(cacheKey, request);
        }
    }

    private void batchResponse(String cacheKey, BatchedImageRequest request) {
        mBatchedResponses.put(cacheKey, request);
        if (mRunnable == null) {
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    for (BatchedImageRequest bir :  mBatchedResponses.values()) {
                        for (ImageContainer container : bir.mContainers) {
                            if (container.mListener == null) {
                                continue;
                            }

                            if (bir.getError() == null) {
                                container.mBitmap = bir.mResponseBitmap;
                                container.mListener.onResponse(container, false);
                            } else {
                                container.mListener.onErrorResponse(bir.getError());
                            }
                        }
                    }
                    mBatchedResponses.clear();
                    mRunnable = null;
                }
            };
            // Post the runnable
            mHandler.postDelayed(mRunnable, 100);
        }
    }

    private void throwIfNotOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ImageLoader must be invoked from the main thread.");
        }
    }

    /** 抽象出请求成功和失败的回调接口.默认可以使用Volley提供的ImageListener. */
    public interface ImageListener extends Response.ErrorListener {
        void onResponse(ImageContainer response, boolean isImmediate);
    }

    /** 网络图片请求的承载对象. */
    public class ImageContainer {
        /** ImageView需要加载的Bitmap. */
        private Bitmap mBitmap;

        /** L1缓存的key */
        private final String mCacheKey;

        /** ImageRequest请求的url. */
        private final String mRequestUrl;

        /** 图片请求成功或失败的回调接口类. */
        private final ImageListener mListener;

        public ImageContainer(Bitmap bitmap, String requestUrl, String cacheKey,
                              ImageListener listener) {
            mBitmap = bitmap;
            mRequestUrl = requestUrl;
            mCacheKey = cacheKey;
            mListener = listener;

        }

        public void cancelRequest() {
            if (mListener == null) {
                return;
            }

            BatchedImageRequest request = mInFlightRequests.get(mCacheKey);
            if (request != null) {
                boolean canceled = request.removeContainerAndCancelIfNecessary(this);
                if (canceled) {
                    mInFlightRequests.remove(mCacheKey);
                }
            } else {
                request = mBatchedResponses.get(mCacheKey);
                if (request != null) {
                    request.removeContainerAndCancelIfNecessary(this);
                    if (request.mContainers.size() == 0) {
                        mBatchedResponses.remove(mCacheKey);
                    }
                }
            }
        }

        public Bitmap getBitmap() {
            return mBitmap;
        }

        public String getRequestUrl() {
            return mRequestUrl;
        }
    }

    /**
     * CacheKey相同的ImageRequest请求抽象类.
     * 判定两个ImageRequest相同包括:
     * 1. url相同.
     * 2. maxWidth和maxHeight相同.
     * 3. 显示的scaleType相同.
     * 同一时间可能有多个相同CacheKey的ImageRequest请求,由于需要返回的Bitmap都一样,所以用BatchedImageRequest
     * 来实现该功能.同一时间相同CacheKey的ImageRequest只能有一个.
     * 为什么不使用RequestQueue的mWaitingRequestQueue来实现该功能?
     * 答:是因为仅靠URL是没法判断两个ImageRequest相等的.
     */
    private class BatchedImageRequest {
        /** 对应的ImageRequest请求. */
        private final Request<?> mRequest;

        /** 请求结果的Bitmap对象. */
        private Bitmap mResponseBitmap;

        /** ImageRequest的错误. */
        private VolleyError mError;

        /** 所有相同ImageRequest请求结果的封装集合. */
        private final LinkedList<ImageContainer> mContainers = new LinkedList<ImageContainer>();

        public BatchedImageRequest(Request<?> request, ImageContainer container) {
            mRequest = request;
            mContainers.add(container);
        }

        public VolleyError getError() {
            return mError;
        }

        public void setError(VolleyError error) {
            mError = error;
        }

        public void addContainer(ImageContainer container) {
            mContainers.add(container);
        }

        public boolean removeContainerAndCancelIfNecessary(ImageContainer container) {
            mContainers.remove(container);
            if (mContainers.size() == 0) {
                mRequest.cancel();
                return true;
            }
            return false;
        }
    }
}
