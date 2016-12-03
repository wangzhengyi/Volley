package com.android.volley;

/**
 * Request请求的重试策略类.
 */
@SuppressWarnings("unused")
public interface RetryPolicy {
    /**
     * 获取当前请求的超时时间
     */
    int getCurrentTimeout();

    /**
     * 获取当前请求的重试次数
     */
    int getCurrentRetryCount();

    /**
     * 实现类需要重点实现的方法,用于判断当前Request是否还需要再进行重试操作
     */
    void retry(VolleyError error) throws VolleyError;
}
