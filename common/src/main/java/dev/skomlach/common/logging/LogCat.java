package dev.skomlach.common.logging;

import android.util.Log;

import dev.skomlach.common.BuildConfig;

public class LogCat {

    public static boolean DEBUG = BuildConfig.DEBUG;

    private LogCat() {

    }

    private static String getMethod() {
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        StackTraceElement el = elements[3];
        return el.getClassName() + ":" + el.getMethodName() + ", " + el.getFileName() + ":" + el.getLineNumber();
    }

    public static void log(String msg) {
        if (DEBUG) {
            Log.d(getMethod(), msg);
        }
    }

    public static void logError(String msg) {
        if (DEBUG) {
            Log.e(getMethod(), msg);
        }
    }

    public static void logException(Throwable e) {
        if (DEBUG)
            Log.e(getMethod(), e.getMessage(), e);
    }

    public static void logException(String msg, Throwable e) {
        if (DEBUG)
            Log.e(getMethod(), msg, e);
    }
}