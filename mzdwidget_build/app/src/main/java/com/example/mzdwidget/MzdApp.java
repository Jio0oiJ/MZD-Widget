package com.example.mzdwidget;

import android.app.Application;
import android.content.Context;

/** 全局 Context：供 CtrlLog 在前台服务/接收器/主线程之外写日志。 */
public final class MzdApp extends Application {
    private static Context app;

    @Override public void onCreate() {
        super.onCreate();
        app = getApplicationContext();
    }

    public static Context get() { return app; }
}
