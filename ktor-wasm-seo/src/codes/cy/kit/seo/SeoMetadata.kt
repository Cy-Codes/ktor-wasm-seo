package codes.cy.kit.seo

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SCHEMA_ORG_CONTEXT: String = "https://schema.org"
private const val XML_DECLARATION: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
private const val SITEMAP_XML_NAMESPACE: String = "http://www.sitemaps.org/schemas/sitemap/0.9"
private const val DEFAULT_OG_TYPE: String = "website"
private const val DEFAULT_TWITTER_CARD: String = "summary"
private const val DEFAULT_ARTICLE_SCHEMA_TYPE: String = "Article"
private const val DEFAULT_WEBPAGE_SCHEMA_TYPE: String = "WebPage"

/**
 * Default immutable [Json] configuration for JSON-LD serialization (`encodeDefaults = true`,
 * `ignoreUnknownKeys = true`, `explicitNulls = false`).
 */
public val DefaultSeoJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Pure immutable SEO metadata contract for a dynamically rendered HTML shell or server-side reader view.
 */
public data class SeoPageMetadata(
    public val title: String,
    public val description: String,
    public val canonicalUrl: String,
    public val ogType: String = DEFAULT_OG_TYPE,
    public val siteName: String? = null,
    public val imageUrl: String? = null,
    public val imageAlt: String? = null,
    public val twitterCard: String = DEFAULT_TWITTER_CARD,
    public val locale: String? = null,
    public val robotsDirective: String? = null,
    public val jsonLdScripts: List<String> = emptyList(),
    public val noscriptHeading: String? = null,
    public val noscriptSummary: String? = null,
    public val noscriptHtml: String? = null,
)

/**
 * Pure immutable `<url>` entry for XML sitemap generation via [renderSitemapXml].
 */
public data class SitemapEntry(
    public val loc: String,
    public val lastModified: String? = null,
    public val changeFreq: String? = null,
    public val priority: String? = null,
)

/**
 * Pure query: normalizes [baseUrl] and [path] into an absolute canonical URL.
 */
public fun buildCanonicalUrl(baseUrl: String, path: String): String {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val trimmedPath = path.trim()
    val cleanPath = when {
        trimmedPath.isEmpty() || trimmedPath == "/" -> "/"
        trimmedPath.startsWith('/') -> trimmedPath
        else -> "/$trimmedPath"
    }
    return if (cleanPath == "/") "$cleanBase/" else "$cleanBase$cleanPath"
}

/**
 * Pure query: escapes HTML `<script>` breakout sequences (`</` and `<!--`) in serialized JSON-LD
 * using valid RFC 8259 JSON escapes so embedded payloads cannot terminate `<script>` tags early.
 */
public fun sanitizeJsonLdScript(rawJson: String): String =
    rawJson
        .replace("</", "<\\/")
        .replace("<!--", "\\u003c!--")

/**
 * Pure query: encodes a [JsonElement] into a script-tag-safe JSON-LD string using [json].
 */
public fun encodeSafeJsonLd(
    jsonElement: JsonElement,
    json: Json = DefaultSeoJson,
): String =
    sanitizeJsonLdScript(json.encodeToString(JsonElement.serializer(), jsonElement))

/**
 * Pure query: encodes a strongly typed `@Serializable` JSON-LD payload via [serializer] into a
 * script-tag-safe JSON-LD string using [json].
 */
public fun <T> encodeSafeJsonLd(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = DefaultSeoJson,
): String =
    sanitizeJsonLdScript(json.encodeToString(serializer, value))

/**
 * Pure query: builds a complete `<script type="application/ld+json">...</script>` HTML fragment
 * from a [JsonElement].
 */
public fun buildSafeJsonLdScript(
    jsonElement: JsonElement,
    json: Json = DefaultSeoJson,
): String =
    wrapJsonLdScriptTag(encodeSafeJsonLd(jsonElement = jsonElement, json = json))

/**
 * Pure query: builds a complete `<script type="application/ld+json">...</script>` HTML fragment
 * from a strongly typed `@Serializable` value via [serializer].
 */
public fun <T> buildSafeJsonLdScript(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = DefaultSeoJson,
): String =
    wrapJsonLdScriptTag(encodeSafeJsonLd(serializer = serializer, value = value, json = json))

/**
 * Pure query: wraps a pre-serialized [rawJson] payload in `<script type="application/ld+json">...</script>`,
 * escaping any `</` and `<!--` script-breakout sequences.
 */
public fun buildSafeJsonLdScript(rawJson: String): String =
    wrapJsonLdScriptTag(sanitizeJsonLdScript(rawJson))

/**
 * Pure query: builds a Schema.org `WebPage` [JsonObject] suitable for [encodeSafeJsonLd].
 */
