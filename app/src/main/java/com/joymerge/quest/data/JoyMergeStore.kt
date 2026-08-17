package com.joymerge.quest.data

import android.content.Context
import android.content.SharedPreferences
import com.joymerge.quest.core.MappingProfile
import com.joymerge.quest.core.ProfileCodec
import com.joymerge.quest.gamepad.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the user can change, in one immutable snapshot. */
data class AppSettings(
    val backendId: String = "uinput",
    val identity: DeviceIdentity = DeviceIdentity.NEUTRAL_VIRTUAL,
    val deviceName: String = "JoyMerge Virtual Gamepad",
    val duplicateTriggerAxes: Boolean = true,
    /**
     * Take the Joy-Cons exclusively (EVIOCGRAB) so Horizon OS stops seeing them
     * as two separate pads. Turning this off leaves three controllers visible.
     */
    val exclusiveGrab: Boolean = true,
    val leftEvdevPath: String? = null,
    val rightEvdevPath: String? = null,
    val leftAndroidDescriptor: String? = null,
    val rightAndroidDescriptor: String? = null,
)

/**
 * SharedPreferences-backed persistence.
 *
 * Plain preferences plus [ProfileCodec]'s text format rather than a database or
 * a JSON library: the profile has to survive being exported into a bug report
 * and read by a human, and there is not enough data here to justify anything
 * heavier.
 */
class JoyMergeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<ProfileCodec.Bundle> = _profile.asStateFlow()

    val hasCalibration: Boolean get() = !_profile.value.isEmpty

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit().apply {
            putString(KEY_BACKEND, next.backendId)
            putString(KEY_IDENTITY, next.identity.name)
            putString(KEY_DEVICE_NAME, next.deviceName)
            putBoolean(KEY_DUPLICATE_TRIGGERS, next.duplicateTriggerAxes)
            putBoolean(KEY_EXCLUSIVE_GRAB, next.exclusiveGrab)
            putStringOrRemove(KEY_LEFT_EVDEV, next.leftEvdevPath)
            putStringOrRemove(KEY_RIGHT_EVDEV, next.rightEvdevPath)
            putStringOrRemove(KEY_LEFT_DESCRIPTOR, next.leftAndroidDescriptor)
            putStringOrRemove(KEY_RIGHT_DESCRIPTOR, next.rightAndroidDescriptor)
        }.apply()
        _settings.value = next
    }

    fun saveProfile(bundle: ProfileCodec.Bundle) {
        val stamped = if (bundle.createdAt == 0L) bundle.copy(createdAt = System.currentTimeMillis()) else bundle
        prefs.edit().putString(KEY_PROFILE, ProfileCodec.encode(stamped)).apply()
        _profile.value = stamped
    }

    fun updateProfile(transform: (ProfileCodec.Bundle) -> ProfileCodec.Bundle) {
        saveProfile(transform(_profile.value).copy(createdAt = System.currentTimeMillis()))
    }

    fun saveMapping(mapping: MappingProfile) {
        updateProfile { it.withProfile(mapping) }
    }

    fun clearProfile() {
        prefs.edit().remove(KEY_PROFILE).apply()
        _profile.value = ProfileCodec.Bundle()
    }

    fun exportProfileText(): String = ProfileCodec.encode(_profile.value)

    fun importProfileText(text: String): Boolean {
        val decoded = runCatching { ProfileCodec.decode(text) }.getOrNull() ?: return false
        if (decoded.isEmpty) return false
        saveProfile(decoded)
        return true
    }

    private fun readSettings(): AppSettings {
        val identity = prefs.getString(KEY_IDENTITY, null)
            ?.let { name -> runCatching { DeviceIdentity.valueOf(name) }.getOrNull() }
            ?: DeviceIdentity.NEUTRAL_VIRTUAL
        return AppSettings(
            backendId = prefs.getString(KEY_BACKEND, "uinput") ?: "uinput",
            identity = identity,
            deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME,
            duplicateTriggerAxes = prefs.getBoolean(KEY_DUPLICATE_TRIGGERS, true),
            exclusiveGrab = prefs.getBoolean(KEY_EXCLUSIVE_GRAB, true),
            leftEvdevPath = prefs.getString(KEY_LEFT_EVDEV, null),
            rightEvdevPath = prefs.getString(KEY_RIGHT_EVDEV, null),
            leftAndroidDescriptor = prefs.getString(KEY_LEFT_DESCRIPTOR, null),
            rightAndroidDescriptor = prefs.getString(KEY_RIGHT_DESCRIPTOR, null),
        )
    }

    private fun readProfile(): ProfileCodec.Bundle {
        val text = prefs.getString(KEY_PROFILE, null) ?: return ProfileCodec.Bundle()
        return runCatching { ProfileCodec.decode(text) }.getOrDefault(ProfileCodec.Bundle())
    }

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val PREFS_NAME = "joymerge"
        const val DEFAULT_DEVICE_NAME = "JoyMerge Virtual Gamepad"

        const val KEY_BACKEND = "backend"
        const val KEY_IDENTITY = "identity"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DUPLICATE_TRIGGERS = "duplicate_triggers"
        const val KEY_EXCLUSIVE_GRAB = "exclusive_grab"
        const val KEY_LEFT_EVDEV = "left_evdev"
        const val KEY_RIGHT_EVDEV = "right_evdev"
        const val KEY_LEFT_DESCRIPTOR = "left_descriptor"
        const val KEY_RIGHT_DESCRIPTOR = "right_descriptor"
        const val KEY_PROFILE = "profile"
    }
}
