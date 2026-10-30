package dev.cueopen.app.device

/** Password-like UI fields accept only values resolved from the task's secure vault. */
internal object AccessibilityInputPolicy {
    fun mayTypeIntoField(isPassword: Boolean, hasSecureSecret: Boolean): Boolean =
        !isPassword || hasSecureSecret
}
