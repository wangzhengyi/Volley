# Volley HTTP 缓存规则

在介绍Volley的HTTP缓存机制之前,我们首先来看一下HTTP HEADER中和缓存有关的字段有：

| 规则    | 字段             | 示例值                         | 类型    | 作用                                          |
| ----   | ------           | -------                       | ------ | --------                                      |
| 新鲜度  | Expires          | Sat, 23 Jul 2016 03:34:17 GMT | 响应    | 告诉客户端在过期时间之前可以使用副本               |
|        | Cache-Control    | no-cache                      | 响应    | 告诉客户端忽略资源的缓存副本,强制每次请求都访问服务器 |
|        |                  | no-store                      | 响应    | 强制缓存在任何情况下都不要保留任何副本              |
|        |                  | must-revalidate               | 响应    | 表示必须进行新鲜度的再验证之后才能使用              |
|        |                  | max-age=[秒]                  | 响应    | 指明缓存副本的有效时长,从请求时间到到期时间的秒数     |
|        | Last-Modified    | Mon, 23 Jun 2014 08:43:26 GMT | 响应    | 告诉客户端当前资源的最后修改时间                   |
|        | If-Modified-Since| Mon, 23 Jun 2014 08:43:26 GMT | 请求    | 如果浏览器第一次请求时响应的Last-Modified非空,
                                                                       第二次请求同一资源时,会把它作为该项的值发送给服务器   |
| 校验值  | ETag             | 53a7e8ae-1f79                 | 响应    | 告知客户端当前资源在服务器的唯一标识                |
|        | If-None-Match    | 53a7e8ae-1f79                 | 请求    | 如果浏览器第一次请求时响应中ETag非空,第二次请求同一资源时,会把它作为该项的值发给服务器|

-------
## Volley的缓存机制

Volley的缓存机制在对HTTP RESPONSE的解析中能够明显的看出来:
```java
public static Cache.Entry parseCacheHeaders(NetworkResponse response) {
    long now = System.currentTimeMillis();

    Map<String, String> headers = response.headers;

    long serverDate = 0;
    long lastModified = 0;
    long serverExpires = 0;
    long softExpire = 0;
    long finalExpire = 0;
    long maxAge = 0;
    long staleWhileRevalidate = 0;
    boolean hasCacheControl = false;
    boolean mustRevalidate = false;

    String serverEtag;
    String headerValue;

    headerValue = headers.get("Date");
    if (headerValue != null) {
        serverDate = parseDateAsEpoch(headerValue);
    }

    // 获取响应体的Cache缓存策略.
    headerValue = headers.get("Cache-Control");
    if (headerValue != null) {
        hasCacheControl = true;
        String[] tokens = headerValue.split(",");
        for (String token : tokens) {
            token = token.trim();
            if (token.equals("no-cache") || token.equals("no-store")) {
                // no-cache|no-store代表服务器禁止客户端缓存,每次需要重新发送HTTP请求
                return null;
            } else if (token.startsWith("max-age=")) {
                // 获取缓存的有效时间
                try {
                    maxAge = Long.parseLong(token.substring(8));
                } catch (Exception e) {
                    maxAge = 0;
                }
            } else if (token.startsWith("stale-while-revalidate=")) {
                try {
                    staleWhileRevalidate = Long.parseLong(token.substring(23));
                } catch (Exception e) {
                    staleWhileRevalidate = 0;
                }
            } else if (token.equals("must-revalidate") || token.equals("proxy-revalidate")) {
                // 需要进行新鲜度验证
                mustRevalidate = true;
            }
        }
    }

    // 获取服务器资源的过期时间
    headerValue = headers.get("Expires");
    if (headerValue != null) {
        serverExpires = parseDateAsEpoch(headerValue);
    }

    // 获取服务器资源最后一次的修改时间
    headerValue = headers.get("Last-Modified");
    if (headerValue != null) {
        lastModified = parseDateAsEpoch(headerValue);
    }

    // 获取服务器资源标识
    serverEtag = headers.get("ETag");

    // 计算缓存的ttl和softTtl
    if (hasCacheControl) {
        softExpire = now + maxAge * 1000;
        finalExpire = mustRevalidate
                ? softExpire
                : softExpire + staleWhileRevalidate * 1000;
    } else if (serverDate > 0 && serverExpires >= serverDate) {
        // Default semantic for Expire header in HTTP specification is softExpire.
        softExpire = now + (serverExpires - serverDate);
        finalExpire = softExpire;
    }

    Cache.Entry entry = new Cache.Entry();
    entry.data = response.data;
    entry.etag = serverEtag;
    entry.softTtl = softExpire;
    entry.ttl = finalExpire;
    entry.serverDate = serverDate;
    entry.lastModified = lastModified;
    entry.responseHeaders = headers;

    return entry;
}
```

这个方法其实是实现了Volley的本地缓存的关键代码.

-------
# L2级硬盘缓存的实现和缓存替换机制

之前介绍了用户使用LruCache实现自定义的L1级缓存,而Volley本身利用了FIFO算法实现了L2级硬盘缓存.接下来,就详细介绍一下硬盘缓存的实现和缓存替换机制.
这里我们也是考虑如果自己实现硬盘缓存,需要实现哪几个步骤:

1. 抽象出存储实体类.
2. 定义并实现缓存替换算法.

-------
## 存储实体

