package dev.mobimapper.mobimapper.backend;

/** Native capture events carry kernel monotonic time, not Android input device identity. */
oneway interface IPhysicalInputCallback {
    void onFrame(long captureId, int kind, long eventTimeNanos, int deviceId,
        String devicePath, float relX, float relY, float wheel, float horizontalWheel,
        int buttonState, int keyScanCode, boolean keyDown, boolean keyRepeat);
    void onCaptureLost(long captureId, String reason);
    void onMotionBatch(long captureId, in long[] eventTimesNanos, in int[] deviceIds,
        in String[] devicePaths, in float[] relXs, in float[] relYs,
        float wheel, float horizontalWheel, int buttonState);
}
