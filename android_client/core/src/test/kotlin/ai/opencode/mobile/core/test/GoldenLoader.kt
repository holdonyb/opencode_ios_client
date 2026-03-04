package ai.opencode.mobile.core.test

import java.io.File

object GoldenLoader {
    fun load(name: String): String {
        val candidates = listOf(
            File("../mobile_contract/golden/$name"),
            File("../../mobile_contract/golden/$name"),
            File("mobile_contract/golden/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Golden file not found: $name")
        return file.readText()
    }
}

