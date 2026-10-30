package dev.cueopen.app

/** Avoid starting app-wide components in dedicated component processes such as :assistant. */
internal object MainProcessPolicy {
    fun isMainProcess(applicationProcessName: String?, currentProcessName: String?): Boolean =
        !applicationProcessName.isNullOrBlank() && currentProcessName == applicationProcessName
}
