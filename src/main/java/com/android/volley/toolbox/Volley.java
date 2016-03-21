package com.android.volley.toolbox;


import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.android.volley.Network;
import com.android.volley.RequestQueue;

import java.io.File;

public class Volley {
    private static final String DEFAULT_CACHE_DIR = "volley";

    public static RequestQueue newRequestQueue(Context context) {
        return newRequestQueue(context, null);
    }

    private static RequestQueue newRequestQueue(Context context, HttpStack stack) {
        File cacheDir = new File(context.getCacheDir(), DEFAULT_CACHE_DIR);
        String userAgent = "volley/0";
        try {
            String packageName = context.getPackageName();
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            userAgent = packageName + "/" + info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        if (stack == null) {
            stack = new HurlStack();
        }

        Network network = new BasicNetwork(stack);

        RequestQueue queue = new RequestQueue(new DiskBasedCache(cacheDir), network);
        queue.start();

        return queue;
    }
}
