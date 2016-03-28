package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;

/** 网络图片请求类. */
@SuppressWarnings("unused")
public class ImageRequest extends Request<Bitmap> {
    /** 默认图片获取的超时时间(单位:毫秒) */
    public static final int DEFAULT_IMAGE_REQUEST_MS = 1000;

    /** 默认图片获取的重试次数. */
    public static final int DEFAULT_IMAGE_MAX_RETRIES = 2;

    private final Response.Listener<Bitmap> mListener;
    private final Bitmap.Config mDecodeConfig;
    private final int mMaxWidth;
    private final int mMaxHeight;
    private ImageView.ScaleType mScaleType;

    /** Bitmap解析同步锁,保证同一时间只有一个Bitmap被load到内存进行解析,防止OOM. */
    private static final Object sDecodeLock = new Object();

    /**
     * 构造一个网络图片请求.
     * @param url 图片的url地址.
     * @param listener 请求成功用户设置的回调接口.
     * @param maxWidth 图片的最大宽度.
     * @param maxHeight 图片的最大高度.
     * @param scaleType 图片缩放类型.
     * @param decodeConfig 解析bitmap的配置.
     * @param errorListener 请求失败用户设置的回调接口.
     */
    public ImageRequest(String url, Response.Listener<Bitmap> listener, int maxWidth, int maxHeight,
                        ImageView.ScaleType scaleType, Bitmap.Config decodeConfig,
                        Response.ErrorListener errorListener) {
        super(Method.GET, url, errorListener);
        mListener = listener;
        mDecodeConfig = decodeConfig;
        mMaxWidth = maxWidth;
        mMaxHeight = maxHeight;
        mScaleType = scaleType;
    }

    /** 设置网络图片请求的优先级. */
    @Override
    public Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    protected Response<Bitmap> parseNetworkResponse(NetworkResponse response) {
        synchronized (sDecodeLock) {
            try {
                return doParse(response);
            } catch (OutOfMemoryError e) {
                return Response.error(new VolleyError(e));
            }
        }
    }

    private Response<Bitmap> doParse(NetworkResponse response) {
        byte[] data = response.data;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        Bitmap bitmap;
        if (mMaxWidth == 0 && mMaxHeight == 0) {
            decodeOptions.inPreferredConfig = mDecodeConfig;
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
        } else {
            // 获取网络图片的真实尺寸.
            decodeOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
            int actualWidth = decodeOptions.outWidth;
            int actualHeight = decodeOptions.outHeight;

            int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight,
                    actualWidth, actualHeight, mScaleType);
            int desireHeight = getResizedDimension(mMaxWidth, mMaxHeight,
                    actualWidth, actualHeight, mScaleType);

            decodeOptions.inJustDecodeBounds = false;
            decodeOptions.inSampleSize =
                    findBestSampleSize(actualWidth, actualHeight, desiredWidth, desireHeight);
            Bitmap tempBitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);

            if (tempBitmap != null && (tempBitmap.getWidth() > desiredWidth ||
                    tempBitmap.getHeight() > desireHeight)) {
                bitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth, desireHeight, true);
                tempBitmap.recycle();
            } else {
                bitmap = tempBitmap;
            }
        }

        if (bitmap == null) {
            return Response.error(new VolleyError(response));
        } else {
            return Response.success(bitmap, HttpHeaderParser.parseCacheHeaders(response));
        }
    }

    static int findBestSampleSize(
            int actualWidth, int actualHeight, int desiredWidth, int desireHeight) {
        double wr = (double) actualWidth / desiredWidth;
        double hr = (double) actualHeight / desireHeight;
        double ratio = Math.min(wr, hr);
        float n = 1.0f;
        while ((n * 2) <= ratio) {
            n *= 2;
        }
        return (int) n;
    }

    /** 根据ImageView的ScaleType设置图片的大小. */
    private static int getResizedDimension(int maxPrimary, int maxSecondary, int actualPrimary,
                                           int actualSecondary, ImageView.ScaleType scaleType) {
        // 如果没有设置ImageView的最大值,则直接返回网络图片的真实大小.
        if ((maxPrimary == 0) && (maxSecondary == 0)) {
            return actualPrimary;
        }

        // 如果ImageView的ScaleType为FIX_XY,则将其设置为图片最值.
        if (scaleType == ImageView.ScaleType.FIT_XY) {
            if (maxPrimary == 0) {
                return actualPrimary;
            }
            return maxPrimary;
        }

        if (maxPrimary == 0) {
            double ratio = (double)maxSecondary / (double)actualSecondary;
            return (int)(actualPrimary * ratio);
        }

        if (maxSecondary == 0) {
            return maxPrimary;
        }

        double ratio = (double) actualSecondary / (double) actualPrimary;
        int resized = maxPrimary;

        if (scaleType == ImageView.ScaleType.CENTER_CROP) {
            if ((resized * ratio) < maxSecondary) {
                resized = (int)(maxSecondary / ratio);
            }
            return resized;
        }

        if ((resized * ratio) > maxSecondary) {
            resized = (int)(maxSecondary / ratio);
        }

        return resized;
    }


    @Override
    protected void deliverResponse(Bitmap response) {
        mListener.onResponse(response);
    }
}
