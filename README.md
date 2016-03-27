# Volley源码解析

为了学习Volley的网络框架,我在AS中将Volley代码重新撸了一遍,感觉这种照抄代码也是一种挺好的学习方式.再分析Volley源码之前,我们先考虑一下,如果我们自己要设计一个网络请求框架,需要实现哪些事情,有哪些注意事项?

我的总结如下：

1. 需要抽象出request请求类（包括url, params, method等）,抽象出request请求类之后,我们可以对其继承从而实现丰富的扩展功能.
2. 需要抽象出response类.即服务器返回的结果需要抽象出来,方便我们继承扩展.
3. 需要实现并发和异步操作.具体包括：
    
    3-1. 抽象出Http请求类,封装基本操作.
    
    3-2. 将Http请求类在子线程中执行,最好能支撑并发.
    
    3-3. 由于需要并发,所以要用队列控制,并且能随时终止并发.
    
    3-4. 子线程获取结果后,需要支持异步,将请求结果返回给主线程.

4. 最好能实现缓存.当request抽象出来后,那相同的request请求可以直接从本地获取,不需要再通过网络获取.
5. 缓存需要有缓存替换机制,超时更新机制等.

在我总结的这些问题的基础上,我们来学习一下Volley是如何解决并实现这些问题的.

****

# 网络请求抽象类

Request类就是Volley抽象出来的网络请求类了.我已经对其进行了中文注解,大家可以直接看一下其实现代码：

```java
/**
 * Volley的网络请求基类
 */
@SuppressWarnings("unused")
public abstract class Request<T> implements Comparable<Request<T>> {
    /** 默认参数编码是UTF-8. */
    private static final String DEFAULT_PARAMS_ENCODING = "UTF-8";

    /** Volley支持的Http请求类型,我们一般常用的就是GET和POST. */
    public interface Method {
        int DEPRECATED_GET_OR_POST = -1;
        int GET = 0;
        int POST = 1;
        int PUT = 2;
        int DELETE = 3;
        int HEAD = 4;
        int OPTIONS = 5;
        int TRACE = 6;
        int PATCH = 7;
    }

    /** 当前Request的HTTP请求类型. */
    private final int mMethod;

    /** 请求的url. */
    private final String mUrl;

    /** 默认的TrafficStats的tag. */
    private final int mDefaultTrafficStatsTag;

    /** request请求失败时的回调接口. */
    private final Response.ErrorListener mErrorListener;

    /** request的请求序列号,用于请求队列FIFO时排序查找使用. */
    private Integer mSequence;

    /** request的投放队列,该队列可采用FIFO方式执行request请求. */
    private RequestQueue mRequestQueue;

    /** 该request请求是否需要缓存,默认http request请求都是可以缓存的. */
    private boolean mShouldCache = true;

    /** 该request请求是否被取消的标志. */
    private boolean mCanceled = false;

    /** 该request是否已经获取请求结果. */
    private boolean mResponseDelivered = false;

    /** 遇到服务器错误(5xx)时,该request请求是否需要重试. */
    private boolean mShouldRetryServerErrors = false;

    /** request重试策略. */
    private RetryPolicy mRetryPolicy;

    /**
     * 保存request缓存的结果.
     * 因为当一个request可以被缓存,但是又必须要刷新（即需要从网络重新获取时）,我们保存该缓存结果,可以确保该结果
     * 不被cache的替换策略清除掉,以防服务器返回“Not Modified”时,我们可以继续使用该缓存结果.
     */
    private Cache.Entry mCacheEntry = null;

    /**
     * 创建一个Http request对象.
     *
     * @param method HTTP请求方式(GET, POST, PUT, DELETE...).
     * @param url HTTP请求的url.
     * @param listener 当HTTP访问出错时,用户设置的回调的接口.
     */
    public Request(int method, String url, Response.ErrorListener listener) {
        mMethod = method;
        mUrl = url;
        mErrorListener = listener;
        mDefaultTrafficStatsTag = findDefaultTrafficStatsTag(url);
    }

    /** 返回HTTP请求方式. */
    public int getMethod() {
        return mMethod;
    }

    /** 返回HTTP请求错误时的回调接口. */
    public Response.ErrorListener getErrorListener() {
        return mErrorListener;
    }

    /** 返回统计类使用的Tag. */
    public int getTrafficStatsTag() {
        return mDefaultTrafficStatsTag;
    }

    /**
     * 使用url的host字段的hash值作为统计类的tag.
     */
    private static int findDefaultTrafficStatsTag(String url) {
        if (!TextUtils.isEmpty(url)) {
            Uri uri = Uri.parse(url);
            if (uri != null) {
                String host = uri.getHost();
                if (host != null) {
                    return host.hashCode();
                }
            }
        }
        return 0;
    }

    /** 设置重试接口.典型的组合模式,关联关系. */
    public Request<?> setRetryPolicy(RetryPolicy retryPolicy) {
        mRetryPolicy = retryPolicy;
        return this;
    }

    /** 调试打印当前请求进度使用 */
    public void addMarker(String tag) {
        Log.e("Volley", tag);
    }

    /** 用于告知请求队列当前request已经结束. */
    void finish(final String tag) {
        if (mRequestQueue != null) {
            mRequestQueue.finish(this);
        }
    }

    /** 设置当前request的请求队列. */
    public Request<?> setRequestQueue(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
        return this;
    }

    /** 设置当前request在当前request队列的系列号. */
    public final Request<?> setSequence(int sequence) {
        mSequence = sequence;
        return this;
    }

    /** 返回request请求的序列号. */
    public final int getSequence() {
        if (mSequence == null) {
            throw new IllegalStateException("getSequence called before setSequence");
        }
        return mSequence;
    }

    /** 返回request的url. */
    public String getUrl() {
        return mUrl;
    }

    /** 使用request的url作为volley cache缓存系统存储的key值(默认url可唯一标识一个request). */
    public String getCacheKey() {
        return getUrl();
    }

    /** 设置request对应的volley cache缓存系统中的请求结果. */
    public Request<?> setCacheEntry(Cache.Entry entry) {
        mCacheEntry = entry;
        return this;
    }

    /** 返回request的cache系统的请求结果. */
    public Cache.Entry getCacheEntry() {
        return mCacheEntry;
    }

    /** 标识该request已经被取消. */
    public void cancel() {
        mCanceled = true;
    }

    /** 返回该request是否被取消标识. */
    public boolean isCanceled() {
        return mCanceled;
    }

    /** 返回该request的headers. */
    public Map<String, String> getHeaders() throws AuthFailureError {
        return Collections.emptyMap();
    }

    /** 返回该request的请求体中参数,如果是GET请求,则直接返回null. */
    protected Map<String, String> getParams() throws AuthFailureError {
        return null;
    }

    /** 返回该request请求参数编码. */
    protected String getParamsEncoding() {
        return DEFAULT_PARAMS_ENCODING;
    }

    /** 获取request body content type. */
    public String getBodyContentType() {
        return "application/x-www-form-urlencoded; charset="
                + getParamsEncoding();
    }

    /** 返回request请求参数体. */
    public byte[] getBody() throws AuthFailureError {
        Map<String, String> params = getParams();
        if (params != null && params.size() > 0) {
            return encodeParameters(params, getParamsEncoding());
        }
        return null;
    }

    /** 构造post请求参数体. */
    private byte[] encodeParameters(Map<String, String> params, String paramsEncoding) {
        StringBuilder encodedParams = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                encodedParams.append(URLEncoder.encode(entry.getKey(), paramsEncoding));
                encodedParams.append("=");
                encodedParams.append(URLEncoder.encode(entry.getValue(), paramsEncoding));
                encodedParams.append("&");
            }
            return encodedParams.toString().getBytes(paramsEncoding);
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("Encoding not supported:" + paramsEncoding, uee);
        }
    }

    /** 设置当前request是否需要被缓存. */
    public final Request<?> setShouldCache(boolean shouldCache) {
        mShouldCache = shouldCache;
        return this;
    }

    /** 返回当前request是否需要被缓存. */
    public final boolean shouldCache() {
        return mShouldCache;
    }

    /** 设置request的重试接口. */
    public final Request<?> setShouldRetryServerErrors(boolean shouldRetryServerErrors) {
        mShouldRetryServerErrors = shouldRetryServerErrors;
        return this;
    }

    /** 返回该request当遇到服务器错误时是否需要重试标志 */
    public final boolean shouldRetryServerErrors() {
        return mShouldRetryServerErrors;
    }

    /** request优先级枚举类. */
    public enum  Priority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }

    /** 返回当前request的优先级.子类可以重写该方法修改request的优先级. */
    public Priority getPriority() {
        return Priority.NORMAL;
    }

    /** 返回重试的时间,用于日志记录. */
    public final int getTimeoutMs() {
        return mRetryPolicy.getCurrentTimeout();
    }

    /** 返回重试接口. */
    public RetryPolicy getRetryPolicy() {
        return mRetryPolicy;
    }

    /** 用于标识已经将response传给该request. */
    public void markDelivered() {
        mResponseDelivered = true;
    }

    /** 返回该request是否有response delivered. */
    public boolean hasHadResponseDelivered() {
        return mResponseDelivered;
    }

    /** 子类必须重写该方法,用来解析http请求的结果. */
    abstract protected Response<T> parseNetworkResponse(NetworkResponse response);

    /** 子类可以重写该方法,从而获取更精准的出错信息. */
    protected VolleyError parseNetworkError(VolleyError volleyError) {
        return volleyError;
    }

    /** 子类必须重写该方法用于将网络结果返回给用户设置的回调接口. */
    abstract protected void deliverResponse(T response);

    /** 将网络错误传递给回调接口. */
    public void deliverError(VolleyError error) {
        if (mErrorListener != null) {
            mErrorListener.onErrorResponse(error);
        }
    }

    /** 先判断执行顺序,再判断request优先级. */
    @Override
    public int compareTo(@NonNull Request<T> another) {
        Priority left = this.getPriority();
        Priority right = another.getPriority();

        return left == right ? this.mSequence - another.mSequence :
                right.ordinal() - left.ordinal();
    }

    @Override
    public String toString() {
        String trafficStatsTag = "0x" + Integer.toHexString(getTrafficStatsTag());
        return (mCanceled ? "[X]" : "[ ]") + getUrl() + " " + trafficStatsTag + " " +
                getPriority() + " " + mSequence;
    }
}
```

