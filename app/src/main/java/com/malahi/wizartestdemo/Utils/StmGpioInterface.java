package com.malahi.wizartestdemo.Utils;

public class StmGpioInterface {
    static {
        System.loadLibrary("jni_stm_gpio");
    }

    public static native int ispReset();
}
