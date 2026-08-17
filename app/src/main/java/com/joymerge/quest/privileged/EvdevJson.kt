package com.joymerge.quest.privileged

import com.joymerge.quest.core.JoyConDetector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses what the privileged process reports back over AIDL.
 *
 * The privileged side speaks JSON because it has to cross a process boundary
 * with no shared parcelable schema; this is the one place that translation
 * lives, so the rest of the app works with real types.
 */
object EvdevJson {

    data class DeviceList(
        val devices: List<EvdevDeviceInfo>,
        val error: String?,
    )

    fun parseDeviceList(raw: String?): DeviceList {
        if (raw.isNullOrBlank()) return DeviceList(emptyList(), "privileged service returned nothing")
        val root = runCatching { JSONObject(raw) }
            .getOrElse { return DeviceList(emptyList(), "unparseable device list: ${it.message}") }
        root.optString("error").takeIf { it.isNotBlank() }?.let { return DeviceList(emptyList(), it) }

        val array = root.optJSONArray("devices") ?: return DeviceList(emptyList(), "no devices field")
        val result = ArrayList<EvdevDeviceInfo>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result += EvdevDeviceInfo(
                path = item.optString("path"),
                exists = item.optBoolean("exists", false),
                mode = item.optString("mode", "?"),
                ownerUid = item.optInt("uid", -1),
                ownerGid = item.optInt("gid", -1),
                readable = item.optBoolean("readable", false),
                writable = item.optBoolean("writable", false),
                openError = item.optString("openError").takeIf { it.isNotBlank() },
                name = item.optString("name").takeIf { it.isNotBlank() },
                uniq = item.optString("uniq").takeIf { it.isNotBlank() },
                bus = item.optInt("bus", 0),
                vendor = item.optInt("vendor", 0),
                product = item.optInt("product", 0),
                version = item.optInt("version", 0),
                keyCodes = item.optJSONArray("keyCodes").toIntList(),
                absAxes = item.optJSONArray("absAxes").toAbsList(),
            )
        }
        return DeviceList(result, null)
    }

    /** Devices that actually opened and look like controllers rather than sensors. */
    fun gamepadCandidates(devices: List<EvdevDeviceInfo>): List<EvdevDeviceInfo> =
        devices.filter { it.opened && it.keyCodes.any { code -> code in GAMEPAD_KEY_RANGE } }

    fun toCandidate(device: EvdevDeviceInfo): JoyConDetector.Candidate = JoyConDetector.Candidate(
        name = device.name ?: "",
        vendorId = device.vendor,
        productId = device.product,
        uniq = device.uniq,
        keyCodes = device.keyCodes,
        axisCodes = device.absAxes.map { it.code },
    )

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { optInt(it) }
    }

    private fun JSONArray?.toAbsList(): List<AbsAxisInfo> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            AbsAxisInfo(
                code = item.optInt("code"),
                value = item.optInt("value"),
                min = item.optInt("min"),
                max = item.optInt("max"),
                fuzz = item.optInt("fuzz"),
                flat = item.optInt("flat"),
                resolution = item.optInt("resolution"),
            )
        }
    }

    /** BTN_MISC..BTN_THUMBR plus the D-pad button block. */
    private val GAMEPAD_KEY_RANGE = (0x100..0x151) + (0x220..0x223)
}
