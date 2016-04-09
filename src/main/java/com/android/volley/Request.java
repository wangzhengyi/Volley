package com.android.volley;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;

/**
 * Volley的网络请求基类
 */
@SuppressWarnings("unused")
public abstract class Request<T> implements Comparable<Request<T>> {
    /** 默认参数编码是UTF-8. */
    private static final String DEFAULT_PARAMS_ENCODING = "UTF-8";

    /** Volley支持的Http请求类型，我们一般常用的就是GET和POST. */
    public interface Method {
        int DEPRECATED_GET_OR_POST = -1;
        int GET = 0;
        int POST = 1;
        int PUT = 2;
        int DELETE = 3;
        int HEAD = 4;
        int OPTIONS = 5;
        int TRACE = 6;
        int PATCH = 7;
    }

    /** 当前Request的HTTP请求类型. */
    private final int mMethod;

    /** 请求的url. */
    private final String mUrl;

    /** 默认的TrafficStats的tag. */
    private final int mDefaultTrafficStatsTag;

    /** request请求失败时的回调接口. */
    private final Response.ErrorListener mErrorListener;

    /** request的请求序列号，用于请求队列FIFO时排序查找使用. */
    private Integer mSequence;

    /** request的投放队列，该队列可采用FIFO方式执行request请求. */
    private RequestQueue mRequestQueue;

    /** 该request请求是否需要缓存，默认http request请求都是可以缓存的. */
    private boolean mShouldCache = true;

    /** 该request请求是否被取消的标志. */
    private boolean mCanceled = false;

    /** 该request是否已经获取请求结果. */
    private boolean mResponseDelivered = false;

    /** 遇到服务器错误(5xx)时，该request请求是否需要重试. */
    private boolean mShouldRetryServerErrors = false;

    /** request重试策略. */
    private RetryPolicy mRetryPolicy;

    /**
     * 保存request缓存的结果.
     * 因为当一个request可以被缓存，但是又必须要刷新（即需要从网络重新获取时），我们保存该缓存结果，可以确保该结果
     * 不被cache的替换策略清除掉，以防服务器返回“Not Modified”时，我们可以继续使用该缓存结果.
     */
    private Cache.Entry mCacheEntry = null;

    /**
     * 创建一个Http request对象.
     *
     * @param method HTTP请求方式(GET, POST, PUT, DELETE...).
     * @param url HTTP请求的url.
     * @param listener 当HTTP访问出错时，用户设置的回调的接口.
     */
    public Request(int method, String url, Response.ErrorListener listener) {
        mMethod = method;
        mUrl = url;
        mErrorListener = listener;
        mDefaultTrafficStatsTag = findDefaultTrafficStatsTag(url);
    }

    /** 返回HTTP请求方式. */
    public int getMethod() {
        return mMethod;
    }

    /** 返回HTTP请求错误时的回调接口. */
    public Response.ErrorListener getErrorListener() {
        return mErrorListener;
    }

    /** 返回统计类使用的Tag. */
    public int getTrafficStatsTag() {
        return mDefaultTrafficStatsTag;
    }

    /**
     * 使用url的host字段的hash值作为统计类的tag.
     */
    private static int findDefaultTrafficStatsTag(String url) {
        if (!TextUtils.isEmpty(url)) {
            Uri uri = Uri.parse(url);
            if (uri != null) {
                String host = uri.getHost();
                if (host != null) {
                    return host.hashCode();
                }
            }
        }
        return 0;
    }

    /** 设置重试接口.典型的组合模式，关联关系. */
    public Request<?> setRetryPolicy(RetryPolicy retryPolicy) {
        mRetryPolicy = retryPolicy;
        return this;
    }

    /** 调试打印当前请求进度使用 */
    public void addMarker(String tag) {
        Log.e("Volley", tag);
    }

    /** 用于告知请求队列当前request已经结束. */
    void finish(final String tag) {
        if (mRequestQueue != null) {
            mRequestQueue.finish(this);
        }
    }

    /** 设置当前request的请求队列. */
    public Request<?> setRequestQueue(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
        return this;
    }

    /** 设置当前request在当前request队列的系列号. */
    public final Request<?> setSequence(int sequence) {
        mSequence = sequence;
        return this;
    }

    /** 返回request请求的序列号. */
    public final int getSequence() {
        if (mSequence == null) {
            throw new IllegalStateException("getSequence called before setSequence");
        }
        return mSequence;
    }

    /** 返回request的url. */
    public String getUrl() {
        return mUrl;
    }

    /** 使用request的url作为volley cache缓存系统存储的key值(默认url可唯一标识一个request). */
    public String getCacheKey() {
        return getUrl();
    }

