package com.android.volley;

/**
 * Error indicating that no connection could be established when performing a Volley request.
 */
@SuppressWarnings("unused")
public class NoConnctionError extends NetworkError {
    public NoConnctionError() {
        super();
    }

    public NoConnctionError(Throwable reason) {
        super(reason);
    }
}
