package codes.cy.kit.seo

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.noScript
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

private const val DEFAULT_STATIC_MOUNT_PATH: String = "/static"
private const val DEFAULT_ROOT_PATH: String = "/"
private const val DEFAULT_ROOT_ELEMENT_ID: String = "app-root"
private const val DEFAULT_LANGUAGE: String = "en"
private const val SITEMAP_ROUTE_PATH: String = "/sitemap.xml"
private const val ROBOTS_ROUTE_PATH: String = "/robots.txt"
private const val INDEX_HTML_FILENAME: String = "index.html"
private const val SCRIPT_TYPE_MODULE: String = "module"
private const val APPLICATION_LD_JSON: String = "application/ld+json"
private const val VIEWPORT_CONTENT: String = "width=device-width, initial-scale=1.0"
private val ROOT_ELEMENT_ID_REGEX: Regex = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

/**
 * Immutable configuration for [installWasmSeoShell] and [wasmSeoRoutes].
 */
public data class WasmSpaShellConfig(
    public val staticDir: Path? = null,
    public val staticMountPath: String = DEFAULT_STATIC_MOUNT_PATH,
    public val spaRoutes: List<String> = listOf(DEFAULT_ROOT_PATH),
    public val rootElementId: String = DEFAULT_ROOT_ELEMENT_ID,
    public val language: String = DEFAULT_LANGUAGE,
    public val installAutoHeadResponse: Boolean = true,
    public val excludeIndexHtmlFromStatic: Boolean = true,
    public val preferredEntryName: String? = null,
    public val includeDefaultViewportStyles: Boolean = true,
    public val inlineStylesheet: String? = null,
    public val noscriptFallbackLinkHref: String? = null,
    public val noscriptFallbackLinkLabel: String? = null,
) {
    init {
        require(ROOT_ELEMENT_ID_REGEX.matches(rootElementId)) {
            "rootElementId must match ${ROOT_ELEMENT_ID_REGEX.pattern}, got '$rootElementId'."
        }
        require(language.isNotBlank()) { "language must not be blank." }
    }
}

/**
 * Side-effecting installer: ensures [AutoHeadResponse] is installed when
 * [WasmSpaShellConfig.installAutoHeadResponse] is enabled and registers dynamic SEO HTML shell,
 * static Wasm ES-module bundle serving, and optional `/sitemap.xml` + `/robots.txt` routes.
 */
public fun Application.installWasmSeoShell(
    config: WasmSpaShellConfig = WasmSpaShellConfig(),
    sitemapProvider: (suspend () -> List<SitemapEntry>)? = null,
    robotsProvider: (() -> String)? = null,
    resolveMetadata: suspend (ApplicationCall) -> SeoPageMetadata?,
) {
    if (config.installAutoHeadResponse && pluginOrNull(AutoHeadResponse) == null) {
        install(AutoHeadResponse)
    }
    routing {
        wasmSeoRoutes(
            config = config,
            sitemapProvider = sitemapProvider,
            robotsProvider = robotsProvider,
            resolveMetadata = resolveMetadata,
        )
    }
}

/**
 * Side-effecting route installer: mounts static Wasm ES-module assets (excluding `index.html`),
 * optional `/sitemap.xml` and `/robots.txt` endpoints, and dynamic SEO HTML shell routes.
 */
public fun Route.wasmSeoRoutes(
    config: WasmSpaShellConfig = WasmSpaShellConfig(),
    sitemapProvider: (suspend () -> List<SitemapEntry>)? = null,
    robotsProvider: (() -> String)? = null,
    resolveMetadata: suspend (ApplicationCall) -> SeoPageMetadata?,
) {
    if (config.installAutoHeadResponse && application.pluginOrNull(AutoHeadResponse) == null) {
        application.install(AutoHeadResponse)
    }

    val staticDir = config.staticDir
    val resolvedAssets = if (staticDir != null && Files.isDirectory(staticDir)) {
        resolveWasmShellAssets(
            staticDir = staticDir,
            mountPath = config.staticMountPath,
            preferredEntryName = config.preferredEntryName,
        )
    } else {
        WasmShellAssets()
    }

    if (staticDir != null && Files.isDirectory(staticDir)) {
        val staticIndex = if (config.excludeIndexHtmlFromStatic) null else INDEX_HTML_FILENAME
        staticFiles(
            remotePath = normalizeStaticMountPath(config.staticMountPath),
            dir = staticDir.toFile(),
            index = staticIndex,
        ) {
            if (config.excludeIndexHtmlFromStatic) {
                exclude { file -> file.name.equals(INDEX_HTML_FILENAME, ignoreCase = true) }
            }
        }
    }

    sitemapProvider?.let { provider ->
        get(SITEMAP_ROUTE_PATH) {
            call.respondText(
                text = renderSitemapXml(provider()),
                contentType = ContentType.Application.Xml,
                status = HttpStatusCode.OK,
            )
        }
    }

    robotsProvider?.let { provider ->
        get(ROBOTS_ROUTE_PATH) {
            call.respondText(
                text = provider(),
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.OK,
            )
        }
    }

    config.spaRoutes
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { spaRoute ->
            get(spaRoute) {
                val metadata = resolveMetadata(call)
                if (metadata == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondWasmSeoShell(
                        metadata = metadata,
                        assets = resolvedAssets,
                        config = config,
                    )
                }
            }
        }
}

