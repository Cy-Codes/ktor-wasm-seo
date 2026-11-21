package codes.cy.kit.sample.seo

import codes.cy.kit.seo.SeoPageMetadata
import codes.cy.kit.seo.SitemapEntry
import codes.cy.kit.seo.WasmSpaShellConfig
import codes.cy.kit.seo.buildArticleJsonLd
import codes.cy.kit.seo.buildCanonicalUrl
import codes.cy.kit.seo.buildWebPageJsonLd
import codes.cy.kit.seo.encodeSafeJsonLd
import codes.cy.kit.seo.installWasmSeoShell
import codes.cy.kit.seo.renderRobotsTxt
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Path

private const val DEFAULT_HOST: String = "127.0.0.1"
private const val DEFAULT_PORT: Int = 8080
private const val DEFAULT_BASE_URL: String = "https://example.org"
private const val DEFAULT_SITE_NAME: String = "Kotlin Wasm Reference App"
private const val STATIC_MOUNT_PATH: String = "/static"
private const val HEALTH_PATH: String = "/health"
private const val HEALTH_RESPONSE_BODY: String = """{"status":"UP"}"""
private const val ARTICLES_PATH: String = "/articles"
private const val ARTICLE_DETAIL_ROUTE: String = "/articles/{slug}"

/**
 * Sample article record rendered dynamically inside SEO HTML shells and `/sitemap.xml`.
 */
data class SampleArticle(
    val slug: String,
    val title: String,
    val summary: String,
    val publishedDate: String,
    val tags: List<String> = emptyList(),
)

/**
 * Configuration for the sample Ktor Netty Wasm SEO server.
 */
data class SampleSeoConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val baseUrl: String = DEFAULT_BASE_URL,
    val siteName: String = DEFAULT_SITE_NAME,
    val staticDir: Path? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): SampleSeoConfig {
            val host = env["SERVER_HOST"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HOST
            val port = env["SERVER_PORT"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
            val baseUrl = env["SITE_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL
            val staticDir = env["WEB_STATIC_DIR"]?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
            return SampleSeoConfig(
                host = host,
                port = port,
                baseUrl = baseUrl,
                staticDir = staticDir,
            )
        }
    }
}

val DEFAULT_SAMPLE_ARTICLES: List<SampleArticle> = listOf(
    SampleArticle(
        slug = "demo",
        title = "Dynamic SEO Shells for Kotlin/Wasm",
        summary = "Serving per-route OpenGraph tags, safe JSON-LD, and ES-module bundles from Ktor.",
        publishedDate = "2026-11-20",
        tags = listOf("kotlin", "wasm", "ktor", "seo"),
    ),
    SampleArticle(
        slug = "es-modules",
        title = "Zero-Breakout JSON-LD & AutoHeadResponse",
        summary = "Preventing </script> injection and keeping HEAD probes green on static Wasm assets.",
        publishedDate = "2026-11-18",
        tags = listOf("ktor", "security", "wasmJs"),
    ),
)

fun main() {
    val config = SampleSeoConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        configureSampleSeoServer(config = config)
    }.start(wait = true)
}

