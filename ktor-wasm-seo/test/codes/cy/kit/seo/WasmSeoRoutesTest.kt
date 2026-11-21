package codes.cy.kit.seo

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class WasmSeoRoutesTest {

    @Test
    fun `installWasmSeoShell serves SEO shell, modern ES-module assets, HEAD 200, and excludes index_html`() = testApplication {
        val tempDir = createTempDirectory("wasm-seo-routes-modern")
        try {
            Files.writeString(tempDir.resolve("index.html"), "<html><body>static-index</body></html>")
            Files.writeString(tempDir.resolve("import-map-loader.js"), "// import-map")
            Files.writeString(tempDir.resolve("app.mjs"), "export const ready = true;")
            Files.writeString(tempDir.resolve("styles.css"), "body { margin: 0; }")

            application {
                installWasmSeoShell(
                    config = WasmSpaShellConfig(
                        staticDir = tempDir,
                        staticMountPath = "/static",
                        spaRoutes = listOf("/", "/articles/{slug}"),
                        noscriptFallbackLinkHref = "/sitemap.xml",
                        noscriptFallbackLinkLabel = "Browse XML Sitemap",
                    ),
                    sitemapProvider = {
                        listOf(
                            SitemapEntry(loc = "https://example.com/", priority = "1.0"),
                            SitemapEntry(loc = "https://example.com/articles/demo", lastModified = "2026-11-20"),
                        )
                    },
                    robotsProvider = {
                        renderRobotsTxt(
                            sitemapUrl = "https://example.com/sitemap.xml",
                            disallowPaths = listOf("/internal/"),
                        )
                    },
                ) { call ->
                    val slug = call.parameters["slug"]
                    val jsonLd = encodeSafeJsonLd(
                        buildJsonObject {
                            put("@context", JsonPrimitive("https://schema.org"))
                            put("@type", JsonPrimitive("WebPage"))
                            put("name", JsonPrimitive("Safe </script> Title"))
                        },
                    )
                    when (slug) {
                        null -> SeoPageMetadata(
                            title = "Example Docs & Guides",
                            description = "Reference documentation shell",
                            canonicalUrl = "https://example.com/",
                            ogType = "website",
                            siteName = "Example Docs",
                            imageUrl = "https://example.com/og.png",
                            imageAlt = "Example social preview",
                            twitterCard = "summary_large_image",
                            jsonLdScripts = listOf(jsonLd),
                        )
                        "demo" -> SeoPageMetadata(
                            title = "Demo Article",
                            description = "Dynamic route metadata",
                            canonicalUrl = "https://example.com/articles/demo",
                            ogType = "article",
                            jsonLdScripts = listOf("""{"raw":"</script>"}"""),
                            noscriptHtml = """<main><h1>Static Demo Fallback</h1></main>""",
                        )
                        else -> null
                    }
                }
            }

            val homeResponse = client.get("/")
            assertEquals(HttpStatusCode.OK, homeResponse.status)
            val homeHtml = homeResponse.bodyAsText()
            assertTrue(homeHtml.contains("<!DOCTYPE html>"))
            assertTrue(homeHtml.contains("<title>Example Docs &amp; Guides</title>"))
            assertTrue(homeHtml.contains("""rel="canonical""""))
            assertTrue(homeHtml.contains("""href="https://example.com/""""))
            assertTrue(homeHtml.contains("""property="og:title""""))
            assertTrue(homeHtml.contains("""property="og:description""""))
            assertTrue(homeHtml.contains("""property="og:type" content="website""""))
            assertTrue(homeHtml.contains("""property="og:site_name" content="Example Docs""""))
            assertTrue(homeHtml.contains("""property="og:image" content="https://example.com/og.png""""))
            assertTrue(homeHtml.contains("""name="twitter:card" content="summary_large_image""""))
            assertTrue(homeHtml.contains("""name="twitter:title" content="Example Docs &amp; Guides""""))
            assertTrue(homeHtml.contains("""<script type="application/ld+json">"""))
            assertTrue(homeHtml.contains("""Safe <\/script> Title"""))
            assertFalse(homeHtml.contains("Safe </script> Title"))
            assertTrue(homeHtml.contains("<noscript>"))
            assertTrue(homeHtml.contains("Browse XML Sitemap"))
            assertTrue(homeHtml.contains("""id="app-root""""))
            assertTrue(homeHtml.contains("""<link href="/static/styles.css" rel="stylesheet">"""))
            assertTrue(homeHtml.contains("""<script src="/static/import-map-loader.js"></script>"""))
            assertTrue(homeHtml.contains("""<script src="/static/app.mjs" type="module"></script>"""))

            val demoResponse = client.get("/articles/demo")
            assertEquals(HttpStatusCode.OK, demoResponse.status)
            val demoHtml = demoResponse.bodyAsText()
            assertTrue(demoHtml.contains("""<main><h1>Static Demo Fallback</h1></main>"""))
            assertTrue(demoHtml.contains("""{"raw":"<\/script>"}"""))

            val missingArticleResponse = client.get("/articles/nonexistent")
            assertEquals(HttpStatusCode.NotFound, missingArticleResponse.status)

            val assetGet = client.get("/static/app.mjs")
            assertEquals(HttpStatusCode.OK, assetGet.status)
            assertEquals("export const ready = true;", assetGet.bodyAsText())

            val assetHead = client.head("/static/app.mjs")
            assertEquals(HttpStatusCode.OK, assetHead.status)

            val homeHead = client.head("/")
            assertEquals(HttpStatusCode.OK, homeHead.status)

            val excludedIndex = client.get("/static/index.html")
            assertEquals(HttpStatusCode.Forbidden, excludedIndex.status)
            assertFalse(excludedIndex.bodyAsText().contains("static-index"))
            assertEquals(HttpStatusCode.NotFound, client.get("/static/").status)

            val sitemapResponse = client.get("/sitemap.xml")
            assertEquals(HttpStatusCode.OK, sitemapResponse.status)
            assertEquals(ContentType.Application.Xml.withoutParameters(), sitemapResponse.contentType()?.withoutParameters())
            assertTrue(sitemapResponse.bodyAsText().contains("https://example.com/articles/demo"))

            val robotsResponse = client.get("/robots.txt")
            assertEquals(HttpStatusCode.OK, robotsResponse.status)
            assertEquals(ContentType.Text.Plain.withoutParameters(), robotsResponse.contentType()?.withoutParameters())
            assertTrue(robotsResponse.bodyAsText().contains("Disallow: /internal/"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `wasmSeoRoutes supports legacy bundle fallback and disabling AutoHeadResponse`() = testApplication {
        val tempDir = createTempDirectory("wasm-seo-routes-legacy")
        try {
            Files.writeString(tempDir.resolve("app.js"), "console.log('legacy');")
            Files.writeString(tempDir.resolve("index.html"), "<html>static</html>")

            application {
                routing {
                    wasmSeoRoutes(
                        config = WasmSpaShellConfig(
                            staticDir = tempDir,
                            staticMountPath = "/",
                            spaRoutes = listOf("/"),
                            installAutoHeadResponse = false,
                        ),
                    ) {
                        SeoPageMetadata(
                            title = "Legacy Shell",
                            description = "Legacy fallback",
                            canonicalUrl = "https://example.com/",
                        )
                    }
                }
            }

            val html = client.get("/").bodyAsText()
            assertTrue(html.contains("""<script src="/app.js"></script>"""))
            assertFalse(html.contains("""type="module""""))
            assertFalse(html.contains("static"))

            assertEquals(HttpStatusCode.OK, client.get("/app.js").status)
            assertEquals(HttpStatusCode.NotFound, client.head("/app.js").status)
            val excludedRootIndex = client.get("/index.html")
            assertEquals(HttpStatusCode.Forbidden, excludedRootIndex.status)
            assertFalse(excludedRootIndex.bodyAsText().contains("static"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `wasmSeoRoutes installs AutoHeadResponse when invoked directly inside routing`() = testApplication {
        val tempDir = createTempDirectory("wasm-seo-routes-direct-head")
        try {
            Files.writeString(tempDir.resolve("app.mjs"), "export default 1;")
            application {
                routing {
                    wasmSeoRoutes(
                        config = WasmSpaShellConfig(
                            staticDir = tempDir,
                            staticMountPath = "/static",
                            spaRoutes = listOf("/"),
                            installAutoHeadResponse = true,
                        ),
                    ) {
                        SeoPageMetadata(
                            title = "Direct Route Shell",
                            description = "Direct routing registration",
                            canonicalUrl = "https://example.com/",
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.get("/static/app.mjs").status)
            assertEquals(HttpStatusCode.OK, client.head("/static/app.mjs").status)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
