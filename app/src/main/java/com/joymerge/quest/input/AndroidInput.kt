package com.joymerge.quest.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.joymerge.quest.core.AxisInfo
import com.joymerge.quest.core.JoyConDetector
import com.joymerge.quest.core.JoyConSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An `InputDevice` flattened into something we can display, test and persist. */
data class AndroidDeviceInfo(
    val deviceId: Int,
    val name: String,
    val descriptor: String?,
    val vendorId: Int,
    val productId: Int,
    val sources: Int,
    val isExternal: Boolean,
    val isVirtual: Boolean,
    val controllerNumber: Int,
    val axes: List<AndroidAxisInfo>,
    val supportedKeys: List<Int>,
) {
    val sourceLabels: List<String> get() = SOURCE_LABELS.filter { (mask, _) ->
        sources and mask == mask
    }.map { it.second }

    val idString: String get() = "%04x:%04x".format(vendorId, productId)

    val looksLikeGamepad: Boolean
        get() = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    fun toCandidate(): JoyConDetector.Candidate = JoyConDetector.Candidate(
        name = name,
        vendorId = vendorId,
        productId = productId,
        keyCodes = supportedKeys,
        axisCodes = axes.map { it.axis },
    )

    companion object {
        private val SOURCE_LABELS = listOf(
            InputDevice.SOURCE_GAMEPAD to "GAMEPAD",
            InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
            InputDevice.SOURCE_DPAD to "DPAD",
            InputDevice.SOURCE_KEYBOARD to "KEYBOARD",
            InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
            InputDevice.SOURCE_MOUSE to "MOUSE",
            InputDevice.SOURCE_STYLUS to "STYLUS",
            InputDevice.SOURCE_TRACKBALL to "TRACKBALL",
            InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY_ENCODER",
        )
    }
}

data class AndroidAxisInfo(
    val axis: Int,
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float,
    val resolution: Float,
) {
    val label: String get() = MotionEvent.axisToString(axis)

    fun toCoreAxisInfo(): AxisInfo = AxisInfo(min, max, flat)
}

/**
 * Enumerates and watches Android's view of the connected controllers.
 *
 * This is the app's foreground picture of the world. It is what powers the
 * device list and the automatic Joy-Con detection, and it is the only input
 * source available when Shizuku is not set up — with the important limitation
 * that Android stops delivering these events the moment JoyMerge loses focus.
 */
class InputDeviceManager(context: Context) {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<AndroidDeviceInfo>>(emptyList())
    val devices: StateFlow<List<AndroidDeviceInfo>> = _devices.asStateFlow()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    private var registered = false

    fun start() {
        if (!registered) {
            inputManager.registerInputDeviceListener(listener, handler)
            registered = true
        }
        refresh()
    }

    fun stop() {
        if (registered) {
            inputManager.unregisterInputDeviceListener(listener)
            registered = false
        }
    }

    fun refresh() {
        _devices.value = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.let { describe(it) }
        }
    }

    fun deviceById(deviceId: Int): AndroidDeviceInfo? = _devices.value.firstOrNull { it.deviceId == deviceId }

    /** Best guess at which device is which Joy-Con, based on ids first, names second. */
    fun detectJoyCons(): Map<JoyConSide, AndroidDeviceInfo> =
        JoyConDetector.assignSides(_devices.value) { it.toCandidate() }

    private fun describe(device: InputDevice): AndroidDeviceInfo {
        val axes = device.motionRanges
            .filter { it.source and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD) != 0 || true }
            .map {
                AndroidAxisInfo(
                    axis = it.axis,
                    min = it.min,
                    max = it.max,
                    flat = it.flat,
                    fuzz = it.fuzz,
                    resolution = it.resolution,
                )
            }
            .distinctBy { it.axis }

        val keys = runCatching {
            val probe = PROBE_KEY_CODES.toIntArray()
            val present = device.hasKeys(*probe)
            PROBE_KEY_CODES.filterIndexed { index, _ -> present.getOrElse(index) { false } }
        }.getOrDefault(emptyList())

        return AndroidDeviceInfo(
            deviceId = device.id,
            name = device.name ?: "(unnamed)",
            descriptor = device.descriptor,
            vendorId = device.vendorId,
            productId = device.productId,
            sources = device.sources,
            isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { device.isExternal }.getOrDefault(true)
            } else {
                // isExternal landed in API 29. Below that, assume external:
                // a Joy-Con always is, and this field is only informational.
                true
            },
            isVirtual = device.isVirtual,
            controllerNumber = device.controllerNumber,
            axes = axes,
            supportedKeys = keys,
        )
    }

    companion object {
        /** Everything a pad might plausibly report; used with `InputDevice.hasKeys`. */
        val PROBE_KEY_CODES: List<Int> = listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
            KeyEvent.KEYCODE_BUTTON_5,
            KeyEvent.KEYCODE_BUTTON_6,
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10,
            KeyEvent.KEYCODE_BUTTON_11,
            KeyEvent.KEYCODE_BUTTON_12,
            KeyEvent.KEYCODE_BUTTON_13,
            KeyEvent.KEYCODE_BUTTON_14,
            KeyEvent.KEYCODE_BUTTON_15,
            KeyEvent.KEYCODE_BUTTON_16,
        )

        /** Axes worth sampling from a `MotionEvent` for calibration and diagnostics. */
        val PROBE_AXES: List<Int> = listOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RX,
            MotionEvent.AXIS_RY,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_THROTTLE,
            MotionEvent.AXIS_RUDDER,
            MotionEvent.AXIS_WHEEL,
            MotionEvent.AXIS_GENERIC_1,
            MotionEvent.AXIS_GENERIC_2,
            MotionEvent.AXIS_GENERIC_3,
            MotionEvent.AXIS_GENERIC_4,
        )
    }
}
