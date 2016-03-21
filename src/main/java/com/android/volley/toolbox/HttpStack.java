package com.android.volley.toolbox;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;

import org.apache.http.HttpResponse;

import java.io.IOException;
import java.util.Map;

/**
 * A HTTP stack abstraction.
 */
public interface HttpStack {
    HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
        throws IOException, AuthFailureError;
}
