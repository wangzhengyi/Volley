package com.android.volley;

/** 网络请求结果的封装类.其中泛型T为网络解析结果. */
public class Response<T> {
    /** request请求成功回调接口, 用于用户自行处理网络请求返回的结果. */
    public interface Listener<T> {
        void onResponse(T response);
    }

    /** request请求失败回调接口，用于用户自行处理网络请求失败的情况. */
    public interface ErrorListener {
        void onErrorResponse(VolleyError error);
    }

    /** 构造一个request请求成功的response对象. */
    public static <T> Response<T> success(T result, Cache.Entry cacheEntry) {
        return new Response<T>(result, cacheEntry);
    }

    /** 构造一个request请求失败的response对象. */
    public static <T> Response<T> error(VolleyError error) {
        return new Response<T>(error);
    }

    /** request的网络请求解析结果. */
    public final T result;

    /** request的缓存内容. */
    public final Cache.Entry cacheEntry;

    /** 请求错误内容. */
    public final VolleyError error;

    /** 当前结果是否为中间请求结果. */
    public boolean intermediate = false;

    /** 返回当前request请求结果是否成功. */
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
