# Volley超时重试机制

------
## 基础用法

Volley为开发者提供了可配置的超时重试机制,我们在使用时只需要为我们的Request设置自定义的RetryPolicy即可.
参考设置代码如下:
```java
int DEFAULT_TIMEOUT_MS = 10000;
int DEFAULT_MAX_RETRIES = 3;
StringRequest stringRequest = new StringRequest(Request.Method.GET, url, new Response.Listener<String>() {
    @Override
    public void onResponse(String s) {
        LogUtil.i(TAG, "res=" + s);
    }
}, new Response.ErrorListener() {
    @Override
    public void onErrorResponse(VolleyError volleyError) {
        LogUtil.e(TAG, volleyError.toString());
    }
});
// 设置Volley超时重试策略
stringRequest.setRetryPolicy(new DefaultRetryPolicy(
        DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
RequestQueue requestQueue = VolleyManager.getInstance(context).getRequestQueue();
requestQueue.add(stringRequest);
```

------
## 基础知识

在讲解Volley的超时重试原理之前,需要先普及一下跟超时重试相关的异常.

**org.apache.http.conn.ConnectTimeoutException**

```text
A timeout while connecting to an HTTP server or waiting for an available connection from an HttpConnectionManager.
```
连接HTTP服务端超时或者等待HttpConnectionManager返回可用连接超时,俗称请求超时.

**java.net.SocketTimeoutException**

```text
Signals that a timeout has occurred on a socket read or accept.
```
Socket通信超时,即从服务端读取数据时超时,俗称响应超时.

Volley就是通过捕捉这两个异常来进行超时重试的.

------
## Volley捕捉超时异常

看过之前Volley源码分析的同学,应该知道Volley中是通过BasicNetwork去执行网络请求的.相关源码如下:
```java
@Override
public NetworkResponse performRequest(Request<?> request) throws VolleyError {
    // 记录请求开始的时间,便于进行超时重试
    long requestStart = SystemClock.elapsedRealtime();
    while (true) {
        HttpResponse httpResponse = null;
        byte[] responseContents = null;
        Map<String, String> responseHeaders = Collections.emptyMap();
        try {
            // ......省略部分添加HTTP-HEADER的代码

            // 调用HurlStack的performRequest方法执行网络请求, 并将请求结果存入httpResponse变量中
            httpResponse = mHttpStack.performRequest(request, headers);

            StatusLine statusLine = httpResponse.getStatusLine();
            int statusCode = statusLine.getStatusCode();
            responseHeaders = convertHeaders(httpResponse.getAllHeaders());

            // ......省略部分对返回值处理的代码
            return new NetworkResponse(statusCode, responseContents, responseHeaders, false,
                    SystemClock.elapsedRealtime() - requestStart);
        } catch (SocketTimeoutException e) {
            // 捕捉响应超时
            attemptRetryOnException("socket", request, new TimeoutError());
        } catch (ConnectTimeoutException E) {
            // 捕捉请求超时
            attemptRetryOnException("connection", request, new TimeoutError());
        } catch (IOException e) {
            // 省略对IOException的异常处理,不考虑服务端错误的重试机制
        }
    }
}
private void attemptRetryOnException(String logPrefix, Request<?> request, VolleyError exception) throws VolleyError{
    RetryPolicy retryPolicy = request.getRetryPolicy();
    int oldTimeout = request.getTimeoutMs();

    retryPolicy.retry(exception);
    Log.e("Volley", String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
}
```

因为BasicNetwork类中是用```java while(true) ```包裹连接请求,因此如果捕捉到程序抛出**SocketTimeoutException**或者**ConnectTimeoutException**,并不会跳出退出循环的操作,而是进入到attemptRetryOnException方法.
如果attemptRetryOnException方法中没有抛出VolleyError异常,最终程序还是可以再次进入while循环,从而完成超时重试机制.
接下来,我们看一下RetryPolicy是如何判断是否需要进行超时重试,并且是如何停止超时重试的.

------
## RetryPolicy.java

RetryPolicy是一个接口定义,中文注释的源码如下:
```java
/**
 * Request请求的重试策略类.
 */
@SuppressWarnings("unused")
public interface RetryPolicy {
    /**
     * 获取当前请求的超时时间
     */
    int getCurrentTimeout();

    /**
     * 获取当前请求的重试次数
     */
    int getCurrentRetryCount();

    /**
     * 实现类需要重点实现的方法,用于判断当前Request是否还需要再进行重试操作
     */
    void retry(VolleyError error) throws VolleyError;
}
```

RetryPolicy只是Volley定义的Request请求重试策略接口,同时也提供了DefaultRetryPolicy实现类来帮助开发者来快速实现自定制的请求重试功能.

------
## DefaultRetryPolicy.java

