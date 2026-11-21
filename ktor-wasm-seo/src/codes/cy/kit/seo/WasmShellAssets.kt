package codes.cy.kit.seo

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

private const val DEFAULT_STATIC_MOUNT_PATH: String = "/static"
private const val IMPORT_MAP_LOADER_FILENAME: String = "import-map-loader.js"
private const val PRIMARY_STYLESHEET_FILENAME: String = "styles.css"
private const val MJS_EXTENSION: String = ".mjs"
private const val JS_EXTENSION: String = ".js"
private const val CSS_EXTENSION: String = ".css"
private const val WASM_EXTENSION: String = ".wasm"
private const val SKIKO_STEM: String = "skiko"

private val SUPPORT_MJS_SUFFIXES: List<String> = listOf(
    ".import-object.mjs",
    ".js-builtins.mjs",
    ".uninstantiated.mjs",
)

private val COMMON_ENTRY_STEMS: List<String> = listOf(
    "webApp",
    "composeApp",
    "app",
    "main",
)

/**
 * Resolved URLs for a compiled Kotlin/Wasm web bundle served from a Ktor static mount.
 */
public data class WasmShellAssets(
    public val importMapLoaderPath: String? = null,
    public val moduleEntryPath: String? = null,
    public val legacyScriptPath: String? = null,
    public val stylesheetPaths: List<String> = emptyList(),
)

/**
 * Pure query: normalizes a static route mount path so root maps to `"/"` and non-root paths start
 * with a leading slash and omit trailing slashes.
 */
public fun normalizeStaticMountPath(mountPath: String): String {
    val clean = mountPath.trim().trim('/')
    return if (clean.isEmpty()) "/" else "/$clean"
}

/**
 * Pure query: inspects [staticDir] for Kotlin Toolchain `wasmJs` ES-module bundles
 * (`import-map-loader.js`, `<entry>.mjs`, stylesheets) and legacy `.js` fallback scripts,
 * prefixed with [mountPath]. Returns empty [WasmShellAssets] when [staticDir] is not a directory.
 */
public fun resolveWasmShellAssets(
    staticDir: Path,
    mountPath: String = DEFAULT_STATIC_MOUNT_PATH,
    preferredEntryName: String? = null,
): WasmShellAssets {
    if (!Files.isDirectory(staticDir)) {
        return WasmShellAssets()
    }

    val topLevelFiles = Files.list(staticDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .map { it.fileName.toString() }
            .sorted()
            .collect(Collectors.toList())
    }
    val fileSet = topLevelFiles.toSet()

    val wasmStems = topLevelFiles
        .asSequence()
        .filter { it.endsWith(WASM_EXTENSION, ignoreCase = true) }
        .map { it.dropLast(WASM_EXTENSION.length) }
        .filterNot { it.equals(SKIKO_STEM, ignoreCase = true) }
        .toSet()

    val importMapLoader = topLevelFiles
        .firstOrNull { it.equals(IMPORT_MAP_LOADER_FILENAME, ignoreCase = true) }
        ?.let { joinStaticAssetPath(mountPath, it) }

    val moduleEntry = selectModuleEntryFilename(
        files = topLevelFiles,
        fileSet = fileSet,
        wasmStems = wasmStems,
        preferredEntryName = preferredEntryName,
    )?.let { joinStaticAssetPath(mountPath, it) }

    val legacyScript = selectLegacyScriptFilename(
        files = topLevelFiles,
        fileSet = fileSet,
        wasmStems = wasmStems,
        preferredEntryName = preferredEntryName,
    )?.let { joinStaticAssetPath(mountPath, it) }

    val stylesheets = topLevelFiles
        .asSequence()
        .filter { it.endsWith(CSS_EXTENSION, ignoreCase = true) }
        .sortedWith(
            compareBy<String> { !it.equals(PRIMARY_STYLESHEET_FILENAME, ignoreCase = true) }
                .thenBy { it },
        )
        .map { joinStaticAssetPath(mountPath, it) }
        .toList()

    return WasmShellAssets(
        importMapLoaderPath = importMapLoader,
        moduleEntryPath = moduleEntry,
        legacyScriptPath = legacyScript,
        stylesheetPaths = stylesheets,
    )
}

private fun selectModuleEntryFilename(
    files: List<String>,
    fileSet: Set<String>,
    wasmStems: Set<String>,
    preferredEntryName: String?,
): String? {
    val candidates = files.filter(::isCandidateModuleEntry)
    if (candidates.isEmpty()) return null

    preferredEntryName?.trim()?.takeIf { it.isNotEmpty() }?.let { preferred ->
        val expected = if (preferred.endsWith(MJS_EXTENSION, ignoreCase = true)) {
            preferred
        } else {
            "$preferred$MJS_EXTENSION"
        }
        candidates.firstOrNull { it.equals(expected, ignoreCase = true) }?.let { return it }
    }

    candidates.firstOrNull { candidate ->
        val stem = candidate.dropLast(MJS_EXTENSION.length)
        wasmStems.any { it.equals(stem, ignoreCase = true) }
    }?.let { return it }

    COMMON_ENTRY_STEMS.forEach { stem ->
        val expected = "$stem$MJS_EXTENSION"
        if (expected in fileSet && isCandidateModuleEntry(expected)) {
            return expected
        }
    }

    return candidates.first()
}

private fun selectLegacyScriptFilename(
    files: List<String>,
    fileSet: Set<String>,
    wasmStems: Set<String>,
    preferredEntryName: String?,
): String? {
    val candidates = files.filter(::isCandidateLegacyScript)
    if (candidates.isEmpty()) return null

    preferredEntryName?.trim()?.takeIf { it.isNotEmpty() }?.let { preferred ->
        val expected = if (preferred.endsWith(JS_EXTENSION, ignoreCase = true)) {
            preferred
        } else {
            "$preferred$JS_EXTENSION"
        }
        candidates.firstOrNull { it.equals(expected, ignoreCase = true) }?.let { return it }
    }

    candidates.firstOrNull { candidate ->
        val stem = candidate.dropLast(JS_EXTENSION.length)
        wasmStems.any { it.equals(stem, ignoreCase = true) }
    }?.let { return it }

    COMMON_ENTRY_STEMS.forEach { stem ->
        val expected = "$stem$JS_EXTENSION"
        if (expected in fileSet && isCandidateLegacyScript(expected)) {
            return expected
        }
    }

    return candidates.first()
}

private fun isCandidateModuleEntry(filename: String): Boolean {
    if (!filename.endsWith(MJS_EXTENSION, ignoreCase = true)) return false
    val stem = filename.dropLast(MJS_EXTENSION.length)
    if (stem.equals(SKIKO_STEM, ignoreCase = true)) return false
    return SUPPORT_MJS_SUFFIXES.none { suffix -> filename.endsWith(suffix, ignoreCase = true) }
}

private fun isCandidateLegacyScript(filename: String): Boolean {
    if (!filename.endsWith(JS_EXTENSION, ignoreCase = true)) return false
    if (filename.equals(IMPORT_MAP_LOADER_FILENAME, ignoreCase = true)) return false
    val stem = filename.dropLast(JS_EXTENSION.length)
    return !stem.equals(SKIKO_STEM, ignoreCase = true)
}

private fun joinStaticAssetPath(mountPath: String, filename: String): String {
    val normalizedMount = normalizeStaticMountPath(mountPath)
    return if (normalizedMount == "/") "/$filename" else "$normalizedMount/$filename"
}
