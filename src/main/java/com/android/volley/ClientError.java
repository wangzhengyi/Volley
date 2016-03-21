package com.android.volley;

/**
 * Indicates that the server responded with an error response indicating that the client has erred.
 */
public class ClientError extends ServerError {
    public ClientError(NetworkResponse networkResponse) {
        super(networkResponse);
    }
    public ClientError() {
        super();
    }
}
