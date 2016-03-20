package com.android.volley;

/**
 * Encapsulated a parsed response for delivery.
 */
public class Response<T> {
    /**
     * Callback interface for delivering parsed responses.
     */
    public interface Listener<T> {
        void onResponse(T response);
    }

    /**
     * Callback interface for delivering error responses.
     */
    public interface ErrorListener {
        void onErrorResponse(VolleyError error);
    }

    /**
     * Returns a successful response containing the parsed result.n
     */
    public static <T> Response<T> success(T result, Cache.Entry cacheEntry) {
        return new Response<T>(result, cacheEntry);
    }

    /**
     * Returns a failed response containing the given error code and an optional
     * localized message displayed to the user.
     */
    public static <T> Response<T> error(VolleyError error) {
        return new Response<T>(error);
    }

    /**
     * Parsed response, or null in the case of error.
     */
    public final T result;

    /**
     * Cache metadata for this response, or null in the case of error.
     */
    public final Cache.Entry cacheEntry;

    /**
     * Detailed error information if errorCode != OK
     */
    public final VolleyError error;

    /**
     * True if this response was soft-expired one and a second one MAY be coming.
     */
    public boolean intermediate = false;

    /**
     * Returns whether this response is considered successful.
     */
    public boolean isSuccess() {
        return error == null;
    }

    private Response(T result, Cache.Entry cacheEntry) {
        this.result = result;
        this.cacheEntry = cacheEntry;
        this.error = null;
    }

    private Response(VolleyError error) {
        this.result = null;
        this.cacheEntry = null;
        this.error = error;
    }
}
