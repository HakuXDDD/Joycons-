package com.joymerge.quest.privileged

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.joymerge.quest.core.VirtualButton
import com.joymerge.quest.core.VirtualGamepadState
import java.lang.reflect.Method

/**
 * Fallback path for when /dev/uinput is unavailable.
 *
 * Instead of creating a device, this pushes synthesised events straight into
 * `InputManager.injectInputEvent`, which the shell uid is allowed to call
 * (it is how `adb shell input` works).
 *
 * Be clear-eyed about what this is: injected events go to the *focused window*.
 * There is no device for other apps to enumerate, `InputDevice.getDeviceIds()`
 * will not list a JoyMerge pad, and an app that ignores events whose device id
 * it does not recognise will ignore these too. It is a real fallback, not an
 * equal alternative to uinput.
 */
object InputInjector {

    private const val INJECT_MODE_ASYNC = 0

    private var injectMethod: Method? = null
    private var inputManager: Any? = null

    /** Populated on the first [prepare] call; shown verbatim in Diagnostics. */
    var lastError: String? = null
        private set

    val isReady: Boolean get() = injectMethod != null && inputManager != null

    /** @return null when injection is usable, otherwise the reason it is not. */
    @Synchronized
    fun prepare(): String? {
        if (isReady) return null
        lastError = null

        val manager = resolveManager() ?: return fail(lastError ?: "no InputManager instance available")
        val method = manager.javaClass.methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterTypes.size == 2 &&
                InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return fail("injectInputEvent(InputEvent, int) not found on ${manager.javaClass.name}")

        method.isAccessible = true
        inputManager = manager
        injectMethod = method
        return null
    }

    private fun resolveManager(): Any? {
        // Android 14+ moved the singleton to InputManagerGlobal.
        val candidates = listOf(
            "android.hardware.input.InputManagerGlobal" to "getInstance",
            "android.hardware.input.InputManager" to "getInstance",
        )
        val problems = mutableListOf<String>()
        for ((className, methodName) in candidates) {
            runCatching {
                val clazz = Class.forName(className)
                val getter = clazz.getMethod(methodName)
                getter.isAccessible = true
                getter.invoke(null)
            }.onSuccess { if (it != null) return it }
                .onFailure { problems += "$className.$methodName: ${it.cause?.message ?: it.message}" }
        }
        lastError = problems.joinToString(" | ")
        return null
    }

    private fun fail(message: String): String {
        lastError = message
        return message
    }

    /** Emits the difference between two states. @return null on success. */
    @Synchronized
    fun submit(
        previous: VirtualGamepadState,
        current: VirtualGamepadState,
        deviceId: Int,
    ): String? {
        val method = injectMethod ?: return "injection not prepared"
        val manager = inputManager ?: return "injection not prepared"

        var failure: String? = null

        fun send(event: InputEvent) {
            runCatching { method.invoke(manager, event, INJECT_MODE_ASYNC) }
                .onFailure { failure = failure ?: (it.cause?.message ?: it.message ?: "inject failed") }
        }

        for ((button, keyCode) in KEY_CODES) {
            val was = previous.isPressed(button)
            val now = current.isPressed(button)
            if (was == now) continue
            send(buildKeyEvent(keyCode, now, deviceId))
        }

        if (axesChanged(previous, current)) {
            val motion = buildMotionEvent(current, deviceId)
            send(motion)
            // injectInputEvent parcels the event, so it is ours to return to the
            // pool. At sixty-odd frames a second, not doing this adds up.
            motion.recycle()
        }
        return failure
    }

    /** Releases everything, so nothing stays stuck down after a stop. */
    @Synchronized
    fun releaseAll(previous: VirtualGamepadState, deviceId: Int) {
        submit(previous, VirtualGamepadState.NEUTRAL, deviceId)
    }

    private fun axesChanged(a: VirtualGamepadState, b: VirtualGamepadState): Boolean =
        a.leftX != b.leftX || a.leftY != b.leftY ||
            a.rightX != b.rightX || a.rightY != b.rightY ||
            a.leftTrigger != b.leftTrigger || a.rightTrigger != b.rightTrigger

    private fun buildKeyEvent(keyCode: Int, pressed: Boolean, deviceId: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now,
            now,
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode,
            0,
            0,
            if (deviceId >= 0) deviceId else KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
        )
    }

    private fun buildMotionEvent(state: VirtualGamepadState, deviceId: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        }
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, state.leftX)
            setAxisValue(MotionEvent.AXIS_Y, state.leftY)
            setAxisValue(MotionEvent.AXIS_Z, state.rightX)
            setAxisValue(MotionEvent.AXIS_RZ, state.rightY)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, state.leftTrigger)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, state.rightTrigger)
            setAxisValue(MotionEvent.AXIS_BRAKE, state.leftTrigger)
            setAxisValue(MotionEvent.AXIS_GAS, state.rightTrigger)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX(state))
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY(state))
        }
        return MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            if (deviceId >= 0) deviceId else 0,
            0,
            InputDevice.SOURCE_JOYSTICK,
            0,
        )
    }

    private fun hatX(state: VirtualGamepadState): Float = when {
        state.isPressed(VirtualButton.DPAD_LEFT) && !state.isPressed(VirtualButton.DPAD_RIGHT) -> -1f
        state.isPressed(VirtualButton.DPAD_RIGHT) && !state.isPressed(VirtualButton.DPAD_LEFT) -> 1f
        else -> 0f
    }

    private fun hatY(state: VirtualGamepadState): Float = when {
        state.isPressed(VirtualButton.DPAD_UP) && !state.isPressed(VirtualButton.DPAD_DOWN) -> -1f
        state.isPressed(VirtualButton.DPAD_DOWN) && !state.isPressed(VirtualButton.DPAD_UP) -> 1f
        else -> 0f
    }

    /** D-pad is carried by the hat axes above, so it is intentionally absent here. */
    val KEY_CODES: List<Pair<VirtualButton, Int>> = listOf(
        VirtualButton.A to KeyEvent.KEYCODE_BUTTON_A,
        VirtualButton.B to KeyEvent.KEYCODE_BUTTON_B,
        VirtualButton.X to KeyEvent.KEYCODE_BUTTON_X,
        VirtualButton.Y to KeyEvent.KEYCODE_BUTTON_Y,
        VirtualButton.LB to KeyEvent.KEYCODE_BUTTON_L1,
        VirtualButton.RB to KeyEvent.KEYCODE_BUTTON_R1,
        VirtualButton.L3 to KeyEvent.KEYCODE_BUTTON_THUMBL,
        VirtualButton.R3 to KeyEvent.KEYCODE_BUTTON_THUMBR,
        VirtualButton.START to KeyEvent.KEYCODE_BUTTON_START,
        VirtualButton.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        VirtualButton.MODE to KeyEvent.KEYCODE_BUTTON_MODE,
    )
}
