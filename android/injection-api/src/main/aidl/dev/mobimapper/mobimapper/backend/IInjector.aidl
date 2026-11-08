package dev.mobimapper.mobimapper.backend;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import dev.mobimapper.mobimapper.backend.IBackendEvents;
import dev.mobimapper.mobimapper.backend.IPhysicalInputCallback;
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
    // Appended methods preserve the transaction numbers of the existing injection protocol.
    Bundle startCapture(IPhysicalInputCallback client, int displayId, String targetPackage, boolean mouse, boolean keyboard);
    boolean renewCapture(long captureId);
    void stopCapture(long captureId);
    Bundle captureStatus();
    Bundle windowContext();
    boolean forwardKey(long captureId, int action, int keyCode, int scanCode, long eventTime, int metaState);
    void showNotifications();
    // Retries closure of this guardian's existing stream; never adopts or changes its destination.
    boolean recoverSession(long sessionId);
    Bundle startCaptureWithRate(IPhysicalInputCallback client, int displayId, String targetPackage, boolean mouse, boolean keyboard, int mouseRateHz);
    boolean setCaptureMouseRate(long captureId, int mouseRateHz);
    // One camera-only three-frame replacement; commit an attempt record before each worker send.
    int injectCameraReset(long sessionId, in int[] actions, in long[] downTimes, in long[] eventTimes,
        in int[] counts, in int[] ids, in float[] xs, in float[] ys, in ParcelFileDescriptor progress);
    // Passive, trusted cursor surface; never receives input or injects events.
    long showVisualCursor(int displayId, float x, float y, float sizeDp);
    oneway void moveVisualCursor(long cursorToken, float x, float y, float sizeDp);
    // Zero closes this authorized client's cursor after an ambiguous show reply.
    void hideVisualCursor(long cursorToken);
}