fun Application.configureSampleSeoServer(
    config: SampleSeoConfig = SampleSeoConfig(),
    articles: List<SampleArticle> = DEFAULT_SAMPLE_ARTICLES,
) {
    val articlesBySlug = articles.associateBy { it.slug }

    installWasmSeoShell(
        config = WasmSpaShellConfig(
            staticDir = config.staticDir,
            staticMountPath = STATIC_MOUNT_PATH,
            spaRoutes = listOf("/", ARTICLES_PATH, ARTICLE_DETAIL_ROUTE),
            noscriptFallbackLinkHref = "/sitemap.xml",
            noscriptFallbackLinkLabel = "Browse XML Sitemap",
        ),
        sitemapProvider = {
            buildSitemapEntries(baseUrl = config.baseUrl, articles = articles)
        },
        robotsProvider = {
            renderRobotsTxt(
                sitemapUrl = buildCanonicalUrl(config.baseUrl, "/sitemap.xml"),
                disallowPaths = listOf("/internal/"),
            )
        },
    ) { call ->
        val path = call.request.path().trimEnd('/').ifEmpty { "/" }
        when {
            path == "/" -> buildHomeMetadata(config)
            path == ARTICLES_PATH -> buildArticlesIndexMetadata(config)
            path.startsWith("$ARTICLES_PATH/") -> {
                val slug = call.parameters["slug"]?.trim().orEmpty()
                articlesBySlug[slug]?.let { article -> buildArticleMetadata(config, article) }
            }
            else -> null
        }
    }

    routing {
        get(HEALTH_PATH) {
            call.respondText(
                text = HEALTH_RESPONSE_BODY,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }
    }
}

private fun buildSitemapEntries(
    baseUrl: String,
    articles: List<SampleArticle>,
): List<SitemapEntry> =
    listOf(
        SitemapEntry(loc = buildCanonicalUrl(baseUrl, "/"), changeFreq = "weekly", priority = "1.0"),
        SitemapEntry(loc = buildCanonicalUrl(baseUrl, ARTICLES_PATH), changeFreq = "weekly", priority = "0.8"),
    ) + articles.map { article ->
        SitemapEntry(
            loc = buildCanonicalUrl(baseUrl, "$ARTICLES_PATH/${article.slug}"),
            lastModified = article.publishedDate,
            changeFreq = "monthly",
            priority = "0.7",
        )
    }

private fun buildHomeMetadata(config: SampleSeoConfig): SeoPageMetadata {
    val canonicalUrl = buildCanonicalUrl(config.baseUrl, "/")
    val description = "Sample Ktor JVM host serving dynamic SEO HTML shells and Kotlin/Wasm ES modules."
    val jsonLd = encodeSafeJsonLd(
        buildWebPageJsonLd(
            name = config.siteName,
            description = description,
            url = canonicalUrl,
            siteName = config.siteName,
        ),
    )
    return SeoPageMetadata(
        title = config.siteName,
        description = description,
        canonicalUrl = canonicalUrl,
        ogType = "website",
        siteName = config.siteName,
        jsonLdScripts = listOf(jsonLd),
    )
}

private fun buildArticlesIndexMetadata(config: SampleSeoConfig): SeoPageMetadata {
    val canonicalUrl = buildCanonicalUrl(config.baseUrl, ARTICLES_PATH)
    val description = "Browse technical articles rendered with per-route SEO metadata."
    val jsonLd = encodeSafeJsonLd(
        buildWebPageJsonLd(
            name = "Articles — ${config.siteName}",
            description = description,
            url = canonicalUrl,
            siteName = config.siteName,
        ),
    )
    return SeoPageMetadata(
        title = "Articles — ${config.siteName}",
        description = description,
        canonicalUrl = canonicalUrl,
        ogType = "website",
        siteName = config.siteName,
        jsonLdScripts = listOf(jsonLd),
    )
}

private fun buildArticleMetadata(
    config: SampleSeoConfig,
    article: SampleArticle,
): SeoPageMetadata {
    val canonicalUrl = buildCanonicalUrl(config.baseUrl, "$ARTICLES_PATH/${article.slug}")
    val jsonLd = encodeSafeJsonLd(
        buildArticleJsonLd(
            headline = article.title,
            description = article.summary,
            url = canonicalUrl,
            datePublished = article.publishedDate,
            keywords = article.tags,
        ),
    )
    return SeoPageMetadata(
        title = "${article.title} — ${config.siteName}",
        description = article.summary,
        canonicalUrl = canonicalUrl,
        ogType = "article",
        siteName = config.siteName,
        jsonLdScripts = listOf(jsonLd),
        noscriptHeading = article.title,
        noscriptSummary = article.summary,
    )
}
