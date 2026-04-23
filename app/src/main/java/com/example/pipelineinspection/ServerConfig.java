package com.example.pipelineinspection;



import android.content.Context;
import android.content.SharedPreferences;

public class ServerConfig {

    private static final String SP_NAME = "server_config";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";

    // 默认值：模拟器访问电脑后端（只用于模拟器）
    private static final String DEFAULT_HOST = "10.0.2.2";
    private static final int DEFAULT_PORT = 8080;

    public static String getHost(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_HOST, DEFAULT_HOST);
    }

    public static int getPort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void set(Context ctx, String host, int port) {
        SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .apply();
    }

    public static String httpBase(Context ctx) {
        return "http://" + getHost(ctx) + ":" + getPort(ctx);
    }

    public static String wsUrl(Context ctx) {
        return "ws://" + getHost(ctx) + ":" + getPort(ctx) + "/ws";
    }

    // 判断是否已经手动设置过（避免一直用默认的 10.0.2.2）
    public static boolean isConfigured(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.contains(KEY_HOST) && sp.contains(KEY_PORT);
    }
}

