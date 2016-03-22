package com.android.volley;

import android.os.Handler;

import java.util.concurrent.Executor;

/**
 * Delivers responses and errors.
 */
public class ExecutorDelivery implements ResponseDelivery {
    /**
     * Used for posting responses, typically to the main thread.
     */
    private final Executor mResponsePoster;

    public ExecutorDelivery(final Handler handler) {
        mResponsePoster = new Executor() {
            @Override
            public void execute(Runnable command) {
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
        mResponsePoster.execute(new ResponseDeliveryRunnable(request, response, runnable));
    }

    @Override
    public void postError(Request<?> request, VolleyError error) {
        Response<?> response = Response.error(error);
        mResponsePoster.execute(new ResponseDeliveryRunnable(request, response, null));
    }

    private class ResponseDeliveryRunnable implements Runnable {
        private final Request<?> mRequest;
        private final Response<?> mResponse;
        private final Runnable mRunnable;

        public ResponseDeliveryRunnable(Request<?> request, Response<?> response, Runnable runnable) {
            mRequest = request;
            mResponse = response;
            mRunnable = runnable;
        }

        @Override
        public void run() {
            // If this request has canceled, finish it and don't deliver.
            if (mRequest.isCanceled()) {
                mRequest.finish("canceled-at-delivery");
                return;
            }

            // Deliver a normal response or error, depending.
            if (mResponse.isSuccess()) {
                mRequest.deliverResponse(mResponse.result);
            } else {
                mRequest.deliverError(mResponse.error);
            }

            if (mResponse.intermediate) {
                mRequest.addMarker("intermediate-response");
            } else {
                mRequest.finish("done");
            }

            if (mRunnable != null) {
                mRunnable.run();
            }
        }
    }
}