    /** 设置request对应的volley cache缓存系统中的请求结果. */
    public Request<?> setCacheEntry(Cache.Entry entry) {
        mCacheEntry = entry;
        return this;
    }

    /** 返回request的cache系统的请求结果. */
    public Cache.Entry getCacheEntry() {
        return mCacheEntry;
    }

    /** 标识该request已经被取消. */
    public void cancel() {
        mCanceled = true;
    }

    /** 返回该request是否被取消标识. */
    public boolean isCanceled() {
        return mCanceled;
    }

    /** 返回该request的headers. */
    public Map<String, String> getHeaders() throws AuthFailureError {
        return Collections.emptyMap();
    }

    /** 返回该request的请求体中参数.
     * 如果是GET请求，则直接返回null.
     * 如果是POST请求，需要重写该方法，返回需要传递的参数Map.
     */
    protected Map<String, String> getParams() throws AuthFailureError {
        return null;
    }

    /** 返回该request请求参数编码. */
    protected String getParamsEncoding() {
        return DEFAULT_PARAMS_ENCODING;
    }

    /** 获取request body content type. */
    public String getBodyContentType() {
        return "application/x-www-form-urlencoded; charset="
                + getParamsEncoding();
    }

    /** 返回request请求参数体. */
    public byte[] getBody() throws AuthFailureError {
        Map<String, String> params = getParams();
        if (params != null && params.size() > 0) {
            return encodeParameters(params, getParamsEncoding());
        }
        return null;
    }

    /** 构造post请求参数体. */
    private byte[] encodeParameters(Map<String, String> params, String paramsEncoding) {
        StringBuilder encodedParams = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                encodedParams.append(URLEncoder.encode(entry.getKey(), paramsEncoding));
                encodedParams.append("=");
                encodedParams.append(URLEncoder.encode(entry.getValue(), paramsEncoding));
                encodedParams.append("&");
            }
            return encodedParams.toString().getBytes(paramsEncoding);
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("Encoding not supported:" + paramsEncoding, uee);
        }
    }

    /** 设置当前request是否需要被缓存. */
    public final Request<?> setShouldCache(boolean shouldCache) {
        mShouldCache = shouldCache;
        return this;
    }

    /** 返回当前request是否需要被缓存. */
    public final boolean shouldCache() {
        return mShouldCache;
    }

    /** 设置request的重试接口. */
    public final Request<?> setShouldRetryServerErrors(boolean shouldRetryServerErrors) {
        mShouldRetryServerErrors = shouldRetryServerErrors;
        return this;
    }

    /** 返回该request当遇到服务器错误时是否需要重试标志 */
    public final boolean shouldRetryServerErrors() {
        return mShouldRetryServerErrors;
    }

    /** request优先级枚举类. */
    public enum  Priority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }

    /** 返回当前request的优先级.子类可以重写该方法修改request的优先级. */
    public Priority getPriority() {
        return Priority.NORMAL;
    }

    /** 返回重试的时间，用于日志记录. */
    public final int getTimeoutMs() {
        return mRetryPolicy.getCurrentTimeout();
    }

    /** 返回重试接口. */
    public RetryPolicy getRetryPolicy() {
        return mRetryPolicy;
    }

    /** 用于标识已经将response传给该request. */
    public void markDelivered() {
        mResponseDelivered = true;
    }

    /** 返回该request是否有response delivered. */
    public boolean hasHadResponseDelivered() {
        return mResponseDelivered;
    }

    /** 子类必须重写该方法，用来解析http请求的结果. */
    abstract protected Response<T> parseNetworkResponse(NetworkResponse response);

    /** 子类可以重写该方法，从而获取更精准的出错信息. */
    protected VolleyError parseNetworkError(VolleyError volleyError) {
        return volleyError;
    }

    /** 子类必须重写该方法用于将网络结果返回给用户设置的回调接口. */
    abstract protected void deliverResponse(T response);

    /** 将网络错误传递给回调接口. */
    public void deliverError(VolleyError error) {
        if (mErrorListener != null) {
            mErrorListener.onErrorResponse(error);
        }
    }

    /** 先判断执行顺序，再判断request优先级. */
    @Override
    public int compareTo(Request<T> another) {
        Priority left = this.getPriority();
        Priority right = another.getPriority();

        return left == right ? this.mSequence - another.mSequence :
                right.ordinal() - left.ordinal();
    }

    @Override
    public String toString() {
        String trafficStatsTag = "0x" + Integer.toHexString(getTrafficStatsTag());
        return (mCanceled ? "[X]" : "[ ]") + getUrl() + " " + trafficStatsTag + " " +
                getPriority() + " " + mSequence;
    }
}