public fun buildWebPageJsonLd(
    name: String,
    description: String,
    url: String,
    siteName: String? = null,
    schemaType: String = DEFAULT_WEBPAGE_SCHEMA_TYPE,
): JsonObject =
    JsonObject(
        listOfNotNull(
            "@context" to JsonPrimitive(SCHEMA_ORG_CONTEXT),
            "@type" to JsonPrimitive(schemaType),
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive(description),
            "url" to JsonPrimitive(url),
            siteName?.trim()?.takeIf { it.isNotEmpty() }?.let { cleanSiteName ->
                "isPartOf" to JsonObject(
                    mapOf(
                        "@type" to JsonPrimitive("WebSite"),
                        "name" to JsonPrimitive(cleanSiteName),
                    ),
                )
            },
        ).toMap(),
    )

/**
 * Pure query: builds a Schema.org `Article` (or `TechArticle` / `BlogPosting`) [JsonObject]
 * suitable for [encodeSafeJsonLd].
 */
public fun buildArticleJsonLd(
    headline: String,
    description: String,
    url: String,
    datePublished: String? = null,
    dateModified: String? = null,
    keywords: List<String> = emptyList(),
    authorName: String? = null,
    imageUrl: String? = null,
    schemaType: String = DEFAULT_ARTICLE_SCHEMA_TYPE,
): JsonObject {
    val cleanKeywords = keywords
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map(::JsonPrimitive)
        .toList()

    return JsonObject(
        listOfNotNull(
            "@context" to JsonPrimitive(SCHEMA_ORG_CONTEXT),
            "@type" to JsonPrimitive(schemaType),
            "headline" to JsonPrimitive(headline),
            "description" to JsonPrimitive(description),
            "url" to JsonPrimitive(url),
            datePublished?.trim()?.takeIf { it.isNotEmpty() }?.let {
                "datePublished" to JsonPrimitive(it)
            },
            dateModified?.trim()?.takeIf { it.isNotEmpty() }?.let {
                "dateModified" to JsonPrimitive(it)
            },
            authorName?.trim()?.takeIf { it.isNotEmpty() }?.let { cleanAuthor ->
                "author" to JsonObject(
                    mapOf(
                        "@type" to JsonPrimitive("Person"),
                        "name" to JsonPrimitive(cleanAuthor),
                    ),
                )
            },
            imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
                "image" to JsonPrimitive(it)
            },
            cleanKeywords.takeIf { it.isNotEmpty() }?.let {
                "keywords" to JsonArray(it)
            },
        ).toMap(),
    )
}

/**
 * Pure query: escapes XML 1.0 special characters (`&`, `<`, `>`, `"`, `'`).
 */
public fun escapeXml(raw: String): String =
    buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

/**
 * Pure query: renders an XML-escaped `sitemap.xml` document from [entries], omitting blank
 * optional fields (`lastModified`, `changeFreq`, `priority`) and deduplicating by `loc`.
 */
public fun renderSitemapXml(entries: List<SitemapEntry>): String {
    val urlNodes = entries
        .asSequence()
        .map { it.copy(loc = it.loc.trim()) }
        .filter { it.loc.isNotEmpty() }
        .distinctBy { it.loc }
        .map(::renderSitemapUrlNode)
        .joinToString(separator = "\n")

    return buildString {
        appendLine(XML_DECLARATION)
        append("<urlset xmlns=\"")
        append(SITEMAP_XML_NAMESPACE)
        appendLine("\">")
        if (urlNodes.isNotEmpty()) {
            appendLine(urlNodes)
        }
        appendLine("</urlset>")
    }
}

/**
 * Pure query: renders a `robots.txt` document pointing crawlers to [sitemapUrl] with optional
 * [disallowPaths] rules and [allowAll] toggle.
 */
public fun renderRobotsTxt(
    sitemapUrl: String,
    disallowPaths: List<String> = emptyList(),
    allowAll: Boolean = true,
): String {
    val sanitizedDisallow = disallowPaths
        .asSequence()
        .map(::stripLineBreaks)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    val ruleLines = if (allowAll) {
        listOf("Allow: /") + sanitizedDisallow.map { path -> "Disallow: $path" }
    } else {
        listOf("Disallow: /") + sanitizedDisallow.filter { it != "/" }.map { path -> "Disallow: $path" }
    }

    val cleanSitemapUrl = stripLineBreaks(sitemapUrl).trim()
    val allLines = if (cleanSitemapUrl.isNotEmpty()) {
        listOf("User-agent: *") + ruleLines + listOf("", "Sitemap: $cleanSitemapUrl")
    } else {
        listOf("User-agent: *") + ruleLines
    }
    return allLines.joinToString(separator = "\n") + "\n"
}

private fun wrapJsonLdScriptTag(safeJson: String): String =
    """<script type="application/ld+json">$safeJson</script>"""

private fun renderSitemapUrlNode(entry: SitemapEntry): String {
    val fields = listOfNotNull(
        "    <loc>${escapeXml(entry.loc)}</loc>",
        entry.lastModified?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "    <lastmod>${escapeXml(it)}</lastmod>"
        },
        entry.changeFreq?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "    <changefreq>${escapeXml(it)}</changefreq>"
        },
        entry.priority?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "    <priority>${escapeXml(it)}</priority>"
        },
    ).joinToString(separator = "\n")
    return "  <url>\n$fields\n  </url>"
}

private fun stripLineBreaks(raw: String): String =
    raw.replace("\r", "").replace("\n", "")
