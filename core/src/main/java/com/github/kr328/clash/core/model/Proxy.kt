package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class Proxy(
    val name: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val delay: Int,
    var isGroup: Boolean,
    // Fleet-status annotation, filled by cfa/native/fleet from the hourly
    // per-node sweep. Empty/zero means "no fresh verdict" — an unknown
    // node, a stale feed, or a nested group. Defaults keep the model
    // decodable from a core build that predates these fields.
    val fleetGemini: String = "",
    val fleetGeminiDetail: String = "",
    val fleetYoutubeGl: String = "",
    val fleetExitIp: String = "",
    val fleetReachable: Boolean = false,
    val fleetCheckedAt: Long = 0,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Proxy> {
        override fun createFromParcel(parcel: Parcel): Proxy {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Proxy?> {
            return arrayOfNulls(size)
        }
    }
}
