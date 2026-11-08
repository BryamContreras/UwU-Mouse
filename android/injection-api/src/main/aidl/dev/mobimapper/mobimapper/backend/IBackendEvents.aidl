package dev.mobimapper.mobimapper.backend;
oneway interface IBackendEvents {
    void recovery(long sessionId, long workerGeneration, int phase, String reason);
}
