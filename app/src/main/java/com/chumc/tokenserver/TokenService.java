package com.chumc.tokenserver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Token 服务常驻 Service：
 * App 打开后启动，退到后台仍持续运行（不做开机自启）。
 */
public class TokenService extends Service {

    private TokenHttpServer server;
    /** 服务是否在运行（供界面显示状态） */
    public static volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            server = new TokenHttpServer(this);
            server.start(5000);
            running = true;
        } catch (Exception e) {
            running = false;
            server = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统回收时尝试重建
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
