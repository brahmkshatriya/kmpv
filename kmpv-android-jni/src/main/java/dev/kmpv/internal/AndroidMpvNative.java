package dev.kmpv.internal;

import android.view.Surface;

public final class AndroidMpvNative {
    static {
        System.loadLibrary("kmpv_jni");
    }

    private AndroidMpvNative() {}

    public static native long nativeCreate();
    public static native String nativeLastLoadError();
    public static native void nativeDestroy(long handle);
    public static native int nativeInitialize(long handle);
    public static native String nativeClientName(long handle);
    public static native long nativeClientId(long handle);
    public static native String nativeErrorString(int code);

    public static native int nativeSetOptionString(long handle, String name, String value);
    public static native int nativeLoadConfigFile(long handle, String path);
    public static native int nativeRequestLogMessages(long handle, String level);
    public static native byte[] nativeGetProperty(long handle, String name, int format);
    public static native int nativeSetPropertyString(long handle, String name, String value);
    public static native int nativeSetPropertyFlag(long handle, String name, boolean value);
    public static native int nativeSetPropertyInt64(long handle, String name, long value);
    public static native int nativeSetPropertyDouble(long handle, String name, double value);
    public static native int nativeSetPropertyNode(long handle, String name, byte[] value);
    public static native int nativeGetPropertyAsync(
        long handle,
        long requestId,
        String name,
        int format
    );
    public static native int nativeSetPropertyStringAsync(
        long handle,
        long requestId,
        String name,
        String value
    );
    public static native int nativeSetPropertyFlagAsync(
        long handle,
        long requestId,
        String name,
        boolean value
    );
    public static native int nativeSetPropertyInt64Async(
        long handle,
        long requestId,
        String name,
        long value
    );
    public static native int nativeSetPropertyDoubleAsync(
        long handle,
        long requestId,
        String name,
        double value
    );
    public static native int nativeSetPropertyNodeAsync(
        long handle,
        long requestId,
        String name,
        byte[] value
    );

    public static native int nativeCommand(long handle, String[] args);
    public static native byte[] nativeCommandResult(long handle, String[] args);
    public static native int nativeCommandAsync(long handle, long requestId, String[] args);
    public static native void nativeAbortAsyncCommand(long handle, long requestId);

    public static native int nativeObserveProperty(
        long handle,
        long observerId,
        String name,
        int format
    );

    public static native int nativeUnobserveProperty(long handle, long observerId);
    public static native byte[] nativeWaitEvent(long handle);
    public static native void nativeWakeup(long handle);
    public static native int nativeAttachSurface(long handle, Surface surface);
    public static native int nativeDetachSurface(long handle);
}
