package com.malahi.wizartestdemo.Utils;

import android.content.Context;
import android.os.Handler;

public class HandleCallbackImpl extends HandleCallBack {

    int HANDLER_LOG = 1;
    private Handler handler = null;
    private Context mContext = null;

    public HandleCallbackImpl(Context context, Handler handler) {
        this.mContext = context;
        this.handler = handler;
    }

    @Override
    public void sendResponse(String msg) {
        sendResponse(0, msg);
    }

    @Override
    public void sendResponse(int code, String msg) {
        super.sendResponse(code, msg);
        handler.obtainMessage(code, msg + "\n").sendToTarget();
    }
}
