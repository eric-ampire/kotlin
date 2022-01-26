/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support

import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail

internal enum class ProcessLevelProperty(shortName: String) {
    KOTLIN_NATIVE_HOME("nativeHome"),
    COMPILER_CLASSPATH("compilerClasspath");

    private val propertyName = fullPropertyName(shortName)

    fun readValue(): String = System.getProperty(propertyName) ?: fail { "Unspecified $propertyName system property" }
}

internal enum class ClassLevelProperty(shortName: String) {
    TEST_TARGET("target"),
    TEST_MODE("mode"),
    OPTIMIZATION_MODE("optimizationMode"),
    MEMORY_MODEL("memoryModel"),
    USE_THREAD_STATE_CHECKER("useThreadStateChecker"),
    GC_TYPE("gcType"),
    CACHE_MODE("cacheMode"),
    EXECUTION_TIMEOUT("executionTimeout");

    internal val propertyName = fullPropertyName(shortName)

    fun <T> readValue(transform: (String) -> T?, default: T): T {
        val propertyValue = System.getProperty(propertyName)
        return if (propertyValue != null) {
            transform(propertyValue) ?: fail { "Invalid value for $propertyName system property: $propertyValue" }
        } else
            default
    }
}

internal inline fun <reified E : Enum<E>> ClassLevelProperty.readValue(values: Array<out E>, default: E): E {
    val optionName = System.getProperty(propertyName)
    return if (optionName != null) {
        values.firstOrNull { it.name == optionName } ?: fail {
            buildString {
                appendLine("Unknown ${E::class.java.simpleName} name $optionName.")
                appendLine("One of the following ${E::class.java.simpleName} should be passed through $propertyName system property:")
                values.forEach { value -> appendLine("- ${value.name}: $value") }
            }
        }
    } else
        default
}

private fun fullPropertyName(shortName: String) = "kotlin.internal.native.test.$shortName"

internal enum class EnvironmentVariable {
    PROJECT_BUILD_DIR;

    fun readValue(): String = System.getenv(name) ?: fail { "Unspecified $name environment variable" }
}
