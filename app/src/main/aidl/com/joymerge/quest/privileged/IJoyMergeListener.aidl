package com.joymerge.quest.privileged;

/**
 * Callbacks from the privileged process back into the app UI.
 * Everything is oneway: the merge loop must never block on the UI.
 */
oneway interface IJoyMergeListener {
    /** Throttled snapshot of the merged pad, encoded by VirtualGamepadState.toFrame(). */
    void onState(in int[] frame);

    /** Raw evdev traffic, only while the app asked for it (Diagnostics screen). */
    void onRawEvent(int slot, int type, int code, int value);

    /** One line of privileged-side log. */
    void onLog(String line);

    /** A capture thread stopped; message carries the real errno text. */
    void onCaptureError(int slot, String message);
}
