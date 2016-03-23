package com.android.volley.toolbox;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;

import java.io.UnsupportedEncodingException;

/** 一个返回结果的String的request实现类 */
@SuppressWarnings("unused")
public class StringRequest extends Request<String>{
    private final Response.Listener<String> mListener;

    /** 根据给定的METHOD设置对应的request. */
    public StringRequest(int method, String url, Response.Listener<String> listener,
                         Response.ErrorListener errorListener) {
        super(method, url, errorListener);
        mListener = listener;
    }

    /** 默认为GET请求的request. */
    public StringRequest(String url, Response.Listener<String> listener,
                         Response.ErrorListener errorListener) {
        this(Method.GET, url, listener, errorListener);
    }

    /** 将HTTP请求结果转换为String. */
    @Override
    protected Response<String> parseNetworkResponse(NetworkResponse response) {
        String parsed;

        try {
            parsed = new String(response.data, HttpHeaderParser.parseCharset(response.headers));
        } catch (UnsupportedEncodingException e) {
            parsed = new String(response.data);
        }
        return Response.success(parsed, HttpHeaderParser.parseCacheHeaders(response));
    }

    /** 将解析的String结果传递给用户的回调接口. */
    @Override
    protected void deliverResponse(String response) {
        mListener.onResponse(response);
    }
}