代码虽然很长,但是都是对request很好的抽象,建议大家结合HTTP协议阅读一下该源码.
Request中的泛型T用来对结果进行泛型表示,当定义出request基类之后,我们可以很轻松的对其进行继承,从而扩展出我们想要的request请求.

例如Volley提供的StringRequest,源码如下:
```java
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
```

有了这个StringRequest类示例,我们也可以参考其实现很方便的对Request类进行扩展.再对request进行扩展时,我们通常只需要实现两个方法即可：

1. deliverResponse：这个方法很简单,就是将网络解析的结果传递给用户设置的回调接口.
2. parseNetworkResponse : 这个方法比较关键,我们主要也是来重写该方法.如果我需要返回JsonObject,那么我就需要将参数NetworkResponse在该方法中转换成JsonObject.
3. getParams : 这个方法是如果有POST参数时,需要重写该方法.

介绍完Request抽象,那我们继续来看一下Response抽象.

****

# 网络请求结果抽象类

## Response.java

Response是Volley抽象出来对网络请求结果进行封装的类.具体注释源码如下：
```java
/** 网络请求结果的封装类.其中泛型T为网络解析结果. */
public class Response<T> {
    /** request请求成功回调接口, 用于用户自行处理网络请求返回的结果. */
    public interface Listener<T> {
        void onResponse(T response);
    }

    /** request请求失败回调接口,用于用户自行处理网络请求失败的情况. */
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
```

其实,Response只是对request请求结果的进一步封装.真正的HTTP Request请求结果的抽象其实是NetworkResponse类.

## NetworkResponse.java

NetworkResponse类是真正的HTTP网络请求结果类,其注释源码如下：
```java
/** HTTP网络请求结果抽象类. */
public class NetworkResponse {
    /** HTTP响应状态码. */
    public final int statusCode;

    /** HTTP响应信息. */
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
```

****
# 网络请求的并发和异步

在讲解网络请求的并发和异步之前,我们先来看一下,Volley是如何封装网络请求的.

## HurlStack.java

