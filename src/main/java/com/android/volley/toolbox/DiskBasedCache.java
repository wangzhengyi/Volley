package com.android.volley.toolbox;

import android.support.annotation.NonNull;
import android.util.Log;

import com.android.volley.Cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** 抽象出来的缓存文件摘要信息。
     * 与Cache.Entry类几乎相同,但是只存储了响应体的大小，没保存响应体的内容.
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
