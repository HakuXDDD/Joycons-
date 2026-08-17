package com.joymerge.quest.privileged;

import com.joymerge.quest.privileged.IJoyMergeListener;

/**
 * The privileged half of JoyMerge, hosted by Shizuku and therefore running as
 * uid 2000 (shell).
 *
 * The whole merge loop lives on this side on purpose: reading /dev/input and
 * writing /dev/uinput both need shell privileges, and keeping the hot path out
 * of the app process means it survives the app being sent to the background
 * while the user plays in Xbox Cloud Gaming.
 *
 * Every call returns either a JSON string describing exactly what happened, or
 * a negative errno. Nothing here reports success it did not achieve.
 */
interface IJoyMergeService {

    /** Shizuku-defined destroy transaction. */
    void destroy() = 16777114;

    /** Loads libjoymerge.so. Returns a JSON report of the attempt. */
    String initialize(String nativeLibDir) = 1;

    /** uid, euid, groups, SELinux context, kernel and ABI details. */
    String describeEnvironment() = 2;

    /** Enumerates /dev/input/event*, with permissions, names, ids and codes. */
    String listInputDevices() = 3;

    /** Existence, permissions and a real open() attempt on /dev/uinput. */
    String probeUinput() = 4;

    /**
     * Opens the given evdev nodes and starts reader threads.
     * sides[i] is the JoyConSide ordinal for paths[i]. When grab is true the
     * devices are taken exclusively so Horizon OS stops seeing them separately.
     */
    String startCapture(in String[] paths, in int[] sides, boolean grab) = 5;

    void stopCapture() = 6;

    /** Creates the merged uinput device. Returns a JSON result with errno detail. */
    String startVirtualGamepad(
        String deviceName, int busType, int vendor, int product, int version,
        boolean duplicateTriggerAxes) = 7;

    void stopVirtualGamepad() = 8;

    /** Current state of capture, uinput device and injection support. */
    String status() = 9;

    /** Installs the calibration profile, encoded by ProfileCodec. */
    void setProfile(String profileText) = 10;

    void setListener(IJoyMergeListener listener) = 11;

    void setRawEventStreaming(boolean enabled) = 12;

    String drainLog() = 13;

    /** Pushes a frame produced by the app itself (used by the Tester). */
    oneway void submitFrame(in int[] frame) = 14;

    /** Fallback path: injects the frame through InputManager instead of uinput. */
    String startInjectionBackend() = 15;

    void stopInjectionBackend() = 16;
}
