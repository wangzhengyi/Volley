package com.android.volley;


public class DefaultRetryPolicy implements RetryPolicy {
    /**
     * Request当前超时时间
     */
    private int mCurrentTimeoutMs;

    /**
     * Request当前重试次数
     */
    private int mCurrentRetryCount;

    /**
     * Request最多重试次数
     */
    private final int mMaxNumRetries;

    /**
     * Request超时时间乘积因子
     */
    private final float mBackoffMultiplier;

    /**
     * Volley默认的超时时间(2.5s)
     */
    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /**
     * Volley默认的重试次数(0次,不进行请求重试)
     */
    public static final int DEFAULT_MAX_RETRIES = 0;

    /**
     * 默认超时时间的乘积因子.
     * 以默认超时时间为2.5s为例:
     * 1. DEFAULT_BACKOFF_MULT = 1f, 则每次HttpUrlConnection设置的超时时间都是2.5s*1f*mCurrentRetryCount.
     * 2. DEFAULT_BACKOFF_MULT = 2f, 则第二次超时时间为:2.5s+2.5s*2=7.5s,第三次超时时间为:7.5s+7.5s*2=22.5s
     */
    public static final float DEFAULT_BACKOFF_MULT = 1f;

    /**
     * Request的默认重试策略构造函数
     * 超时时间:2500ms
     * 重试次数:0次
     * 超时时间因子:1f
     */
    public DefaultRetryPolicy() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT);
    }

    /**
     * 开发者自定制Request重试策略构造函数
     *
     * @param initialTimeoutMs  超时时间
     * @param maxNumRetries     最大重试次数
     * @param backoffMultiplier 超时时间乘积因子
     */
    public DefaultRetryPolicy(int initialTimeoutMs, int maxNumRetries, float backoffMultiplier) {
        mCurrentTimeoutMs = initialTimeoutMs;
        mMaxNumRetries = maxNumRetries;
        mBackoffMultiplier = backoffMultiplier;
    }

    @Override
    public int getCurrentTimeout() {
        return mCurrentTimeoutMs;
    }

    @Override
    public int getCurrentRetryCount() {
        return mCurrentRetryCount;
    }

    @Override
    public void retry(VolleyError error) throws VolleyError {
        // 添加重试次数
        mCurrentRetryCount ++;
        // 累加超时时间
        mCurrentTimeoutMs += mCurrentTimeoutMs * mBackoffMultiplier;
        // 判断是否还有剩余次数,如果没有,则抛出VolleyError异常
        if (!hasAttemptRemaining()) {
            throw error;
        }
    }

    /**
     * 判断当前Request的重试次数是否超过最大重试次数
     */
    private boolean hasAttemptRemaining() {
        return mCurrentTimeoutMs <= mMaxNumRetries;
    }
}
