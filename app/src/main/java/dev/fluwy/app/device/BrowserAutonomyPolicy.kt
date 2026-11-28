package dev.fluwy.app.device

import dev.fluwy.app.data.AgentProfile

/** Coordinates the per-agent Browser autonomy toggle with the app's BACKGROUND consent. */
object BrowserAutonomyPolicy {
    /** Normal Browser access never opts into autonomy or background execution. */
    fun setBrowserEnabled(profile: AgentProfile, enabled: Boolean): AgentProfile {
        val updated = profile.copy(useBrowser = enabled)
        return if (enabled) updated else setEnabled(updated, enabled = false)
    }

    /** Turning this off revokes only the background grant that this toggle added itself. */
    fun setEnabled(profile: AgentProfile, enabled: Boolean): AgentProfile {
        if (!enabled || !profile.useBrowser) {
            val capabilities = if (profile.browserAutonomyGrantedBackground)
                profile.deviceCaps.filterNot { it == DeviceCapPolicy.BACKGROUND }
            else profile.deviceCaps
            return profile.copy(
                browserAutonomy = false,
                browserAutonomyGrantedBackground = false,
                deviceCaps = capabilities,
            )
        }
        if (profile.browserAutonomy) return profile
        val alreadyGranted = DeviceCapPolicy.BACKGROUND in profile.deviceCaps
        return profile.copy(
            browserAutonomy = true,
            browserAutonomyGrantedBackground = !alreadyGranted,
            deviceCaps = if (alreadyGranted) profile.deviceCaps else (profile.deviceCaps + DeviceCapPolicy.BACKGROUND).distinct(),
        )
    }

    /** A direct user change to the general BACKGROUND control takes precedence over the derived grant. */
    fun setBackgroundEnabled(profile: AgentProfile, enabled: Boolean): AgentProfile = if (enabled) {
        profile.copy(
            deviceCaps = (profile.deviceCaps + DeviceCapPolicy.BACKGROUND).distinct(),
            browserAutonomyGrantedBackground = false,
        )
    } else {
        setEnabled(
            profile.copy(deviceCaps = profile.deviceCaps.filterNot { it == DeviceCapPolicy.BACKGROUND }),
            enabled = false,
        )
    }
}
