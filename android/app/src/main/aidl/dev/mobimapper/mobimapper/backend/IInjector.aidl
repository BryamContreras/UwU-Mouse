package dev.mobimapper.mobimapper.backend;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import dev.mobimapper.mobimapper.backend.IBackendEvents;
interface IInjector {
    Bundle status();
    long begin(IBackendEvents client, int width, int height);
    boolean inject(long sessionId, int action, long downTime, long eventTime, in int[] ids, in float[] xs, in float[] ys);
    boolean release(long sessionId);
    long recoverOrphan(IBackendEvents client, int width, int height, long downTime, in int[] ids, in float[] xs, in float[] ys, int originalTargetUid);
    void armTestFault(int action, int stage);
    void shutdown();
    long beginTarget(IBackendEvents client, int width, int height, String targetPackage);
    long recoverTargetOrphan(IBackendEvents client, int width, int height, long downTime, in int[] ids, in float[] xs, in float[] ys, int originalTargetUid, String originalTargetPackage);
    long beginContinuousTarget(IBackendEvents client, int width, int height, String targetPackage);
    long recoverContinuousTargetOrphan(IBackendEvents client, int width, int height, long downTime, in int[] ids, in float[] xs, in float[] ys, int originalTargetUid, String originalTargetPackage);
    long beginTargetOnDisplay(IBackendEvents client, int width, int height, String targetPackage, int displayId, boolean continuousCamera);
    long recoverTargetOrphanOnDisplay(IBackendEvents client, int width, int height, long downTime, in int[] ids, in float[] xs, in float[] ys, int originalTargetUid, String originalTargetPackage, int originalDisplayId, boolean continuousCamera);
    int motionDisplayId(in MotionEvent event);
    Bundle focusedApplication();
}
