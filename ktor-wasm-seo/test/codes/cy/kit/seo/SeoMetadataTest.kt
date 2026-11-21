package codes.cy.kit.seo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SeoMetadataTest {

    @Serializable
    private data class SampleSchemaPayload(
        val headline: String,
        val note: String,
    )

    @Test
    fun `encodeSafeJsonLd escapes script closing variants and HTML comments while staying valid JSON`() {
        val rawPayload = buildJsonObject {
            put("title", JsonPrimitive("Breakout </script><script>alert(1)</script>"))
            put("upper", JsonPrimitive("Test </SCRIPT> and </ScRiPt>"))
            put("comment", JsonPrimitive("<!-- comment -->"))
            put("nested", JsonPrimitive("a</b"))
        }

        val encoded = encodeSafeJsonLd(rawPayload)
        assertFalse(encoded.contains("</script>", ignoreCase = true))
        assertFalse(encoded.contains("</"))
        assertFalse(encoded.contains("<!--"))
        assertTrue(encoded.contains("<\\/script>"))
        assertTrue(encoded.contains("<\\/SCRIPT>"))
        assertTrue(encoded.contains("\\u003c!--"))

        val reparsed = DefaultSeoJson.parseToJsonElement(encoded).jsonObject
        assertEquals(
            "Breakout </script><script>alert(1)</script>",
            reparsed["title"]?.jsonPrimitive?.content,
        )
        assertEquals("<!-- comment -->", reparsed["comment"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildSafeJsonLdScript wraps JsonElement, serializable models, and raw JSON safely`() {
        val model = SampleSchemaPayload(
            headline = "Ktor Wasm Guide",
            note = "Avoid </script> tags",
        )
        val fromModel = buildSafeJsonLdScript(SampleSchemaPayload.serializer(), model)
        assertTrue(fromModel.startsWith("""<script type="application/ld+json">"""))
        assertTrue(fromModel.endsWith("</script>"))
        val innerJson = fromModel
            .removePrefix("""<script type="application/ld+json">""")
            .removeSuffix("</script>")
        assertFalse(innerJson.contains("</"))
        assertTrue(innerJson.contains("<\\/script>"))

        val fromRaw = buildSafeJsonLdScript("""{"danger":"</script>"}""")
        assertEquals(
            """<script type="application/ld+json">{"danger":"<\/script>"}</script>""",
            fromRaw,
        )
    }

    @Test
    fun `buildWebPageJsonLd and buildArticleJsonLd omit blank optionals and normalize keywords`() {
        val webPage = buildWebPageJsonLd(
            name = "Home",
            description = "Landing page",
            url = "https://example.com/",
            siteName = " ",
        )
        assertEquals("WebPage", webPage["@type"]?.jsonPrimitive?.content)
        assertFalse("isPartOf" in webPage)

        val article = buildArticleJsonLd(
            headline = "Wasm SSR",
            description = "Dynamic metadata",
            url = "https://example.com/articles/demo",
            datePublished = "2026-11-20",
            dateModified = " ",
            keywords = listOf("kotlin", " ", "kotlin", "wasm"),
            authorName = "Author Name",
        )
        assertEquals("Article", article["@type"]?.jsonPrimitive?.content)
        assertEquals("2026-11-20", article["datePublished"]?.jsonPrimitive?.content)
        assertFalse("dateModified" in article)
        assertTrue("author" in article)
        assertTrue("keywords" in article)
    }

    @Test
    fun `buildCanonicalUrl normalizes slashes and empty paths`() {
        assertEquals("https://example.com/", buildCanonicalUrl("https://example.com/", ""))
        assertEquals("https://example.com/", buildCanonicalUrl("https://example.com", "/"))
        assertEquals(
            "https://example.com/articles/demo",
            buildCanonicalUrl("https://example.com/", "articles/demo"),
        )
        assertEquals(
            "https://example.com/articles/demo",
            buildCanonicalUrl("https://example.com", "/articles/demo"),
        )
    }

    @Test
    fun `renderSitemapXml escapes XML special characters and omits blank optional fields`() {
        val xml = renderSitemapXml(
            entries = listOf(
                SitemapEntry(
                    loc = "https://example.com/search?a=1&b='two'&c=\"<tag>\"",
                    lastModified = "2026-11-20",
                    changeFreq = "weekly",
                    priority = "0.8",
                ),
                SitemapEntry(
                    loc = "https://example.com/minimal",
                    lastModified = " ",
                    changeFreq = "",
                    priority = null,
                ),
                SitemapEntry(loc = "https://example.com/minimal"),
                SitemapEntry(loc = "   "),
            ),
        )

        assertTrue(xml.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(xml.contains("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">"""))
        assertTrue(
            xml.contains(
                "<loc>https://example.com/search?a=1&amp;b=&apos;two&apos;&amp;c=&quot;&lt;tag&gt;&quot;</loc>",
            ),
        )
        assertTrue(xml.contains("<lastmod>2026-11-20</lastmod>"))
        assertTrue(xml.contains("<changefreq>weekly</changefreq>"))
        assertTrue(xml.contains("<priority>0.8</priority>"))
        assertEquals(2, Regex("<url>").findAll(xml).count())
        assertEquals(1, Regex("<lastmod>").findAll(xml).count())
        assertEquals(1, Regex("<changefreq>").findAll(xml).count())
        assertEquals(1, Regex("<priority>").findAll(xml).count())
    }

    @Test
    fun `renderSitemapXml renders empty urlset cleanly`() {
        val xml = renderSitemapXml(emptyList())
        assertEquals(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            </urlset>
            """.trimIndent() + "\n",
            xml,
        )
    }

    @Test
    fun `renderRobotsTxt formats allow, disallow, allowAll=false, and strips CRLF injection`() {
        val permissive = renderRobotsTxt(
            sitemapUrl = "https://example.com/sitemap.xml\r\nUser-agent: Evil",
            disallowPaths = listOf("/internal/", " /private/\nDisallow: /all "),
            allowAll = true,
        )
        assertEquals(
            """
            User-agent: *
            Allow: /
            Disallow: /internal/
            Disallow: /private/Disallow: /all

            Sitemap: https://example.com/sitemap.xmlUser-agent: Evil
            """.trimIndent() + "\n",
            permissive,
        )

        val restrictive = renderRobotsTxt(
            sitemapUrl = "https://example.com/sitemap.xml",
            allowAll = false,
        )
        assertEquals(
            """
            User-agent: *
            Disallow: /

            Sitemap: https://example.com/sitemap.xml
            """.trimIndent() + "\n",
            restrictive,
        )
    }
}
