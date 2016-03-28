package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import java.util.HashMap;
import java.util.LinkedList;

public class ImageLoader {
    /**
     * 关联用来调用ImageLoader的RequestQueue.
     */
    private final RequestQueue mRequestQueue;

    private int mBatchResponseDelayMs = 100;

    /** 图片内存缓存接口类,允许用户自定制. */
    private final ImageCache mCache;


    private final HashMap<String, BatchedImageRequest> mInFlightRequests =
            new HashMap<String, BatchedImageRequest>();

    private final HashMap<String, BatchedImageRequest> mBatchedResponses =
            new HashMap<String, BatchedImageRequest>();

    /** 获取主线程的Handler. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());


    private Runnable mRunnable;

    /** 图片缓存接口定义. */
    public interface ImageCache {
        public Bitmap getBitmap(String url);
        public void putBitmap(String url, Bitmap bitmap);
    }

    /** 构造一个ImageLoader. */
    public ImageLoader(RequestQueue queue, ImageCache imageCache) {
        mRequestQueue = queue;
        mCache = imageCache;
    }

    public static ImageListener getImageListener(final ImageView view, final int defaultImageResId,
                                                 final int errorImageResId) {
        return new ImageListener() {
            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                if (response.getBitmap() != null) {
                    view.setImageBitmap(response.getBitmap());
                } else if (errorImageResId != 0) {
                    view.setImageResource(errorImageResId);
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

    public boolean isCached(String requestUrl, int maxWidth, int maxHeight) {
        return isCached(requestUrl, maxWidth, maxHeight, ImageView.ScaleType.CENTER_INSIDE);
    }

    private boolean isCached(String requestUrl, int maxWidth, int maxHeight, ImageView.ScaleType scaleType) {
        throwIfNotOnMainThread();


        return false;
    }

    private void throwIfNotOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ImageLoader must be invoked from the main thread.");
        }
    }

    public interface ImageListener extends Response.ErrorListener {
        public void onResponse(ImageContainer response, boolean isImmediate);
    }

    public class ImageContainer {
        /** ImageView需要加载的Bitmap. */
        private Bitmap mBitmap;

        /** 缓存key */
        private final String mCacheKey;

        /** request请求的url. */
        private final String mRequestUrl;

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

            BatchedImageRequest request =
        }

        public Bitmap getBitmap() {
            return mBitmap;
        }

        public String getRequestUrl() {
            return mRequestUrl;
        }
    }

    private class BatchedImageRequest {
        private final Request<?> mRequest;

        private Bitmap mResponseBitmap;

        private VolleyError mError;

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
