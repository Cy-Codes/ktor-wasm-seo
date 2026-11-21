package codes.cy.kit.sample.seo

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleSeoServerTest {

    @Test
    fun `sample server exposes health, sitemap, robots, dynamic article SEO, and HEAD on wasm assets`() = testApplication {
        val tempStaticDir = createTempDirectory("sample-seo-wasm-static")
        try {
            Files.writeString(tempStaticDir.resolve("index.html"), "<html><body>raw-static</body></html>")
            Files.writeString(tempStaticDir.resolve("import-map-loader.js"), "// import map loader")
            Files.writeString(tempStaticDir.resolve("app.mjs"), "export default {};")
            Files.writeString(tempStaticDir.resolve("styles.css"), "html, body { margin: 0; }")

            application {
                configureSampleSeoServer(
                    config = SampleSeoConfig(
                        baseUrl = "https://docs.example.org",
                        staticDir = tempStaticDir,
                    ),
                )
            }

            val healthResp = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthResp.status)
            assertEquals("""{"status":"UP"}""", healthResp.bodyAsText())

            val sitemapResp = client.get("/sitemap.xml")
            assertEquals(HttpStatusCode.OK, sitemapResp.status)
            assertEquals(ContentType.Application.Xml.withoutParameters(), sitemapResp.contentType()?.withoutParameters())
            val sitemapBody = sitemapResp.bodyAsText()
            assertTrue(sitemapBody.contains("https://docs.example.org/"))
            assertTrue(sitemapBody.contains("https://docs.example.org/articles/demo"))
            assertTrue(sitemapBody.contains("<lastmod>2026-11-20</lastmod>"))

            val robotsResp = client.get("/robots.txt")
            assertEquals(HttpStatusCode.OK, robotsResp.status)
            assertEquals(ContentType.Text.Plain.withoutParameters(), robotsResp.contentType()?.withoutParameters())
            val robotsBody = robotsResp.bodyAsText()
            assertTrue(robotsBody.contains("Allow: /"))
            assertTrue(robotsBody.contains("Disallow: /internal/"))
            assertTrue(robotsBody.contains("Sitemap: https://docs.example.org/sitemap.xml"))

            val demoResp = client.get("/articles/demo")
            assertEquals(HttpStatusCode.OK, demoResp.status)
            val demoHtml = demoResp.bodyAsText()
            assertTrue(demoHtml.contains("Dynamic SEO Shells for Kotlin/Wasm"))
            assertTrue(demoHtml.contains("""href="https://docs.example.org/articles/demo""""))
            assertTrue(demoHtml.contains("""property="og:type" content="article""""))
            assertTrue(demoHtml.contains("""<script src="/static/import-map-loader.js"></script>"""))
            assertTrue(demoHtml.contains("""<script src="/static/app.mjs" type="module"></script>"""))
            assertTrue(demoHtml.contains("""<link href="/static/styles.css" rel="stylesheet">"""))

            val missingResp = client.get("/articles/does-not-exist")
            assertEquals(HttpStatusCode.NotFound, missingResp.status)

            val staticAssetGet = client.get("/static/app.mjs")
            assertEquals(HttpStatusCode.OK, staticAssetGet.status)
            assertEquals("export default {};", staticAssetGet.bodyAsText())

            val staticAssetHead = client.head("/static/app.mjs")
            assertEquals(HttpStatusCode.OK, staticAssetHead.status)

            val excludedIndex = client.get("/static/index.html")
            assertEquals(HttpStatusCode.Forbidden, excludedIndex.status)
            assertFalse(excludedIndex.bodyAsText().contains("raw-static"))
            assertFalse(demoHtml.contains("raw-static"))
        } finally {
            tempStaticDir.toFile().deleteRecursively()
        }
    }
}
