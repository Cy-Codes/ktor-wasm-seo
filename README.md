# ktor-wasm-seo

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Domain-agnostic Ktor JVM library providing dynamic per-route SEO HTML shells, script-breakout-safe JSON-LD encoding, XML-escaped `sitemap.xml` & `robots.txt` generators, and Kotlin Toolchain `wasmJs` ES-module bundle resolution + static serving.

Source repository: <https://github.com/cy-codes/ktor-wasm-seo>

---

## Maven Coordinate

| Module | Coordinates | Target | Purpose |
| :--- | :--- | :--- | :--- |
| **`ktor-wasm-seo`** | `codes.cy.kit:ktor-wasm-seo:0.1.0` | `jvm` | SEO metadata models, safe JSON-LD encoder, `sitemap.xml` / `robots.txt` renderers, `wasmJs` asset resolver, and Ktor HTML shell + `staticFiles` routing |

`ktor-wasm-seo` marks `$ktor.server.core`, `$ktor.server.htmlBuilder`, and `$kotlin.serialization.json` as `: exported` in `module.yaml` so downstream Ktor servers transitively see `Application`, `ApplicationCall`, `Route`, `HEAD`, `Json`, and `JsonElement`.

---

## Public API Overview (`codes.cy.kit.seo`)

### SEO Metadata, Safe JSON-LD, Sitemap & Robots (`SeoMetadata.kt`)

- **`SeoPageMetadata`**: Immutable `data class` holding `title`, `description`, `canonicalUrl`, `ogType` (default `"website"`), `siteName`, `imageUrl`, `imageAlt`, `twitterCard` (default `"summary"`), `locale`, `robotsDirective`, `jsonLdScripts: List<String>`, `noscriptHeading`, `noscriptSummary`, and `noscriptHtml`.
- **`DefaultSeoJson`**: Immutable `Json` instance (`encodeDefaults = true`, `ignoreUnknownKeys = true`, `explicitNulls = false`).
- **Safe JSON-LD Helpers**:
  - `sanitizeJsonLdScript(rawJson)` — replaces `</` with `<\/` and `<!--` with `\u003c!--` using valid RFC 8259 JSON escapes to prevent `</script>` tag breakout injection.
  - `encodeSafeJsonLd(jsonElement, json = DefaultSeoJson)` & `encodeSafeJsonLd(serializer, value, json = DefaultSeoJson)`
  - `buildSafeJsonLdScript(...)` overloads wrapping escaped JSON-LD in `<script type="application/ld+json">...</script>`.
  - `buildWebPageJsonLd(...)` & `buildArticleJsonLd(...)` — Schema.org `JsonObject` builders with zero domain coupling.
- **Canonical URL, Sitemap & Robots**:
  - `buildCanonicalUrl(baseUrl, path)`
  - `SitemapEntry(loc, lastModified = null, changeFreq = null, priority = null)`
  - `renderSitemapXml(entries)` — XML-escapes `&`, `<`, `>`, `"`, `'`, deduplicates by `loc`, and omits null/blank optional tags.
  - `renderRobotsTxt(sitemapUrl, disallowPaths = emptyList(), allowAll = true)` — strips line breaks and emits crawler directives.

### Wasm ES-Module Asset Resolver (`WasmShellAssets.kt`)

- **`WasmShellAssets(importMapLoaderPath, moduleEntryPath, legacyScriptPath, stylesheetPaths)`**: Resolved static asset URLs.
- **`resolveWasmShellAssets(staticDir, mountPath = "/static", preferredEntryName = null)`**:
  - Auto-detects Kotlin Toolchain modern ES-module bundles (`import-map-loader.js`, `<entry>.mjs` matching `<entry>.wasm` while ignoring `skiko.mjs` and `*.import-object.mjs` / `*.js-builtins.mjs` / `*.uninstantiated.mjs` support files, and `styles.css` + extra `.css` files).
  - Falls back to legacy `.js` entry scripts when no ES-module entry exists, excluding `import-map-loader.js` and `index.html`.
- **`normalizeStaticMountPath(mountPath)`**: Normalizes static mount prefixes (`"/"`, `"/static"`, etc.).

### Ktor SEO Shell & Static Serving (`WasmSeoRoutes.kt`)

