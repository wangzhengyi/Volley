package com.android.volley;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;

/** HTTP网络请求结果抽象类. */
public class NetworkResponse {
    /** HTTP响应状态码. */
    public final int statusCode;

    /** HTTP Body 响应信息. */
    public final byte[] data;

    /** 服务器状态码304代表未修改 */
    public final boolean notModified;

    /** HTTP请求的往返延迟. */
    public final long networkTimeMs;

    /** HTTP响应头信息. */
    public final Map<String, String> headers;

    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers,
                           boolean notModified, long networkTimeMs) {
        this.statusCode = statusCode;
        this.data = data;
        this.headers = headers;
        this.notModified = notModified;
        this.networkTimeMs = networkTimeMs;
    }

    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers,
                           boolean notModified) {
        this(statusCode, data, headers, notModified, 0);
    }

    public NetworkResponse(byte[] data) {
        this(HttpURLConnection.HTTP_OK, data, Collections.<String, String>emptyMap(), false, 0);
    }

    public NetworkResponse(byte[] data, Map<String, String> headers) {
        this(HttpURLConnection.HTTP_OK, data, headers, false, 0);
    }
}
