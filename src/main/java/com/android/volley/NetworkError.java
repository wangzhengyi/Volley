package com.android.volley;

/**
 * Indicates that there was a network error when performing a Volley request.
 */
public class NetworkError extends VolleyError{
    public NetworkError() {
    }

    public NetworkError(Throwable cause) {
        super(cause);
    }

    public NetworkError(NetworkResponse response) {
        super(response);
    }
}