- **`WasmSpaShellConfig`**: Immutable config for `staticDir`, `staticMountPath`, `spaRoutes`, `rootElementId` (default `"app-root"`), `language`, `installAutoHeadResponse`, `excludeIndexHtmlFromStatic`, `preferredEntryName`, `includeDefaultViewportStyles`, `inlineStylesheet`, and `<noscript>` fallback link options.
- **`Application.installWasmSeoShell(config, sitemapProvider, robotsProvider, resolveMetadata)`**: Installs `AutoHeadResponse` idempotently (when enabled) and mounts static files, optional `/sitemap.xml` & `/robots.txt`, and dynamic SPA shell routes.
- **`Route.wasmSeoRoutes(...)`**: Route-scoped equivalent for mounting inside existing `routing { ... }` blocks.
- **`ApplicationCall.respondWasmSeoShell(metadata, assets, config)`** & **`HEAD.applySeoHead(metadata)`**: Reusable HTML5 & `<head>` tag helpers (`<title>`, `description`, `rel="canonical"`, OpenGraph `og:*`, Twitter Card `twitter:*`, script-safe JSON-LD, `<noscript>`, stylesheets, `import-map-loader.js`, and `<script type="module">` / legacy `<script>`).

---

## Usage

### 1. Dynamic Wasm SEO Shell + Sitemap + Robots

```kotlin
import codes.cy.kit.seo.SeoPageMetadata
import codes.cy.kit.seo.SitemapEntry
import codes.cy.kit.seo.WasmSpaShellConfig
import codes.cy.kit.seo.buildArticleJsonLd
import codes.cy.kit.seo.buildCanonicalUrl
import codes.cy.kit.seo.buildWebPageJsonLd
import codes.cy.kit.seo.encodeSafeJsonLd
import codes.cy.kit.seo.installWasmSeoShell
import codes.cy.kit.seo.renderRobotsTxt
import io.ktor.server.application.Application
import java.nio.file.Path

fun Application.configureWebApp(staticDir: Path, baseUrl: String) {
    installWasmSeoShell(
        config = WasmSpaShellConfig(
            staticDir = staticDir,
            staticMountPath = "/static",
            spaRoutes = listOf("/", "/articles/{slug}"),
            noscriptFallbackLinkHref = "/sitemap.xml",
            noscriptFallbackLinkLabel = "Browse XML Sitemap",
        ),
        sitemapProvider = {
            listOf(
                SitemapEntry(loc = buildCanonicalUrl(baseUrl, "/"), priority = "1.0"),
                SitemapEntry(loc = buildCanonicalUrl(baseUrl, "/articles/demo"), lastModified = "2026-11-20"),
            )
        },
        robotsProvider = {
            renderRobotsTxt(
                sitemapUrl = buildCanonicalUrl(baseUrl, "/sitemap.xml"),
                disallowPaths = listOf("/internal/"),
            )
        },
    ) { call ->
        val slug = call.parameters["slug"]
        if (slug == null) {
            val url = buildCanonicalUrl(baseUrl, "/")
            SeoPageMetadata(
                title = "Docs & Guides",
                description = "Interactive Kotlin/Wasm documentation.",
                canonicalUrl = url,
                jsonLdScripts = listOf(encodeSafeJsonLd(buildWebPageJsonLd("Docs & Guides", "Interactive Kotlin/Wasm documentation.", url))),
            )
        } else {
            val url = buildCanonicalUrl(baseUrl, "/articles/$slug")
            SeoPageMetadata(
                title = "Article: $slug",
                description = "Rendered with dynamic OpenGraph and JSON-LD metadata.",
                canonicalUrl = url,
                ogType = "article",
                jsonLdScripts = listOf(encodeSafeJsonLd(buildArticleJsonLd(slug, "Article summary", url))),
            )
        }
    }
}
```

### 2. Composing with `markdown-islands-core` (`HtmlMarkdownRenderer`) for `/read/{slug}` SSR

`ktor-wasm-seo` intentionally carries **zero** dependencies on other `codes.cy.kit` libraries. Applications that want static HTML reader routes alongside their interactive Wasm shell can compose `applySeoHead(...)` directly with `codes.cy.kit.markdown.HtmlMarkdownRenderer`:

