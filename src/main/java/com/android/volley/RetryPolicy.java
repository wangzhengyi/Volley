package com.android.volley;

/**
 * Retry policy for a request.
 */
@SuppressWarnings("unused")
public interface RetryPolicy {
    /**
     * Returns the current timeout (used for logging).
     */
    int getCurrentTimeout();

    /**
     * Returns the current retry count (used for logging).
     * @return
     */
    int getCurrentRetryCount();

    /**
     * Prepares for the next retry by applying a backoff to the timeout.
     */
    void retry(VolleyError error) throws VolleyError;
}
