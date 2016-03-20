package com.android.volley;

/**
 * An interface for performing requests.
 */
public interface Network {
    NetworkResponse performRequest(Request<?> request) throws VolleyError;
}