```kotlin
import codes.cy.kit.markdown.HtmlMarkdownRenderer
import codes.cy.kit.seo.SeoPageMetadata
import codes.cy.kit.seo.applySeoHead
import codes.cy.kit.seo.buildArticleJsonLd
import codes.cy.kit.seo.buildCanonicalUrl
import codes.cy.kit.seo.encodeSafeJsonLd
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.main
import kotlinx.html.unsafe

fun Route.readerViewRoute(
    baseUrl: String,
    markdownRenderer: HtmlMarkdownRenderer,
    loadArticle: suspend (String) -> Pair<String, String>?, // (title, markdownBody)
) {
    get("/read/{slug}") {
        val slug = call.parameters["slug"].orEmpty()
        val (title, markdown) = loadArticle(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val canonicalUrl = buildCanonicalUrl(baseUrl, "/read/$slug")
        val metadata = SeoPageMetadata(
            title = title,
            description = "Static HTML reader view for $title",
            canonicalUrl = canonicalUrl,
            ogType = "article",
            jsonLdScripts = listOf(
                encodeSafeJsonLd(buildArticleJsonLd(headline = title, description = title, url = canonicalUrl)),
            ),
        )
        val bodyHtml = markdownRenderer.renderToHtml(markdown)
        call.respondHtml(HttpStatusCode.OK) {
            lang = "en"
            head { applySeoHead(metadata) }
            body {
                main {
                    article {
                        h1 { +title }
                        div { unsafe { +bodyHtml } }
                    }
                }
            }
        }
    }
}
```

---

## Preserved Production Invariants

1. **Invariant 8 — `AutoHeadResponse` + `index.html` Exclusion:**
   - Ktor's `staticFiles` responds `404 Not Found` to `HTTP HEAD` probes by default unless `AutoHeadResponse` is installed. `installWasmSeoShell` and `wasmSeoRoutes` install `AutoHeadResponse` idempotently by default (`installAutoHeadResponse = true`) so CDN, uptime, and load-balancer `HEAD` checks on `/static/*.mjs` and SPA routes return `200 OK`.
   - `staticFiles` mounts with `index = null` and `exclude { it.name.equals("index.html", ignoreCase = true) }` (when `excludeIndexHtmlFromStatic = true`) so raw `index.html` files in the compiled Wasm directory are blocked (`403 Forbidden`) and never shadow dynamic SEO shell routes.
2. **Script Breakout Protection:** `encodeSafeJsonLd`, `buildSafeJsonLdScript`, and `HEAD.applySeoHead` escape `</` (`<\/`) and `<!--` (`\u003c!--`) in JSON-LD payloads so user or CMS strings containing `</script>` cannot break out of `<script type="application/ld+json">`.
3. **Invariant 4 — `: exported` Propagation:** `$ktor.server.core`, `$ktor.server.htmlBuilder`, and `$kotlin.serialization.json` are marked `: exported` so consumer modules do not fail resolving transitive API types.
4. **Strict Explicit API & Zero Domain Leakage:** `explicitApi: strict` is enabled on `ktor-wasm-seo`, every public symbol carries KDoc, dependencies/resolvers are injected via parameters, and zero domain-specific nouns appear anywhere in the library.

---

## Build, Test, Run & Publish

```bash
# Build ktor-wasm-seo and sample-server
./kotlin build

# Run unit and Ktor testApplication suites
./kotlin test

# Launch sample Netty server (configurable via SERVER_HOST, SERVER_PORT, SITE_BASE_URL, WEB_STATIC_DIR)
./kotlin run -m sample-server

# Publish ktor-wasm-seo (0.1.0 + sources + javadocs + POM) to ~/.m2/repository
./kotlin publish mavenLocal -m ktor-wasm-seo
```

### Verification Results

- `./kotlin build`: `Build successful` (10 tasks across `ktor-wasm-seo` and `sample-server`).
- `./kotlin test`: `16 passed, 0 failed`:
  - `ktor-wasm-seo`: `15 passed` (`SeoMetadataTest`: `7`, `WasmShellAssetsTest`: `5`, `WasmSeoRoutesTest`: `3`)
  - `sample-server`: `1 passed` (`SampleSeoServerTest`: `1`)
- `./kotlin publish mavenLocal -m ktor-wasm-seo`: `Publication successful` — verified `codes.cy.kit:ktor-wasm-seo:0.1.0` (`.jar`, `-sources.jar`, `-javadoc.jar`, `.module`, `.pom` with `<url>https://github.com/cy-codes/ktor-wasm-seo</url>`) under `~/.m2/repository/codes/cy/kit/ktor-wasm-seo/0.1.0/`.
