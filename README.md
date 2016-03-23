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
NetworkDispatcher中文注释代码如下：
```java

```


