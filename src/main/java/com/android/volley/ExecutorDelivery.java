package com.android.volley;

import android.os.Handler;

import java.util.concurrent.Executor;

/**
 * 网络请求结果传递类.(实现异步功能，主线程传递数据给子线程)
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
            public void execute(Runnable command) {
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
            // 如果request被取消，则不回调用户设置的Listener接口
            if (mRequest.isCanceled()) {
                mRequest.finish("canceled-at-delivery");
                return;
            }

            // 通过response状态标志，来判断是回调用户设置的Listener接口还是ErrorListener接口
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
