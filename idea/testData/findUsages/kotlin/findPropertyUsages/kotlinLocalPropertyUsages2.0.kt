// PSI_ELEMENT: org.jetbrains.kotlin.psi.JetProperty
// OPTIONS: usages
fun foo(): String {
    if (true) {
        val <caret>bar = ""

        return bar
    }

    return bar
}

val x = bar