这个类封装了HttpURLConnection类的构造操作,我自己实现网络请求时,也会封装这些重复的HttpURLConnection构造代码.注释代码如下:
```java
/** 封装HttpURLConnection类,简化网络请求代码. */
public class HurlStack implements HttpStack {
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    private final SSLSocketFactory mSslSocketFactory;

    /** 默认创建一个HTTP请求类. */
    public HurlStack() {
        this(null);
    }

    /** 创建一个HTTPS请求类. */
    public HurlStack(SSLSocketFactory sslSocketFactory) {
        mSslSocketFactory = sslSocketFactory;
    }

    /** HTTP or HTTPS请求真正执行的地方 */
    @Override
    public HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        HashMap<String, String> map = new HashMap<String, String>();
        map.putAll(request.getHeaders());
        map.putAll(additionalHeaders);

        // 构造HttpURLConnection,封装一些固定参数.
        String url = request.getUrl();
        URL parsedUrl = new URL(url);
        HttpURLConnection connection = openConnection(parsedUrl, request);
        // 构造http请求的header.
        for (String headerName: map.keySet()) {
            connection.addRequestProperty(headerName, map.get(headerName));
        }
        // 构造http请求的body.
        setConnectionParametersForRequest(connection, request);

        // Initialize HttpResponse with data from the HttpURLConnection
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);
        int responseCode = connection.getResponseCode();
        if (responseCode == -1) {
            throw new IOException("Could not retrieve response code from HttpUrlConnection.");
        }

        // 使用apache提供的BasicHttpResponse来封装请求.
        StatusLine responseStatus = new BasicStatusLine(protocolVersion,
                connection.getResponseCode(), connection.getResponseMessage());
        BasicHttpResponse response = new BasicHttpResponse(responseStatus);
        if (hasResponseBody(request.getMethod(), responseStatus.getStatusCode())) {
            response.setEntity(entityFromConnection(connection));
        }
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() != null) {
                Header h = new BasicHeader(header.getKey(), header.getValue().get(0));
                response.addHeader(h);
            }
        }

        return response;
    }

    /** 封装HttpURLConnection类的构造函数. */
    private HttpURLConnection openConnection(URL url, Request<?> request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(HttpURLConnection.getFollowRedirects());

        int timeoutMs = request.getTimeoutMs();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setDoInput(true);

        if ("https".equals(url.getProtocol()) && mSslSocketFactory != null) {
            ((HttpsURLConnection)connection).setSSLSocketFactory(mSslSocketFactory);
        }

        return connection;
    }

    /* package */ static void setConnectionParametersForRequest(HttpURLConnection connection,
                                                                Request<?> request)
            throws IOException, AuthFailureError {
        switch (request.getMethod()) {
            case Request.Method.GET:
                connection.setRequestMethod("GET");
                break;
            case Request.Method.POST:
                connection.setRequestMethod("POST");
                addBodyIfExists(connection, request);
                break;
        }
    }

    /** 添加POST请求参数到HttpURLConnection中. */
    private static void addBodyIfExists(HttpURLConnection connection, Request<?> request)
            throws AuthFailureError, IOException {
        byte[] body = request.getBody();
        if (body != null) {
            connection.setDoOutput(true);
            connection.addRequestProperty(HEADER_CONTENT_TYPE, request.getBodyContentType());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.write(body);
            out.flush();
        }
    }

    /** 判断当前request请求结果是否有响应体. */
    private boolean hasResponseBody(int requestMethod, int responseCode) {
        return requestMethod != Request.Method.HEAD
                && !(HttpStatus.SC_CONTINUE <= responseCode && responseCode <= HttpStatus.SC_OK)
                && responseCode != HttpStatus.SC_NO_CONTENT
                && responseCode != HttpStatus.SC_NOT_MODIFIED;
    }

    /** 保存Http Body. */
    private HttpEntity entityFromConnection(HttpURLConnection connection) {
        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException ioe) {
            inputStream = connection.getErrorStream();
        }
        entity.setContent(inputStream);
        entity.setContentLength(connection.getContentLength());
        entity.setContentEncoding(connection.getContentEncoding());
        entity.setContentType(connection.getContentType());

        return entity;
    }
}
```

当用户new出HurlStack对象,调用它的performRequest方法,即可以发出HTTP请求,并获取HTTP请求结果.
但是,Android主线程中是不允许进行耗时操作的,所以Volley实现了并发访问HurlStack的performRequest的方法.
至于HurlStack的并发访问,就需要看NetworkDispatcher的实现.

## NetworkDispatcher.java

