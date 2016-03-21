package com.android.volley;

/**
 * Indicates that the server responded with an error response.
 */
public class ServerError extends VolleyError {
    public ServerError(NetworkResponse response) {
        super(response);
    }

    public ServerError() {
        super();
    }
}
