package com.android.volley.toolbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by wzy on 16-3-21.
 */
public class ByteArrayPool {
    /** The buffer pool, arranged both by last use and by buffer size */
    private List<byte[]> mBuffersByLastUse = new LinkedList<byte[]>();
    private List<byte[]> mBuffersBySize = new ArrayList<byte[]>(64);

    /**
     * The total size of the buffers in the pool
     */
    private int mCurrentSize = 0;

    /**
     * The maximum aggregate size of the buffers in the pool.
     */
    private final int mSizeLimit;

    protected static final Comparator<byte[]> BUF_COMPARATOR = new Comparator<byte[]>() {
        @Override
        public int compare(byte[] lhs, byte[] rhs) {
            return lhs.length - rhs.length;
        }
    };

    public ByteArrayPool(int sizeLimit) {
        mSizeLimit = sizeLimit;
    }

    /**
     * Returns a buffer from the pool if one is available in the requested size, or allocates a new
     * one if a pooled one is not available
     * @param len the minimum size, in bytes, of the requested buffer. The returned buffer may be
     *            larger.
     * @return a byte[] buffer is always returned.
     */
    public synchronized byte[] getBuf(int len) {
        for (int i = 0; i < mBuffersBySize.size(); i ++) {
            byte[] buf = mBuffersBySize.get(i);
            if (buf.length >= len) {
                mCurrentSize -= buf.length;
                mBuffersBySize.remove(i);
                mBuffersByLastUse.remove(buf);
                return buf;
            }
        }
        return new byte[len];
    }

    /**
     * Returns a buffer to the pool, throwing away old buffers if the pool would exceed its allotted
     * size.
     *
     * @param buf the buffer to return to the pool.
     */
    public synchronized void returnBuf(byte[] buf) {
        if (buf == null || buf.length > mSizeLimit) {
            return;
        }

        mBuffersByLastUse.add(buf);
        int pos  = Collections.binarySearch(mBuffersBySize, buf, BUF_COMPARATOR);
        if (pos < 0) {
            pos = -pos - 1;
        }
        mBuffersBySize.add(pos, buf);
        mCurrentSize += buf.length;
        trim();
    }

    /**
     * Removes buffers from the pool until it is under its size limit.
     */
    private synchronized void trim() {
        while (mCurrentSize > mSizeLimit) {
            byte[] buf = mBuffersByLastUse.remove(0);
            mBuffersBySize.remove(buf);
            mCurrentSize -= buf.length;
        }
    }
}
