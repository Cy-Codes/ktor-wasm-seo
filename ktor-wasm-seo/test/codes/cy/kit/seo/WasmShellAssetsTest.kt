package codes.cy.kit.seo

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmShellAssetsTest {

    @Test
    fun `resolveWasmShellAssets returns empty assets when directory does not exist`() {
        val missing = createTempDirectory("wasm-seo-missing").also { it.toFile().deleteRecursively() }
        assertEquals(WasmShellAssets(), resolveWasmShellAssets(missing))
    }

    @Test
    fun `resolveWasmShellAssets detects modern ES-module bundle layout`() {
        val tempDir = createTempDirectory("wasm-seo-modern")
        try {
            Files.writeString(tempDir.resolve("index.html"), "<html></html>")
            Files.writeString(tempDir.resolve("import-map-loader.js"), "// loader")
            Files.writeString(tempDir.resolve("app.mjs"), "export default {};")
            Files.writeString(tempDir.resolve("styles.css"), "body {}")

            val assets = resolveWasmShellAssets(tempDir)
            assertEquals("/static/import-map-loader.js", assets.importMapLoaderPath)
            assertEquals("/static/app.mjs", assets.moduleEntryPath)
            assertNull(assets.legacyScriptPath)
            assertEquals(listOf("/static/styles.css"), assets.stylesheetPaths)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveWasmShellAssets selects real entry mjs over skiko and compiler support mjs files`() {
        val tempDir = createTempDirectory("wasm-seo-toolchain")
        try {
            Files.writeString(tempDir.resolve("index.html"), "<html></html>")
            Files.writeString(tempDir.resolve("import-map-loader.js"), "// loader")
            Files.writeString(tempDir.resolve("skiko.mjs"), "// skiko")
            Files.writeString(tempDir.resolve("skiko.wasm"), "wasm")
            Files.writeString(tempDir.resolve("clientApp.import-object.mjs"), "// imports")
            Files.writeString(tempDir.resolve("clientApp.js-builtins.mjs"), "// builtins")
            Files.writeString(tempDir.resolve("clientApp.mjs"), "// entry")
            Files.writeString(tempDir.resolve("clientApp.wasm"), "wasm")
            Files.writeString(tempDir.resolve("extra.css"), "h1 {}")
            Files.writeString(tempDir.resolve("styles.css"), "body {}")

            val assets = resolveWasmShellAssets(tempDir, mountPath = "/")
            assertEquals("/import-map-loader.js", assets.importMapLoaderPath)
            assertEquals("/clientApp.mjs", assets.moduleEntryPath)
            assertNull(assets.legacyScriptPath)
            assertEquals(listOf("/styles.css", "/extra.css"), assets.stylesheetPaths)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveWasmShellAssets detects legacy js layout without import-map-loader`() {
        val tempDir = createTempDirectory("wasm-seo-legacy")
        try {
            Files.writeString(tempDir.resolve("index.html"), "<html></html>")
            Files.writeString(tempDir.resolve("app.js"), "console.log('legacy');")
            Files.writeString(tempDir.resolve("styles.css"), "body {}")

            val assets = resolveWasmShellAssets(tempDir, mountPath = "/assets/")
            assertNull(assets.importMapLoaderPath)
            assertNull(assets.moduleEntryPath)
            assertEquals("/assets/app.js", assets.legacyScriptPath)
            assertEquals(listOf("/assets/styles.css"), assets.stylesheetPaths)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveWasmShellAssets respects preferredEntryName when multiple modules exist`() {
        val tempDir = createTempDirectory("wasm-seo-preferred")
        try {
            Files.writeString(tempDir.resolve("app.mjs"), "export default 'app';")
            Files.writeString(tempDir.resolve("customApp.mjs"), "export default 'custom';")

            val assets = resolveWasmShellAssets(
                staticDir = tempDir,
                mountPath = "/static",
                preferredEntryName = "customApp",
            )
            assertEquals("/static/customApp.mjs", assets.moduleEntryPath)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
