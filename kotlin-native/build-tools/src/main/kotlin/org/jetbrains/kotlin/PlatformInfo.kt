package org.jetbrains.kotlin

import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.*
import org.gradle.api.Project

object PlatformInfo {
    @JvmStatic
    fun isMac() = HostManager.hostIsMac
    @JvmStatic
    fun isWindows() = HostManager.hostIsMingw
    @JvmStatic
    fun isLinux() = HostManager.hostIsLinux

    @JvmStatic
    val hostName: String
        get() = HostManager.hostName

    @JvmStatic
    fun isAppleTarget(project: Project): Boolean {
        val target = getTarget(project)
        return target.family.isAppleFamily
    }

    @JvmStatic
    fun isAppleTarget(target: KonanTarget): Boolean {
        return target.family.isAppleFamily
    }

    @JvmStatic
    fun isWindowsTarget(project: Project) = getTarget(project).family == Family.MINGW

    @JvmStatic
    fun getTarget(project: Project): KonanTarget {
        val platformManager = project.platformManager
        val targetName = project.project.testTarget.name
        return platformManager.targetManager(targetName).target
    }

    @JvmStatic
    fun needSmallBinary(project: Project): Boolean {
        return getTarget(project).needSmallBinary()
    }

    @JvmStatic
    fun isK2(project: Project): Boolean {
        val idx = project.globalTestArgs.indexOf("-language-version")
        if (idx == -1) return true
        return project.globalTestArgs[idx + 1].toDouble() >= 2.0
    }

    @JvmStatic
    fun supportsLibBacktrace(project: Project): Boolean {
        return getTarget(project).supportsLibBacktrace()
    }

    @JvmStatic
    fun supportsCoreSymbolication(project: Project): Boolean {
        return getTarget(project).supportsCoreSymbolication()
    }

    @JvmStatic
    fun checkXcodeVersion(project: Project) {
        val properties = PropertiesProvider(project)
        val requiredMajorVersion = properties.xcodeMajorVersion

        if (!DependencyProcessor.isInternalSeverAvailable
                && properties.checkXcodeVersion
                && requiredMajorVersion != null
        ) {
            val currentXcodeVersion = Xcode.findCurrent().version.toString()
            val currentMajorVersion = currentXcodeVersion.splitToSequence('.').first()
            if (currentMajorVersion != requiredMajorVersion) {
                throw IllegalStateException(
                        "Incorrect Xcode version: ${currentXcodeVersion}. Required major Xcode version is ${requiredMajorVersion}."
                                + "\nYou can try '-PcheckXcodeVersion=false' to suppress this error, the result might be wrong."
                )
            }
        }
    }

    fun unsupportedPlatformException() = TargetSupportException()
}
