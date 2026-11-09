package dev.uwumouse.app.backend;
oneway interface IBackendEvents {
    void recovery(long sessionId, long workerGeneration, int phase, String reason);
}