NetworkDispatcher是一个线程,用来调度处理网络请求.启动后会不断从网络请求队列中取请求处理,队列为空则等待,请求处理结束则将结果传递给ResponseDelivery去执行后续处理,并判断结果是否要进行缓存.
NetworkDispatcher的执行流程图如下:
![NetworkDispatcher](https://github.com/wangzhengyi/Volley/raw/master/picture/NetworkDispatcher.png)

NetworkDispatcher中文注释代码如下：
```java
/** 调度网络请求线程. */
public class NetworkDispatcher extends Thread{
    /** 网络请求队列. */
    private final BlockingQueue<Request<?>> mQueue;

    /** 封装了HurlStack的网络类,其performRequest方法是单个request请求真正执行的地方. */
    private final Network mNetwork;

    /** 缓存类,存储请求结果的缓存. */
    private final Cache mCache;

    /** 请求结果传递类. */
    private final ResponseDelivery mDelivery;

    /** 暂停线程的标志位,替换Thread自身的stop方法. */
    private volatile boolean mQuit = false;

    /** 构造网络请求调度线程类. */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
                             Network network, Cache cache, ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /** 强制停止当前调度线程. */
    public void quit() {
        mQuit = true;
        interrupt();
    }

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

                // 真正执行网络请求的地方.
                NetworkResponse networkResponse = mNetwork.performRequest(request);

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // 在当前线程中解析网络结果.
                // 不同的Request实现的parseNetworkResponse是不同的(例如StringRequest和JsonRequest).
                Response<?> response = request.parseNetworkResponse(networkResponse);

                //
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                }

                // 将网络请求结果进行传递.
                // ResponseDelivery调用顺序如下:
                // ResponseDelivery.postResponse==>ResponseDeliveryRunnable[Runnable]->run
                // ==>Request->deliverResponse==>用户设置的Listener回调接口
                request.markDelivered();
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
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

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }

    private void addTrafficStatsTag(Request<?> request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }
}
```
这里还有一点需要说明,NetworkDispatcher真正执行Http request请求时,并不是直接使用HurlStack类的performRequest方法,而是又对其进行了一个封装,封装成了Network类.

## Network.java

Network.java的源码如下：
```java
/** 网络接口,处理网络请求 */
public interface Network {
    NetworkResponse performRequest(Request<?> request) throws VolleyError;
}
```
可以看到,Network有一个子类需要实现的方法,和HurlStack的具体执行HTTP请求的方法的名称是一样的.那为什么Volley要多此一举对HurlStack进行进一步封装呢？
> 1. 这是因为Volley向下兼容到Android2.3之下的版本,而Android2.3以下的版本构造Http请求时推荐使用的是HttpClient类,所以这里Volley做了一个适配器模式的封装.也就是说,HurlStack类只需要负责对HttpURLConnection进行封装,HttpClientStack只需要对HttpClient类进行封装.
> 2. 封装更多的处理操作.包括：缓存新鲜度验证、超时重试等.

至于Network接口的具体实现类是BasicNetwork类,其注释源码如下：
```java
/** Volley默认的网络接口实现类. */
public class BasicNetwork implements Network {
    /** 网络请求真正实现类. */
    private final HttpStack mHttpStack;

    public BasicNetwork(HttpStack httpStack) {
        mHttpStack = httpStack;
    }

    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {
        long requestStart = SystemClock.elapsedRealtime();
        while (true) {
            HttpResponse httpResponse = null;
            byte[] responseContents = null;
            Map<String, String> responseHeaders = Collections.emptyMap();
            try {
                // 构造Cache的HTTP headers,主要是添加If-None-Match和If-Modified-Since两个字段
                // 当客户端发送的是一个条件验证请求时,服务器可能返回304状态码.
                // If-Modified-Since：代表服务器上次修改是的日期值.
                // If-None-Match：服务器上次返回的ETag响应头的值.
                Map<String, String> headers = new HashMap<String, String>();
                addCacheHeaders(headers, request.getCacheEntry());

                // 调用HurlStack的performRequest方法执行网络请求, 并将请求结果存入httpResponse变量中
                httpResponse = mHttpStack.performRequest(request, headers);

                StatusLine statusLine = httpResponse.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                responseHeaders = convertHeaders(httpResponse.getAllHeaders());

                // 当服务端返回304状态码时,直接将Volley缓存中结果返回
                if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                    Cache.Entry entry = request.getCacheEntry();
                    if (entry == null) {
                        return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, null,
                                responseHeaders, true,
                                SystemClock.elapsedRealtime() - requestStart);
                    }

                    // A HTTP 304 response dose not have all header filed. We
                    // have to use the header fields from the cache entry plus
                    // the new ones from the response.
                    entry.responseHeaders.putAll(responseHeaders);
                    return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, entry.data,
                            entry.responseHeaders, true,
                            SystemClock.elapsedRealtime() - requestStart);
                }

                // Some responses such as 204s do not have content. We mush check
                if (httpResponse.getEntity() != null) {
                    responseContents = entityToBytes(httpResponse.getEntity());
                } else {
                    responseContents = new byte[0];
                }

                if (statusCode < 200 || statusCode > 299) {
                    throw new IOException();
                }

                return new NetworkResponse(statusCode, responseContents, responseHeaders, false,
                        SystemClock.elapsedRealtime() - requestStart);
            } catch (SocketTimeoutException e) {
                // 捕获各种异常,进行重试操作.
                attemptRetryOnException("socket", request, new TimeoutError());
            } catch (ConnectTimeoutException E) {
                attemptRetryOnException("connection", request, new TimeoutError());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Bad URL " + request.getUrl(), e);
            } catch (IOException e) {
                int statusCode;
                if (httpResponse != null) {
                    statusCode = httpResponse.getStatusLine().getStatusCode();
                } else {
                    throw new NoConnctionError(e);
                }
                NetworkResponse networkResponse;
                if (responseContents != null) {
                    networkResponse = new NetworkResponse(statusCode, responseContents,
                            responseHeaders, false, SystemClock.elapsedRealtime() - requestStart);
                    if (statusCode == HttpStatus.SC_UNAUTHORIZED ||
                            statusCode == HttpStatus.SC_FORBIDDEN) {
                        attemptRetryOnException("auth",
                                request, new AuthFailureError(networkResponse));
                    } else if (statusCode >= 400 && statusCode <= 499) {
                        throw new ClientError(networkResponse);
                    } else if (statusCode >= 500 && statusCode <= 599) {
                        if (request.shouldRetryServerErrors()) {
                            attemptRetryOnException("server",
                                    request, new ServerError(networkResponse));
                        } else {
                            throw new ServerError(networkResponse);
                        }
                    } else {
                        // 3xx?
                        throw new ServerError(networkResponse);
                    }
                } else {
                    attemptRetryOnException("network", request, new NetworkError());
                }
            }
        }
    }

    private void addCacheHeaders(Map<String, String> headers, Cache.Entry entry) {
        if (entry == null) {
            return;
        }

        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.lastModified > 0) {
            Date refTime = new Date(entry.lastModified);
            headers.put("If-modified-Since", DateUtils.formatDate(refTime));
        }
    }

    private static Map<String, String> convertHeaders(Header[] headers) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : headers) {
            result.put(header.getName(), header.getValue());
        }
        return result;
    }

    /**
     * 将服务器返回的InputStream输入流转换成byte数组.
     * 这个函数让我实现的话,我会使用StringBuffer来替换ByteArrayOutputStream来实现字符串拼接.
     */
    private byte[] entityToBytes(HttpEntity entity) throws IOException, ServerError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        byte[] buffer = new byte[1024];

        try {
            InputStream in = entity.getContent();
            if (in == null) {
                throw new ServerError();
            }
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } finally {
            try {
                entity.consumeContent();
            } catch (IOException e){
                e.printStackTrace();
            }
            bytes.close();
        }
    }

    private void attemptRetryOnException(String logPrefix, Request<?> request,
                                         VolleyError exception) throws VolleyError{
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        retryPolicy.retry(exception);
        Log.e("Volley", String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
    }
}
```
## RequestQueue.java

RequestQueue是Volley框架的核心类,用户在使用Volley时,就是将一个Request加入到RequestQueue来完成请求操作的.所以,RequestQueue既是request的存储仓库,也是NetworkDispatcher的调度核心.
由于RequestQueue其中还包括Volley的缓存机制,我们稍后会对缓存机制进行讲解,所以这里只看跟NetworkDispatcher调度相关的源码.

RequestQueue类的注释代码如下：
```java
/** Request请求调度队列. */
@SuppressWarnings("unused")
public class RequestQueue {
    /**
     * Callback interface for completed requests.
     */
    public interface RequestFinishedListener<T> {
        void onRequestFinished(Request<T> request);
    }

    /** 为每一个request申请独立的序列号. */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /** 保存所有被加入到当前队列的request集合. */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /**
     * The cache triage queue.
     */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** 存储需要进行网络通信的request的存储队列. */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
            new PriorityBlockingQueue<Request<?>>();

    /** RequestQueue默认开启的网络线程的数量. */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /**
     * Cache interface for retrieving and storing responses.
     */
    private final Cache mCache;

    /** 封装request网络请求的Network类. */
    private final Network mNetwork;

    /** 网络请求传输结果实现类. */
    private final ResponseDelivery mDelivery;

    /** 网络请求线程数组. */
    private NetworkDispatcher[] mDispatchers;

    /** 缓存线程 */
    private CacheDispatcher mCacheDispatcher;

    private List<RequestFinishedListener> mFinishedListeners =
            new ArrayList<RequestFinishedListener>();

    public RequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    public RequestQueue(Cache cache, Network network, int threadPoolSize) {
        this(cache, network, threadPoolSize,
                new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Creates the worker pool.
     * @param cache A cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     * @param delivery A ResponseDelivery interface for posting responses and errors
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize,
                        ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new NetworkDispatcher[threadPoolSize];
        mDelivery = delivery;
    }

    /** 开启request的缓存线程和多个网络请求线程 */
    public void start() {
        // 关闭所有正在运行的缓存线程和网络请求线程.
        stop();

        // 默认开启DEFAULT_NETWORK_THREAD_POOL_SIZE(4)个线程来执行request网络请求.
        for (int i = 0; i < mDispatchers.length; i ++) {
            // 将NetworkDispatcher线程与mNetworkQueue这个队列进行绑定.
            // NetworkDispatcher会使用生产者-消费者模型从mNetworkQueue获取request请求,并执行.
            NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }
    }

    /** 停止所有的缓存线程和网络请求线程. */
    private void stop() {
        for (NetworkDispatcher dispatcher : mDispatchers) {
            if (dispatcher != null) {
                dispatcher.quit();
            }
        }
    }

    /** 将Request请求加入到调度队列中. */
    public <T> Request<?> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        // 分配request唯一的序列号.
        request.setSequence(getSequenceNumber());

        // request不允许缓存,则直接将request加入到mNetworkQueue当中
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
            return request;
        }
    }

    /** 提供request请求序列号. */
    private int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }
}
```
RequestQueue在构造函数中,会默认生成4个NetworkDispatcher线程,并且将NetworkDispatcher线程与mNetworkQueue进行绑定,然后start NetworkDispatcher执行网络请求操作.

## 异步

前面已经详细讲解了一个Request是如何被并发处理的,那现在回到我们的3-4问题,子线程中并发处理的结果如何异步传递给用户设置的Listener回调接口.
从NetworkDispatcher最后传递结果的代码：
```java
request.markDelivered();
mDelivery.postResponse(request, response);
```
我们就可以看出,异步回调是通过ResponseDelivery类实现的.

### ResponseDelivery.java

ResponseDelivery的中文注释源码如下：
```java
/** 网络结果分发接口类. */
public interface ResponseDelivery {
    /**
     * Parses a response from the network or cache and delivers it.
     */
    void postResponse(Request<?> request, Response<?> response);

    /**
     * Parses a response from the network or cache and delivers it.
     */
    void postResponse(Request<?> request, Response<?> response, Runnable runnable);

    /**
     * Posts an error for the given request.
     */
    void postError(Request<?> request, VolleyError error);
}
```

在RequestQueue中,ResponseDelivery的实现类为ExecutorDelivery类.

### ExecutorDelivery

众所周知,Android中实现异步肯定是需要用到Handler、Looper和Message机制的.ExecutorDelivery的实现异步的机制也是居于Handler机制.
我们先来看一下,RequestQueue中ExecutorDelivery是如何被构造的:
```java
ResponseDelivery delivery = new ExecutorDelivery(new Handler(Looper.getMainLooper()));
```
可以看到,RequestQueue将绑定主线程Looper对象的Handler对象传递给了ExecutorDelivery,这样我们通过handler发送的消息其实都是在主线程进行处理了.
ExecutorDelivery的中文注释源码如下:
```java
/**
 * 网络请求结果传递类.(实现异步功能,主线程传递数据给子线程)
 */
@SuppressWarnings("unused")
public class ExecutorDelivery implements ResponseDelivery {
    /**
     * 构造执行已提交的Runnable任务对象.
     */
    private final Executor mResponsePoster;

    public ExecutorDelivery(final Handler handler) {
        mResponsePoster = new Executor() {
            @Override
            public void execute(@NonNull Runnable command) {
                // 所有的Runnable通过绑定主线程Looper的Handler对象最终在主线程执行.
                handler.post(command);
            }
        };
    }

    public ExecutorDelivery(Executor executor) {
        mResponsePoster = executor;
    }

    @Override
    public void postResponse(Request<?> request, Response<?> response) {
        postResponse(request, response, null);
    }

    @Override
    public void postResponse(Request<?> request, Response<?> response, Runnable runnable) {
        request.markDelivered();
        mResponsePoster.execute(
                new ResponseDeliveryRunnable(request, response, runnable)
        );
    }

    @Override
    public void postError(Request<?> request, VolleyError error) {
        Response<?> response = Response.error(error);
        mResponsePoster.execute(new ResponseDeliveryRunnable(request, response, null));
    }

    /** 在主线程执行的Runnable类 */
    @SuppressWarnings("unchecked")
    private class ResponseDeliveryRunnable implements Runnable {
        private final Request mRequest;
        private final Response mResponse;
        private final Runnable mRunnable;

        public ResponseDeliveryRunnable(Request request, Response response, Runnable runnable) {
            mRequest = request;
            mResponse = response;
            mRunnable = runnable;
        }

        @Override
        public void run() {
            // 如果request被取消,则不回调用户设置的Listener接口
            if (mRequest.isCanceled()) {
                mRequest.finish("canceled-at-delivery");
                return;
            }

            // 通过response状态标志,来判断是回调用户设置的Listener接口还是ErrorListener接口
            if (mResponse.isSuccess()) {
                mRequest.deliverResponse(mResponse.result);
            } else {
                mRequest.deliverError(mResponse.error);
            }

            if (mResponse.intermediate) {
                mRequest.addMarker("intermediate-response");
            } else {
                // 通知RequestQueue终止该Request请求
                mRequest.finish("done");
            }

            if (mRunnable != null) {
                mRunnable.run();
            }
        }
    }
}
```

# 缓存机制

前面讲解了并发和异步的实现,接下来,我们就来看一下Volley的缓存机制.再学习Volley缓存实现方案之前,我们先来感受一下Google I/O大会上Volley官方一张宣传图片：
![Volley](https://github.com/wangzhengyi/Volley/raw/master/picture/volley.jpg)

这张图片非常形象的表达了Volley适合频繁的网络请求.接下来,我们就从Volley的缓存系统入手,介绍一下为什么Volley适合频繁的网络请求.

## Cache.java

既然要缓存Request请求,那我们首先就需要抽象出缓存对象.而Cache类就是对缓存对象的抽象描述:
```java
/** 缓存内存的抽象接口 */
@SuppressWarnings("unused")
public interface Cache {
    /** 通过key获取请求的缓存实体. */
    Entry get(String key);

    /** 存入一个请求的缓存实体. */
    void put(String key, Entry entry);

    void initialize();

    void invalidate(String key, boolean fullExpire);

    /** 移除指定的缓存实体. */
    void remove(String key);

    /** 清空缓存. */
    void clear();

    /** 真正HTTP请求缓存实体类. */
    class Entry {
        /** HTTP响应体. */
        public byte[] data;

        /** HTTP响应首部中用于缓存新鲜度验证的ETag. */
        public String etag;

        /** HTTP响应时间. */
        public long serverDate;

        /** 缓存内容最后一次修改的时间. */
        public long lastModified;

        /** Request的缓存过期时间. */
        public long ttl;

        /** Request的缓存新鲜时间. */
        public long softTtl;

        /** HTTP响应Headers. */
        public Map<String, String> responseHeaders = Collections.emptyMap();

        /** 判断缓存内容是否过期. */
        public boolean isExpired() {
            return this.ttl < System.currentTimeMillis();
        }

        /** 判断缓存是否新鲜,不新鲜的缓存需要发到服务端做新鲜度的检测. */
        public boolean refreshNeeded() {
            return this.softTtl < System.currentTimeMillis();
        }
    }
}
```
Cache接口定义规定了缓存实体的内容和其需要实现的方法.在RequestQueue中,Cache的实现类是DiskBasedCache类.

## DiskBasedCache.java

DiskBasedCache类的主要作用是：实现了基于Disk的对象存储类,并提供替换策略.代码比较简单,中文注释的代码如下:
```java
/** 基于Disk的缓存实现类. */
@SuppressWarnings("ResultOfMethodCallIgnored")
public class DiskBasedCache implements Cache {
    /** 默认硬盘最大的缓存空间(5M). */
    private static final int DEFAULT_DISK_USAGE_BYTES = 5 * 1024 * 1024;

    /** 标记缓存起始的MAGIC_NUMBER. */
    private static final int CACHE_MAGIC = 0x20150306;

    /**
     * High water mark percentage for the cache.
     */
    private static final float HYSTERESIS_FACTOR = 0.9f;

    /**
     * Map of the Key, CacheHeaders pairs.
     */
    private final Map<String, CacheHeader> mEntries =
            new LinkedHashMap<String, CacheHeader>(16, 0.75f, true);

    /** 目前使用的缓存字节数. */
    private long mTotalSize = 0;

    /** 硬盘缓存目录. */
    private final File mRootDirectory;

    /** 硬盘缓存最大容量(默认5M). */
    private final int mMaxCacheSizeInBytes;

    public DiskBasedCache(File rootDirectory) {
        this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
    }

    public DiskBasedCache(File rootDirectory, int maxCacheSizeInBytes) {
        mRootDirectory = rootDirectory;
        mMaxCacheSizeInBytes = maxCacheSizeInBytes;
    }

    /** 清空缓存内容. */
    @Override
    public synchronized void clear() {
        File[] files = mRootDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        mEntries.clear();
        mTotalSize = 0;
    }

    /** 从Disk中根据key获取并构造HTTP响应体Cache.Entry. */
    @Override
    public synchronized Entry get(String key) {
        CacheHeader entry = mEntries.get(key);
        if (entry == null) {
            return null;
        }

        File file = getFileForKey(key);
        CountingInputStream cis = null;
        try {
            cis = new CountingInputStream(new BufferedInputStream(new FileInputStream(file)));
            // 读完CacheHeader部分,并通过CountingInputStream的bytesRead成员记录已经读取的字节数.
            CacheHeader.readHeader(cis);
            // 读取缓存文件存储的HTTP响应体内容.
            byte[] data = streamToBytes(cis, (int)(file.length() - cis.bytesRead));
            return entry.toCacheEntry(data);
        } catch (IOException e) {
            remove(key);
            return null;
        } finally {
            if (cis != null) {
                try {
                    cis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 初始化Disk缓存系统.
     * 作用是：遍历Disk缓存系统,将缓存文件中的CacheHeader和key存储到Map对象中. */
    @Override
    public void initialize() {
        if (!mRootDirectory.exists() && !mRootDirectory.mkdirs()) {
            return;
        }

        File[] files = mRootDirectory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            BufferedInputStream fis = null;
            try {
                fis = new BufferedInputStream(new FileInputStream(file));
                CacheHeader entry = CacheHeader.readHeader(fis);
                entry.size = file.length();
                putEntry(entry.key, entry);
            }catch (IOException e) {
                file.delete();
                e.printStackTrace();
            }finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    /** 标记指定的cache过期. */
    @Override
    public synchronized void invalidate(String key, boolean fullExpire) {
        Entry entry = get(key);
        if (entry != null) {
            entry.softTtl = 0;
            if (fullExpire) {
                entry.ttl = 0;
            }
            put(key, entry);
        }
    }

    /** 将Cache.Entry存入到指定的缓存文件中. 并在Map中记录<key,CacheHeader>. */
    @Override
    public synchronized void put(String key, Entry entry) {
        pruneIfNeeded(entry.data.length);
        File file = getFileForKey(key);
        try {
            BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(file));
            CacheHeader e = new CacheHeader(key, entry);
            boolean success = e.writeHeader(fos);
            if (!success) {
                fos.close();
                throw new IOException();
            }
            fos.write(entry.data);
            fos.close();
            putEntry(key, e);
            return;
        } catch (IOException e) {
            e.printStackTrace();
        }
        file.delete();
    }

    /** Disk缓存替换更新机制. */
    private void pruneIfNeeded(int neededSpace) {
        if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes) {
            return;
        }

        Iterator<Map.Entry<String, CacheHeader>> iterator = mEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheHeader> entry = iterator.next();
            CacheHeader e = entry.getValue();
            boolean deleted = getFileForKey(e.key).delete();
            if (deleted) {
                mTotalSize -= e.size;
            }
            iterator.remove();

            if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes * HYSTERESIS_FACTOR) {
                break;
            }
        }
    }

    /** 获取存储当前key对应value的文件句柄. */
    private File getFileForKey(String key) {
        return new File(mRootDirectory, getFilenameForKey(key));
    }

    /** 根据key的hash值生成对应的存储文件名称. */
    private String getFilenameForKey(String key) {
        int firstHalfLength = key.length() / 2;
        String localFilename = String.valueOf(key.substring(0, firstHalfLength).hashCode());
        localFilename += String.valueOf(key.substring(firstHalfLength).hashCode());
        return localFilename;
    }

    /** 将key和CacheHeader存入到Map对象中.并更新当前占用的总字节数. */
    private void putEntry(String key, CacheHeader entry) {
        if (!mEntries.containsKey(key)) {
            mTotalSize += entry.size;
        } else {
            CacheHeader oldEntry = mEntries.get(key);
            mTotalSize += (entry.size - oldEntry.size);
        }

        mEntries.put(key, entry);
    }

    @Override
    public synchronized void remove(String key) {
        boolean deleted = getFileForKey(key).delete();
        removeEntry(key);
        if (!deleted) {
            Log.e("Volley", "没能删除key=" + key + ", 文件名=" + getFilenameForKey(key) + "缓存.");
        }
    }

    /** 从Map对象中删除key对应的键值对. */
    private void removeEntry(String key) {
        CacheHeader entry = mEntries.get(key);
        if (entry != null) {
            mTotalSize -= entry.size;
            mEntries.remove(key);
        }
    }

    /** 抽象出来的缓存文件摘要信息.
     * 与Cache.Entry类几乎相同,但是只存储了响应体的大小,没保存响应体的内容.
     */
    static class CacheHeader {
        /** HTTP响应头(header)和响应体(body)的整体大小.也就是Disk缓存系统中对应缓存文件的大小. */
        public long size;

        public String key;

        /** HTTP响应首部中用于缓存新鲜度验证的ETag. */
        public String etag;

        /** HTTP响应时间. */
        public long serverDate;

        /** 缓存内容最后一次修改的时间. */
        public long lastModified;

        /** Request的http缓存过期时间. */
        public long ttl;

        /** Request的http缓存新鲜时间. */
        public long softTtl;

        /** HTTP的响应headers. */
        public Map<String, String> responseHeaders;

        private CacheHeader(){}

        /**
         * Instantiates a new CacheHeader object
         * @param key The key that indentifies the cache entry
         * @param entry The cache entry
         */
        public CacheHeader(String key, Entry entry) {
            this.key = key;
            this.size = entry.data.length;
            this.etag = entry.etag;
            this.serverDate = entry.serverDate;
            this.lastModified = entry.lastModified;
            this.ttl = entry.ttl;
            this.softTtl = entry.softTtl;
            this.responseHeaders = entry.responseHeaders;
        }

        /** 从InputStream中构造CacheHeader对象.其实就是实现对象的反序列化. */
        public static CacheHeader readHeader(InputStream is) throws IOException {
            CacheHeader entry = new CacheHeader();
            // 以CACHE_NUMBER作为读取一个对象的开始
            int magic = readInt(is);
            if (magic != CACHE_MAGIC) {
                throw new IOException();
            }
            entry.key = readString(is);
            entry.etag = readString(is);
            if (entry.etag.equals("")) {
                entry.etag = null;
            }
            entry.serverDate = readLong(is);
            entry.lastModified = readLong(is);
            entry.ttl = readLong(is);
            entry.softTtl = readLong(is);
            entry.responseHeaders = readStringStringMap(is);

            return entry;
        }

        /** 通过传入的data数组构造一个Cache.Entry对象. */
        public Entry toCacheEntry(byte[] data) {
            Entry e = new Entry();
            e.data = data;
            e.etag = etag;
            e.serverDate = serverDate;
            e.lastModified = lastModified;
            e.ttl = ttl;
            e.softTtl = softTtl;
            e.responseHeaders = responseHeaders;
            return e;
        }

        /** 将CacheHeader对象序列化. */
        public boolean writeHeader(OutputStream os) {
            try {
                writeInt(os, CACHE_MAGIC);
                writeString(os, key);
                writeString(os, etag == null ? "" : etag);
                writeLong(os, serverDate);
                writeLong(os, lastModified);
                writeLong(os, ttl);
                writeLong(os, softTtl);
                writeStringStringMap(responseHeaders, os);
                os.flush();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    static void writeString(OutputStream os, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        writeLong(os, b.length);
        os.write(b, 0, b.length);
    }

    /** InputStream中读取字符串的方法是:
     *  1. 读取字符串长度n.
     *  2. 读取n个字节保存在字符数组中.
     *  3. 将字符数组转换成字符串.
     */
    private static String readString(InputStream is) throws IOException {
        int n = (int)readLong(is);
        byte[] b = streamToBytes(is, n);
        return new String(b, "UTF-8");
    }

    private static byte[] streamToBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int count;
        int pos = 0;
        // 这里调用的是InputStream的read(byte[] b, int off, int len)方法.作用是：
        // 从输入流中最多读取len个数据字节到byte数组中,并将读取的第一个字节存储在byte[pos]位置上.
        // 由于,每次读取的字节数count可能小于len,所以需要循环读取.
        while (pos < length && ((count = in.read(bytes, pos, length - pos)) != -1)) {
            pos += count;
        }
        if (pos != length) {
            throw new IOException("Expected " + length + " bytes, read " + pos + " bytes");
        }
        return bytes;
    }

    static void writeLong(OutputStream os, long n) throws IOException {
        os.write((byte)(n));
        os.write((byte)(n >>> 8));
        os.write((byte)(n >>> 16));
        os.write((byte)(n >>> 24));
        os.write((byte)(n >>> 32));
        os.write((byte)(n >>> 40));
        os.write((byte)(n >>> 48));
        os.write((byte)(n >>> 56));
    }

    private static long readLong(InputStream is) throws IOException {
        long n = 0;
        n |= ((read(is) & 0xFFL));
        n |= ((read(is) & 0xFFL) << 8);
        n |= ((read(is) & 0xFFL) << 16);
        n |= ((read(is) & 0xFFL) << 24);
        n |= ((read(is) & 0xFFL) << 32);
        n |= ((read(is) & 0xFFL) << 40);
        n |= ((read(is) & 0xFFL) << 48);
        n |= ((read(is) & 0xFFL) << 56);
        return n;
    }

    private static void writeInt(OutputStream os, int n) throws IOException {
        os.write((n) & 0xff);
        os.write((n >> 8) & 0xff);
        os.write((n >> 16) & 0xff);
        os.write((n >> 24) & 0xff);
    }

    private static int readInt(InputStream is) throws IOException {
        int n = 0;
        n |= (read(is));
        n |= (read(is) << 8);
        n |= (read(is) << 16);
        n |= (read(is) << 24);

        return n;
    }

    private static int read(InputStream is) throws IOException {
        int b = is.read();
        if (b == -1) {
            throw new EOFException();
        }
        return b;
    }

    static void writeStringStringMap(Map<String, String> map, OutputStream os) throws IOException {
        if (map != null) {
            writeInt(os, map.size());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writeString(os, entry.getKey());
                writeString(os, entry.getValue());
            }
        } else {
            writeInt(os, 0);
        }
    }


    /**
     * 从输入流中读取Map对象.读取方法如下:
     * 1. 读取Map对象的数量size.
     * 2. 然后循环读取size次,每次先读一个String作为key,再读一个String作为Value.
     */
    private static Map<String, String> readStringStringMap(InputStream is) throws IOException {
        int size = readInt(is);
        Map<String, String> result = (size == 0) ? Collections.<String, String>emptyMap()
                : new HashMap<String, String>(size);
        for (int i = 0; i < size; i ++) {
            String key = readString(is).intern();
            String value = readString(is).intern();
            result.put(key, value);
        }

        return result;
    }

    /** 继承FilterInputStream,增加记录读取总字节数的功能. */
    private static class CountingInputStream extends FilterInputStream{
        private int bytesRead = 0;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                bytesRead ++;
            }
            return result;
        }

        @Override
        public int read(@NonNull byte[] buffer, int byteOffset, int byteCount) throws IOException {
            int result = super.read(buffer, byteOffset, byteCount);
            if (result != -1) {
                bytesRead += result;
            }
            return result;
        }
    }
}
```

有了DiskBasedCache类,我们就可以看一下Volley是如何对缓存进行存储的了.
回到RequestQueue类中,我们看一下跟缓存相关的代码实现.

## CacheDispatcher.java

在RequestQueue的start方法里,有如下代码:
```java
    public void start() {
        // 关闭所有正在运行的缓存线程和网络请求线程.
        stop();
        // 开启缓存线程.
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();
    }
```
从上面代码,可以看到,Volley是启动了一个线程来实现缓存功能.我们再学习CacheDispatcherd的实现之前,可以来思考一下,如果让我们来实现CacheDispatcher,我们的思路是什么呢?
我的思路如下：

1. 在当前DiskBasedCache缓存系统中,查找是否已经缓存过该Request.
2. 如果已经缓存过,且没有过期,则直接返回缓存系统中的内容.
3. 如果没有缓存,或者缓存已经过期,则走网络请求,并且网络请求之后的结果记录到DiskBasedCache缓存系统中.

接下来,我们来看一下CacheDispatcher的源码,看看它是不是这么操作的：
```java
/** 线程,用来调度可以走缓存的Request请求. */
public class CacheDispatcher extends Thread{
    /** 可以走Disk缓存的request请求队列. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** 需要走网络的request请求队列. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** DiskBasedCache缓存实现类. */
    private final Cache mCache;

    /** 网络请求结果传递类. */
    private final ResponseDelivery mDelivery;

    /** 用来停止线程的标志位. */
    private volatile boolean mQuit = false;

    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /** 通过标记位机制强行停止CacheDispatcher线程. */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // 初始化DiskBasedCache缓存类.
        mCache.initialize();

        while (true) {
            try {
                // 从缓存队列中获取request请求.(缓存队列实现了生产者-消费者队列模型)
                final Request<?> request = mCacheQueue.take();

                // 判断请求是否被取消
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // 从缓存系统中获取request请求结果Cache.Entry.
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    // 如果缓存系统中没有该缓存请求,则将request加入到网络请求队列中.
                    // 由于NetworkQueue跟NetworkDispatcher线程关联,并且也是生产者-消费者队列,
                    // 所以这里添加request请求就相当于将request执行网络请求.
                    mNetworkQueue.put(request);
                    continue;
                }

                // 判断缓存结果是否过期.
                if (entry.isExpired()) {
                    request.setCacheEntry(entry);
                    // 过期的缓存需要重新执行request请求.
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                Response<?> response = request.parseNetworkResponse(new NetworkResponse(entry.data,
                        entry.responseHeaders));

                // 判断Request请求结果是否新鲜?
                if (!entry.refreshNeeded()) {
                    // 请求结果新鲜,则直接将请求结果分发,进行异步回调用户接口.
                    mDelivery.postResponse(request, response);
                } else {
                    // 请求结果不新鲜,但是同样还是将缓存结果返回给用户,并且同时执行网络请求,刷新Request网络结果缓存.
                    request.setCacheEntry(entry);

                    response.intermediate = true;

                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                if (mQuit) {
                    return;
                }
            }
        }
    }
}
```

从源码中可以看出,CacheDispatcher的执行流程和我们设想的基本一致,但是当缓存内容不存在时,如何将网络拉取的最新内容存储在Cache缓存中却没有在CacheDispatcher类中体现.这是因为：
NetworkDispatcher代码中,所有进行网络请求的request默认都会进行缓存存储,所以这里CacheDispatcher就不需要重复操作了.

之前介绍RequestQueue的时候,我们只介绍了不进行缓存的Request请求是如何被调度的,那这里我们继续看一下,默认情况下,Request都是需要进行缓存的,那缓存是如何调度的呢?
来看一下RequestQueue完整的add方法源码:
```java
    /** 将Request请求加入到调度队列中. */
    public <T> Request<?> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        // 分配request唯一的序列号.
        request.setSequence(getSequenceNumber());

        // request不允许缓存,则直接将request加入到mNetworkQueue当中
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
            return request;
        }

        // Insert request into stage if there's already a request with the same cache key in flight.
        synchronized (mWaitingRequests) {
            String cacheKey = request.getCacheKey();
            if (mWaitingRequests.containsKey(cacheKey)) {
                // 表示RequestQueue正在调度过该Request,因为后续相同的Request先入队列,排队等待执行.
                Queue<Request<?>> stageRequests = mWaitingRequests.get(cacheKey);
                if (stageRequests == null) {
                    stageRequests = new LinkedList<Request<?>>();
                }
                stageRequests.add(request);
                mWaitingRequests.put(cacheKey, stageRequests);
            } else {
                // 将Request加入到等待Map中,表示Request正在执行.
                mWaitingRequests.put(cacheKey, null);
                mCacheQueue.add(request);
            }
            return request;
        }
    }
```
add方法之前也介绍过,这里要特殊强调一下mWaitingRequests的妙用.
在应用的网络请求过程中,有时可能由于多线程或者后台Service更新等机制,导致同一个Url的Request被同一时间多次请求.这时,RequestQueue通过mWaitingRequests这个Map很好的控制了这种情况.
通过mWaitingRequests,同一时间相同Url的Request只能有一个再执行.我想大家可能会有疑问(至少我看这部分代码时存在这个疑问):从代码逻辑中,可以看出,相同的Request被加入到Map该url对应的队列中,但是后续什么时候执行呢?add方法中并没有体现.
那既然同一时间相同url的Request只能有一个在执行,那mWaitingRequests中url对应队列的Request当然是在上一个Request执行完毕后才会执行.Request执行完毕后会调用自身的finish方法.
Request的finish调用时机肯定是ExecutorDelivery类将结果回调给用户接口时调用的,具体代码大家可以翻看之前的ExecutorDelivery类源码.
Request的finish方法源码如下:
```java
    /** 用于告知请求队列当前request已经结束. */
    void finish(final String tag) {
        if (mRequestQueue != null) {
            mRequestQueue.finish(this);
        }
    }
```
可以看到,Request的finish方法其实是通知RequestQueue,调用RequestQueue的finish方法来结束自己.继续跟踪RequestQueue的finish方法:
```java
    /** 该方法的调用时机为:参数Request将请求结果回调给用户接口时,会调用该方法告知此Request已经结束. */
    <T> void finish(Request<T> request) {
        // 从正在执行的Request队列中删除指定的request.
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        // 观察者模式,通知Observer该request请求结束.
        synchronized (mFinishedListeners) {
            for (RequestFinishedListener<T> listener : mFinishedListeners) {
                listener.onRequestFinished(request);
            }
        }

        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                // 因为当前Request已经正常结束,而且该request是可以缓存的,所以这时需要直接把正在等待的所有相同
                // url的Request全部加入到缓存队列中,从缓存系统读取结果后回调用户接口.
                String cacheKey = request.getCacheKey();
                Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }
```
相信上面的注释足够让大家理解mWaitingRequests的妙用了.


# Volley框架概览

讲到这里,Volley的整体框架基本就算介绍完全了.相信坚持看到这里的同学,肯定对Volley框架也已经非常熟悉,这时候我们再来看一下Volley框架的整体架构,回顾一下之前所讲的知识:
![Volley_FRAME](https://github.com/wangzhengyi/Volley/raw/master/picture/volley_frame.png)


# 问答

欢迎大家提出跟Volley架构相关的问题，我会挑选出某个问题进行具体解答.

1. 为什么Volley适合频繁的网络请求，不适合文件上传等大数据请求呢？

> 答：Volley为什么适合频繁的网络请求，是因为:
  1. Volley有四个并发的线程,并有一个阻塞队列来对并发线程进行调度.
  2. Volley有自己的Disk缓存系统,相同url的Request再没过期前可以直接从Disk缓存系统中获取结果.
  3. Volley的RequestQueue类有一个mWaitingRequest的Map,用来存储相同url的request,key为url,value为request队列。保证同一时间相同url的request只有一个再执行,后续Request再第一个request结束后可直接从缓存系统中获取结果.
  为什么不适合文件上传,是因为文件上传这种操作都是唯一的，用不到缓存，而且4个线程的并发似乎也有点少.

