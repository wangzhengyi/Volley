# Volley HTTP 缓存规则

在介绍Volley的HTTP缓存机制之前,我们首先来看一下HTTP HEADER中和缓存有关的字段有：

| 规则      | 字段             | 示例值                         | 类型      | 作用                                          |
| :----:   | :------:         | :-------:                     | :------: | :--------:                                    |
| 新鲜度    | Expires          | Sat, 23 Jul 2016 03:34:17 GMT | 响应    | 告诉客户端在过期时间之前可以使用副本               |
|          | Cache-Control    | no-cache                      | 响应    | 告诉客户端忽略资源的缓存副本,强制每次请求都访问服务器 |
|          |                  | no-store                      | 响应    | 强制缓存在任何情况下都不要保留任何副本              |
|          |                  | must-revalidate               | 响应    | 表示必须进行新鲜度的再验证之后才能使用              |
|          |                  | max-age=[秒]                  | 响应    | 指明缓存副本的有效时长,从请求时间到到期时间的秒数     |
|          | Last-Modified    | Mon, 23 Jun 2014 08:43:26 GMT | 响应    | 告诉客户端当前资源的最后修改时间                   |
|          | If-Modified-Since| Mon, 23 Jun 2014 08:43:26 GMT | 请求    | 如果浏览器第一次请求时响应的Last-Modified非空,第二次请求同一资源时,会把它作为该项的值发送给服务器   |
| 校验值    | ETag             | 53a7e8ae-1f79                 | 响应    | 告知客户端当前资源在服务器的唯一标识                |
|          | If-None-Match    | 53a7e8ae-1f79                 | 请求    | 如果浏览器第一次请求时响应中ETag非空,第二次请求同一资源时,会把它作为该项的值发给服务器|

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
2. 定义抽象存储接口,包括initialize,get,put,clear等具体缓存系统的操作.
3. 对象的序列化.

-------
## 存储实体

存储的实体肯定是响应的结果,响应结果分为响应头和响应体,抽象类代码如下所示:
```java
/** 真正HTTP请求缓存实体类. */
class Entry {
    /** HTTP响应Headers. */
    public Map<String, String> responseHeaders = Collections.emptyMap();

    /** HTTP响应体. */
    public byte[] data;

    /** 服务器资源标识ETag. */
    public String etag;

    /** HTTP响应时间. */
    public long serverDate;

    /** 缓存内容最后一次修改的时间. */
    public long lastModified;

    /** Request的缓存过期时间. */
    public long ttl;

    /** Request的缓存新鲜时间. */
    public long softTtl;

    /** 判断缓存内容是否过期. */
    public boolean isExpired() {
        return this.ttl < System.currentTimeMillis();
    }

    /** 判断缓存是否新鲜，不新鲜的缓存需要发到服务端做新鲜度的检测. */
    public boolean refreshNeeded() {
        return this.softTtl < System.currentTimeMillis();
    }
}
```

## 抽象缓存系统类

```java
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
}
```

在Volley中,实现Cache接口的硬盘缓存类是DiskBasedCache.接下来,具体介绍每个方法的具体实现.

### 构造函数

我们先来看一下DiskBasedCache的构造函数实现:
```java
public DiskBasedCache(File rootDirectory) {
    this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
}

public DiskBasedCache(File rootDirectory, int maxCacheSizeInBytes) {
    mRootDirectory = rootDirectory;
    mMaxCacheSizeInBytes = maxCacheSizeInBytes;
}
```
类似于LruCache,DiskBasedCache的构造函数做了两件事:

1. 指定硬盘缓存的目录.
2. 指定硬盘缓存的大小,默认为5M.


### initialize函数

在介绍put和get函数之前,先介绍一下硬盘缓存的初始化函数,这个函数主要是用来遍历缓存的文件,从而获取当前缓存大小,和构造<key,value>键值对信息.
```java
/** 
 * 初始化Disk缓存系统.
 * 作用是：遍历Disk缓存系统,将缓存文件中的CacheHeader和key存储到Map对象中. 
 */
public void initialize() {
    if (!mRootDirectory.exists() && !mRootDirectory.mkdirs()) {
        // 硬盘缓存目录不存在直接返回即可
        return;
    }

    // 获取硬盘缓存目录所有文件集合.每个HTTP请求结果对应一个文件.
    File[] files = mRootDirectory.listFiles();
    if (files == null) {
        return;
    }

    for (File file : files) {
        BufferedInputStream fis = null;
        try {
            fis = new BufferedInputStream(new FileInputStream(file));
            // 进行对象反序列化
            CacheHeader entry = CacheHeader.readHeader(fis);
            // 将文件的大小赋值给entry.size,单位字节
            entry.size = file.length();
            // 在内存中维护一张硬盘<key,value>映射表
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
```

### put函数

接下来我们讲解put函数,是因为一个缓存系统最为关键的操作就是put,这其中还设计到缓存替换策略的实现.

首先是缓存替换策略.
```java
private void pruneIfNeeded(int neededSpace) {
    if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes) {
        return;
    }

    Iterator<Map.Entry<String, CacheHeader>> iterator = mEntries.entrySet().iterator();
    while (iterator.hasNext()) {
        Map.Entry<String, CacheHeader> entry = iterator.next();
        CacheHeader e = entry.getValue();
        // 这里的替换策略不太好,其实可以按照serverDate排序,从而实现FIFO的缓存替换策略.
        boolean deleted = getFileForKey(e.key).delete();
        if (deleted) {
            mTotalSize -= e.size;
        }
        iterator.remove();
        
        // 当硬盘大小满足可以存放新的HTTP请求结果时,停止删除操作
        if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes * HYSTERESIS_FACTOR) {
            break;
        }
    }
}
```

接下来,是硬盘缓存的插入操作,准备是对象序列化的一些内容.
```java
/** 将Cache.Entry存入到指定的缓存文件中. 并在Map中记录<key,CacheHeader>. */
@Override
public synchronized void put(String key, Entry entry) {
    pruneIfNeeded(entry.data.length);
    // 根据HTTP的url生成缓存文件(ps:根据hash值生成文件名)
    File file = getFileForKey(key);
    try {
        BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(file));
        // 这里有个bug,插入时的size只计算响应体,没有考虑响应头部缓存字段的大小.
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
```

### get函数

get函数比较简单了,源码如下:
```java
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
```

### clear函数

clear顾名思义,就是清空硬盘缓存的操作:
```java
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
```

所做的事情也比较简单,包括:

1. 情况缓存文件.
2. 将使用size置为0.
3. 清空内存中维护的硬盘<key,value>映射.


### remove函数

remove函数也就是删除指定key对应的硬盘缓存,代码很简单:
```java
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
```