中文注释的源码如下:
```java
public class DefaultRetryPolicy implements RetryPolicy {
    /**
     * Request当前超时时间
     */
    private int mCurrentTimeoutMs;

    /**
     * Request当前重试次数
     */
    private int mCurrentRetryCount;

    /**
     * Request最多重试次数
     */
    private final int mMaxNumRetries;

    /**
     * Request超时时间乘积因子
     */
    private final float mBackoffMultiplier;

    /**
     * Volley默认的超时时间(2.5s)
     */
    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /**
     * Volley默认的重试次数(0次,不进行请求重试)
     */
    public static final int DEFAULT_MAX_RETRIES = 0;

    /**
     * 默认超时时间的乘积因子.
     * 以默认超时时间为2.5s为例:
     * 1. DEFAULT_BACKOFF_MULT = 1f, 则每次HttpUrlConnection设置的超时时间都是2.5s*1f*mCurrentRetryCount.
     * 2. DEFAULT_BACKOFF_MULT = 2f, 则第二次超时时间为:2.5s+2.5s*2=7.5s,第三次超时时间为:7.5s+7.5s*2=22.5s
     */
    public static final float DEFAULT_BACKOFF_MULT = 1f;

    /**
     * Request的默认重试策略构造函数
     * 超时时间:2500ms
     * 重试次数:0次
     * 超时时间因子:1f
     */
    public DefaultRetryPolicy() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT);
    }

    /**
     * 开发者自定制Request重试策略构造函数
     *
     * @param initialTimeoutMs  超时时间
     * @param maxNumRetries     最大重试次数
     * @param backoffMultiplier 超时时间乘积因子
     */
    public DefaultRetryPolicy(int initialTimeoutMs, int maxNumRetries, float backoffMultiplier) {
        mCurrentTimeoutMs = initialTimeoutMs;
        mMaxNumRetries = maxNumRetries;
        mBackoffMultiplier = backoffMultiplier;
    }

    @Override
    public int getCurrentTimeout() {
        return mCurrentTimeoutMs;
    }

    @Override
    public int getCurrentRetryCount() {
        return mCurrentRetryCount;
    }

    @Override
    public void retry(VolleyError error) throws VolleyError {
        // 添加重试次数
        mCurrentRetryCount ++;
        // 累加超时时间
        mCurrentTimeoutMs += mCurrentTimeoutMs * mBackoffMultiplier;
        // 判断是否还有剩余次数,如果没有,则抛出VolleyError异常
        if (!hasAttemptRemaining()) {
            throw error;
        }
    }

    /**
     * 判断当前Request的重试次数是否超过最大重试次数
     */
    private boolean hasAttemptRemaining() {
        return mCurrentTimeoutMs <= mMaxNumRetries;
    }
}
```

本文开始时就讲过Request如何设置自定制的RetryPolicy,结合中文注释的DefaultRetryPolicy源码,相信大家很容易就能理解自定制RetryPolicy的参数含义和作用.
目前可能大家还有一个疑问,为什么DefaultRetryPolicy的retry方法抛出VolleyError异常,就能退出BasicNetwork类performRequest的while(true)循环呢?
这是因为BasicNetwork并没有捕获VolleyError异常,因此没有被try&catch住的异常会终止当前程序的运行,继续往外抛出,这时候就回到NetworkDispatcher类,相关源码如下:
```java
@Override
public void run() {
    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    while (true) {
        long startTimeMs = SystemClock.elapsedRealtime();
        Request<?> request;
        try {
            // 使用BlockingQueue实现了生产者-消费者模型.
            // 消费者是该调度线程.
            // 生产者是request网络请求.
            request = mQueue.take();
        } catch (InterruptedException e) {
            // We may have been interrupted because it was time to quit.
            if (mQuit) {
                return;
            }
            continue;
        }

        try {
            if (request.isCanceled()) {
                continue;
            }

            addTrafficStatsTag(request);

            // 真正执行网络请求的地方.(BasicNetwork由于超时抛出的VolleyError会抛出到这里)
            NetworkResponse networkResponse = mNetwork.performRequest(request);
            
            mDelivery.postResponse(request, response);
        } catch (VolleyError volleyError) {
            // 捕获VolleyError异常,通过主线程Handler回调用户设置的ErrorListener中的onErrorResponse回调方法.
            volleyError.printStackTrace();
            volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
            parseAndDeliverNetworkError(request, volleyError);
        } catch (Exception e) {
            VolleyError volleyError = new VolleyError(e);
            volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
            mDelivery.postError(request, volleyError);
        }
    }
}
```

从上述源码中可以看出,Request无法继续重试后抛出的VolleyError异常,会被NetworkDispatcher捕获,然后通过Delivery去回调用户设置的ErrorListener.

------
## 小结

至此,Volley的超时重试机制就分析完了,在本文末尾给大家推荐一下Volley默认重试策略的参数.
默认超时时间:Volley的默认2500ms确实有点短,大家可以设置成HttpClient的默认超时时间,也就是10000ms.
默认重试次数:建议为3次,可根据业务自行调整.
默认超时时间因子:建议采用DefaultRetryPolicy的默认值1f即可,不然曲线增长太快会造成页面长时间的等待.