/**
 * Side-effecting call responder: writes a semantic HTML5 Wasm SPA shell containing OpenGraph,
 * Twitter Card, canonical link, sanitized JSON-LD `<script type="application/ld+json">`,
 * ES-module / legacy scripts, stylesheets, and `<noscript>` fallback markup.
 */
public suspend fun ApplicationCall.respondWasmSeoShell(
    metadata: SeoPageMetadata,
    assets: WasmShellAssets = WasmShellAssets(),
    config: WasmSpaShellConfig = WasmSpaShellConfig(),
) {
    respondHtml(HttpStatusCode.OK) {
        lang = config.language
        head {
            applySeoHead(metadata)
            if (config.includeDefaultViewportStyles) {
                style { unsafe { +buildViewportStylesheet(config.rootElementId) } }
            }
            config.inlineStylesheet?.takeIf { it.isNotBlank() }?.let { customCss ->
                style { unsafe { +customCss } }
            }
            assets.stylesheetPaths.forEach { stylesheetPath ->
                link(rel = "stylesheet", href = stylesheetPath)
            }
        }
        body {
            div { id = config.rootElementId }
            assets.importMapLoaderPath?.let { loaderPath ->
                script(src = loaderPath) {}
            }
            assets.moduleEntryPath?.let { modulePath ->
                script(src = modulePath) {
                    attributes["type"] = SCRIPT_TYPE_MODULE
                }
            } ?: assets.legacyScriptPath?.let { legacyPath ->
                script(src = legacyPath) {}
            }
            noScript {
                val customNoscript = metadata.noscriptHtml?.takeIf { it.isNotBlank() }
                if (customNoscript != null) {
                    unsafe { +customNoscript }
                } else {
                    main {
                        h1 { +(metadata.noscriptHeading?.takeIf { it.isNotBlank() } ?: metadata.title) }
                        p { +(metadata.noscriptSummary?.takeIf { it.isNotBlank() } ?: metadata.description) }
                        val linkHref = config.noscriptFallbackLinkHref?.trim()?.takeIf { it.isNotEmpty() }
                        val linkLabel = config.noscriptFallbackLinkLabel?.trim()?.takeIf { it.isNotEmpty() }
                        if (linkHref != null && linkLabel != null) {
                            p {
                                a(href = linkHref) { +linkLabel }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Populates `<head>` with UTF-8 charset, responsive viewport, `<title>`, meta description,
 * optional robots directive, `rel="canonical"`, OpenGraph `og:*`, Twitter card tags, and
 * script-breakout-sanitized JSON-LD scripts.
 */
public fun HEAD.applySeoHead(metadata: SeoPageMetadata) {
    meta(charset = "UTF-8")
    meta(name = "viewport", content = VIEWPORT_CONTENT)
    title { +metadata.title }
    meta(name = "description", content = metadata.description)
    metadata.robotsDirective?.trim()?.takeIf { it.isNotEmpty() }?.let { robots ->
        meta(name = "robots", content = robots)
    }
    link(rel = "canonical", href = metadata.canonicalUrl)
    metaProperty(property = "og:title", value = metadata.title)
    metaProperty(property = "og:description", value = metadata.description)
    metaProperty(property = "og:type", value = metadata.ogType)
    metaProperty(property = "og:url", value = metadata.canonicalUrl)
    metadata.siteName?.trim()?.takeIf { it.isNotEmpty() }?.let { siteName ->
        metaProperty(property = "og:site_name", value = siteName)
    }
    metadata.locale?.trim()?.takeIf { it.isNotEmpty() }?.let { locale ->
        metaProperty(property = "og:locale", value = locale)
    }
    metadata.imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { imageUrl ->
        metaProperty(property = "og:image", value = imageUrl)
        metadata.imageAlt?.trim()?.takeIf { it.isNotEmpty() }?.let { alt ->
            metaProperty(property = "og:image:alt", value = alt)
        }
    }
    metadata.twitterCard.trim().takeIf { it.isNotEmpty() }?.let { card ->
        meta(name = "twitter:card", content = card)
        meta(name = "twitter:title", content = metadata.title)
        meta(name = "twitter:description", content = metadata.description)
        metadata.imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { imageUrl ->
            meta(name = "twitter:image", content = imageUrl)
            metadata.imageAlt?.trim()?.takeIf { it.isNotEmpty() }?.let { alt ->
                meta(name = "twitter:image:alt", content = alt)
            }
        }
    }
    metadata.jsonLdScripts
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::sanitizeJsonLdScript)
        .forEach { safeJsonLd ->
            script(type = APPLICATION_LD_JSON) {
                unsafe { +safeJsonLd }
            }
        }
}

private fun HEAD.metaProperty(property: String, value: String) {
    meta {
        attributes["property"] = property
        content = value
    }
}

private fun buildViewportStylesheet(rootElementId: String): String =
    """
    html, body, #$rootElementId {
        width: 100%;
        height: 100%;
        margin: 0;
        padding: 0;
        overflow: hidden;
    }
    """.trimIndent()
