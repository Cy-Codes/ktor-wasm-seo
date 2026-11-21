# KMP & Ktor Libraries / Starter Template — Master Coding Standards & Architectural Rules

This document defines the authoritative coding standards, architectural boundaries, library hygiene rules, and verification rules across `cy-kmp-kit`, `cy-swiftui-kit`, and `kmp-ktor-fullstack-template` (Kotlin Multiplatform, Compose Multiplatform, SwiftUI, Ktor JVM, Exposed 1.0, PostgreSQL 16, Flyway, Docker, and Bruno).

---

## Part I: Universal Coding Standards (All Languages & Layers)

```text
+-------------------------------------------------------------------------+
|                       Universal Standards Pillars                       |
+--------------------+--------------------+-------------------------------+
| Functional First   | Immutable Always   | Explicit Side Effects (CQS)   |
| Maps over Loops    | val & data class   | Functional Core / Imp. Shell  |
+--------------------+--------------------+-------------------------------+
| Helpers & Ext. Fns | Zero Magic Numbers | Anti-Pattern Zero Tolerance   |
+--------------------+--------------------+-------------------------------+
```

### 1. Functional Logic Over Imperative Loops

All Kotlin, Swift, SQL, and shell code must prefer declarative, functional transformations over state-mutating imperative loops.

1. **Choose Transformations Over Imperative Loops:**
   - **Kotlin:** Use collection and sequence pipelines (`.map { }`, `.filter { }`, `.fold(...)`, `.associate { }`, `.flatMap { }`, `generateSequence`). Never allocate `mutableListOf()` or `mutableMapOf()`, iterate input, and imperatively call `.add()` or `put()`.
   - **Swift:** Use `.map`, `.filter`, `.reduce`, and `.compactMap` over mutable accumulator arrays.
   - **PostgreSQL:** Use set-based SQL (`generate_series`, CTEs, window functions, `jsonb_agg`, `jsonb_build_object`) over procedural `FOR ... LOOP` blocks or cursors.
   - **Bash:** Use `jq` and `awk` pipelines for structured data transformation.
2. **Permitted Loop Exceptions (Must Be Commented):**
   - Loops (`for`, `while`) are allowed **only** for:
     - Streaming unbuffered byte buffers or network/DB streams (`ResultSet.next()` wrapped in `generateSequence`, Ktor `ByteReadChannel` chunking).
     - Benchmark-proven hot paths where closure allocations cause measured GC pressure.
     - Chunked DB maintenance deleting/updating batches in bounded windows to avoid lock escalation.
     - Bounded Bash polling/retry probes (`wait_for_health`) or iterating fixed `readonly` arrays in helper functions.
     - Line-oriented state-machine scanners where `fold` produces cleaner code anyway — prefer `fold` over mutable `var` loops whenever practical.
   - **Mandatory rule:** Every exception loop must carry an inline comment explaining why a functional construct was rejected.

### 2. Immutability by Default

1. **Kotlin Contracts & Models:**
   - Define all domain models, DTOs, `QueryOptions`, `PagedResult<T>`, and FlowMVI `State`/`Intent`/`Action` types as `data class`, `sealed interface`, or `data object` with `val` properties only.
   - Expose read-only Kotlin collection interfaces (`List<T>`, `Map<K, V>`, `Set<T>`) everywhere. Never expose `MutableList<T>` or `MutableMap<K, V>` in domain models, MVI state, or public return types.
   - Mutable `var` properties are forbidden on domain models, DTOs, repositories, services, and routes.
2. **Pure Functions:**
   - Functions must compute new values from inputs without mutating arguments or hidden global state.
3. **Database & Artifact Immutability:**
   - Merged Flyway migration files (`migrations/V<n>__*.sql`) are **strictly immutable**. Never edit, reorder, or delete an applied migration; evolve schemas strictly by appending `V<n+1>__<description>.sql`.
   - Bash constants must be declared `readonly`.
   - Container images must be tagged by immutable content/git hashes — never deploy `:latest` application images.

### 3. Explicit Demarcation of Side Effects (CQS & Functional Core / Imperative Shell)

1. **Command-Query Separation (CQS):**
   - **Queries (Pure / Read-Only):** Idempotent, side-effect free, safe to retry (`get*`, `find*`, `parse*`, `calculate*`, `to*`, `apply*`, `*QueryService`).
   - **Commands (Side-Effecting):** Explicitly mutate state, perform database writes, execute uploads, or emit telemetry (`execute*`, `create*`, `update*`, `delete*`, `publish*`, `upload*`, `record*`, `*CommandService`).
2. **Strict Read-Only `GET` Semantics:**
   - HTTP `GET` handlers (including download endpoints and SSR reader routes) must remain **100% read-only and idempotent** with zero database writes.
   - Telemetry such as page views, content reads, and document downloads must be emitted explicitly via `POST` event ingestion endpoints.
3. **Functional Core, Imperative Shell:**
   - Keep pagination normalization, query validation, `ApiError` mapping, Markdown/SEO metadata building, and FlowMVI reducers pure with zero I/O dependencies.
   - Isolate database transactions (`newSuspendedTransaction`), JDBC/Exposed I/O, S3/R2 calls, and HTTP transport inside explicit `infrastructure` and `api` boundary classes.
   - Always close `AutoCloseable` I/O resources deterministically using Kotlin's `.use { ... }`.
   - In Bash scripts, label functions with `# Pure query: ...` or `# Side effect: ...`.

### 4. Zero Magic Numbers & String Literals

1. **Ban Unnamed Literals:** Raw HTTP status numbers (`404`, `409`, `500`), pagination sizes (`20`, `100`), port numbers (`5432`, `8080`, `9000`), timeouts, retry counts, and string discriminators are prohibited in business logic.
2. **Required Replacements:**
   - Use Ktor `HttpStatusCode.*` constants (`HttpStatusCode.OK`, `HttpStatusCode.Created`, `HttpStatusCode.NotFound`, `HttpStatusCode.Conflict`).
   - Centralize pagination bounds in `QueryOptions` (`DEFAULT_PAGE = 1`, `MIN_PAGE_SIZE = 1`, `DEFAULT_PAGE_SIZE = 20`, `MAX_PAGE_SIZE = 100`).
   - Model bounded concepts with strongly typed `enum class` types rather than raw strings.
   - Use `const val` for Kotlin constants, `CHECK` constraints / SQL variables in Postgres, and `readonly` constants in Bash.

### 5. Eliminate Redundancy via Helper & Extension Functions

1. **Pure Top-Level Kotlin Extensions:** Consolidate repeated mapping, validation, pagination, and response-handling code into focused extension files rather than duplicating logic or building deep inheritance hierarchies.
2. **Shared Shell Workflows:** Repeated Docker, `psql`, Bruno, and health-probe commands must live once in `scripts/lib.sh` and be `source`d — never duplicated across scripts.

---

## Part II: Kotlin Multiplatform, Presentation & UI Standards

### 6. Toolchain & Module Topology

- **Build System:** Kotlin Toolchain `0.13.0` (`project.yaml`, `module.yaml`, `libs.versions.toml`). Prefer native Toolchain settings (`compose`, `ktor`, `serialization`, `publishing`) over bespoke build scripts.
- **Target Platforms:**
  - Pure KMP library modules target `[ jvm, android, iosArm64, iosSimulatorArm64, wasmJs ]` (except client upload pickers targeting `[ jvm, wasmJs ]`).
  - Presentation layers remain strictly **zero Compose dependencies**.

### 7. Shared `core` Contracts & `kotlinx.serialization`

- **Type-Safe API Contracts:** Define `@Serializable` DTOs and Ktor `@Resource` routes in shared `core` contracts so servers and Ktor Client share one compile-time API contract.
- **Compile-Time Serialization Only:** Never use reflection-based JSON serializers. Always use JetBrains `kotlinx.serialization` (`@Serializable`) with a frozen `Json` configuration (`encodeDefaults = true`, `ignoreUnknownKeys = true`, `explicitNulls = true`).
- **Boolean Naming:** Preserve `is*` property names cleanly via `kotlinx.serialization` without property-name mangling.

### 8. Pure KMP Error Contract (`ApiError`)

All client-server error contracts use the pure KMP `@Serializable sealed interface ApiError` from `api-contracts-core`:

- Closed hierarchy with explicit `@SerialName` discriminators (`validation_error`, `unauthorized`, `forbidden`, `not_found`, `conflict`, `internal_error`).
- Server `StatusPages` maps domain exceptions, validation failures, overflow errors, missing routes, and PostgreSQL integrity violations cleanly via `Throwable.toApiError(isDevelopment: Boolean)`.
- **Zero Information Disclosure:** Never expose raw exception messages, SQL state, hostnames, or stack traces in `ApiError.InternalError` when outside development environments; return a generic sanitized message plus `traceId`.

### 9. Pure KMP Presentation (Decompose + FlowMVI)

- **Strict Compose-Free Boundary:** Presentation modules must contain **zero** `androidx.compose.*` or `org.jetbrains.compose.*` imports. Enforce automatically with Konsist architecture tests.
- **Navigation (Decompose):** Parent-driven via sealed `Output` intents and `StackNavigation` / `PanelsNavigation`; synchronize browser history on `wasmJs`.
- **State Management (FlowMVI):** Immutable `MVIState`, sealed `MVIIntent`, sealed `MVIAction`, with cross-cutting plugins installed cleanly.

### 10. Compose UI & Native SwiftUI

- **Adaptive Breakpoints & Input Modality:** Drive layouts exclusively from window size in `dp` via `currentWindowAdaptiveInfoV2()` across 5 Material 3 breakpoints (`Compact`, `Medium`, `Expanded`, `Large`, `Extra-Large`), never OS checks.
- **Web Target (`wasmJs`):** Target `wasmJs` exclusively (not legacy Compose JS), paired with dynamic SEO injection and compression.
- **Native iOS (`SwiftUI`):** Bind native SwiftUI views via `DecomposeSwiftUIKit` (`@StateValue`, `StackView`) and embed interactive Compose widgets inline via `ComposeIslandView`.

---

## Part III: Ktor JVM Server Standards (Compile-Time Public/Admin Split)

### 11. Public/Admin Module Isolation & Clean Architecture Layering

Keep the backend split at compile time across three server modules:
- **`server/server-db` (`jvm/lib`):** Exposed 1.0 DSL tables, `ResultRow` mappers, repositories, HikariCP factory, `BlobStorage` abstraction, Flyway helpers. **Never** imports Ktor routing or auth plugins.
- **`server/server-public` (`jvm/app`):** Public read APIs, `POST /api/v1/public/events`, SSR `/read/{slug}` HTML view, dynamic Wasm SEO shell, `/sitemap.xml`, `/robots.txt`, `/health`, `/health/ready`. Connects with least-privilege `<app>_public` DB role and holds zero S3/R2 write credentials or admin routes.
- **`server/server-admin` (`jvm/app`):** Admin CRUD endpoints, presigned upload & verify routes, analytics queries, auth verification, `/health`, `/health/ready`. Connects with `<app>_admin` DML role.

Organize packages strictly inward: `api` -> `application` -> `infrastructure` -> `domain`. Keep concrete classes `final` with primary-constructor `private val` dependency injection.

### 12. Data Access (Exposed 1.0 DSL-Only) & Pagination

- **Exposed 1.0 DSL Only:** Map queries into immutable `data class` models inside `newSuspendedTransaction(Dispatchers.IO)` (`dbQuery`). Exposed DAO entities and `SchemaUtils.create()` in production are prohibited.
- **Connection Pooling:** HikariCP with `isAutoCommit = false` and `TRANSACTION_REPEATABLE_READ`.
- **Pagination & CWE-190 Overflow Guards:** Accept immutable `QueryOptions` (`calculateOffsetOrNull()`), return `PagedResult<T>`, and clamp `pageSize` between `1` and `100`.
- **Safe Sorting / Filtering:** Validate sort fields against explicit column allowlists (`validateAllowedSortField`).

### 13. HTTP Semantics, Auth, Uploads & Health Probes

- `POST` -> `201 Created` + `Location` header; `PUT` -> `200 OK` (or `404`); `DELETE` -> `204 No Content` (or `404`).
- Generate IDs and timestamps server-side with injected `java.time.Clock`.
- Protect all `/api/v1/admin/*` routes unconditionally.
- Verify direct-to-S3 uploads via `HeadObject` + byte limit + 64-byte magic-byte header inspection, deleting mismatches automatically.

---

## Part IV: PostgreSQL 16 & Flyway Standards

- Organize tables under explicit app and `analytics` schemas (never `public`).
- Enforce 3-tier least-privilege roles: `<app>_migrator` (DDL only), `<app>_admin` (DML RW), `<app>_public` (RO + `INSERT`-only telemetry).
- Use `UUID` or `BIGINT GENERATED BY DEFAULT AS IDENTITY` (never `SERIAL`), `TIMESTAMPTZ` (never `TIMESTAMP`), mandatory B-Tree FK indexes, explicit function volatility (`IMMUTABLE` / `STABLE` / `VOLATILE`), and `psql -v key="value"` parameterization.

---

## Part V: Docker, Testing, Bruno, Bash & Pre-Commit Standards

- Multi-stage, non-root `Dockerfile`s with `-XX:MaxRAMPercentage=75.0`.
- Profiled `compose.yaml` (`deps`, `local`, `test`), `127.0.0.1` binds, meaningful `healthcheck`s.
- Follow the Test Pyramid: fast unit/component tests, Konsist architecture fitness tests, Docker/Testcontainers integration tests, chronological Bruno collection (`00-Health` through `05-Negative-Tests`).
- Bash scripts: `#!/usr/bin/env bash`, `set -euo pipefail`, `source` shared helpers from `scripts/lib.sh`, `readonly` constants, 4-space indentation, `# Pure query:` / `# Side effect:` annotations.

---

## Part VI: Anti-Pattern & Remedy Catalog

| Anti-Pattern | Risk | Required Remedy |
| :--- | :--- | :--- |
| Mutable collections/vars in models (`MutableList`, `var`) | Race conditions, broken MVI equality | Use `data class` + `val` + `List`/`Map`/`Set` only |
| Mutating state inside `GET` routes | Breaks idempotency & forces write DB creds on public server | Keep `GET` 100% read-only; log via `POST /events` |
| Sharing `public` schema or single superuser DB role | Excessive blast radius | Separate schemas + 3-tier DB roles |
| Running `SchemaUtils.create()` or editing merged `V*.sql` | Schema drift, checksum failures | Dedicated Flyway migrator + append-only `V<n>__*.sql` |
| Using `SERIAL`, `TIMESTAMP`, or unindexed foreign keys | Non-standard sequences, lost UTC, FK locks | `GENERATED BY DEFAULT AS IDENTITY` / `UUID`, `TIMESTAMPTZ`, FK indexes |
| Importing Compose in presentation | Breaks native SwiftUI sharing on iOS | Keep presentation pure KMP; enforce with Konsist |
| Switching Compose layout by OS/device model | Breaks resizing, foldables, split-screen | Drive layouts via `WindowSizeClass` `dp` breakpoints |
| Exposing raw `500` details in production | Leaks DB strings and stack traces | Pure `Throwable.toApiError(isDev)` masking `500` with `traceId` |
| Unchecked `(page - 1) * pageSize` | 32-bit integer overflow (CWE-190) | `QueryOptions.calculateOffsetOrNull()` -> `400` |
| Storing uploaded binaries in Postgres `BYTEA` | Table/WAL bloat, heap spikes | Direct presigned PUTs to S3/R2 + magic-byte verify |

---

## Part VII: Library API Hygiene & Hard-Won Multiplatform Invariants

### 19. Library Design Rules (`cy-kmp-kit` & `cy-swiftui-kit`)

1. **Explicit API Mode (`strict`):** Every library module in `cy-kmp-kit` enables `kotlin.explicitApi: strict`. All public classes, functions, properties, and return types must declare explicit visibility and explicit return types. (Samples and the starter template are exempt.)
2. **Zero Domain Leakage:** Library packages, symbols, resource strings, and fallback labels must contain **zero** application-specific nouns (`Portfolio`, `Writeup`, `Project`, `Resume`, `Admin*`).
3. **Explicit Dependency Injection Over Singletons:** Pass `Json`, `HttpClient`, base URLs, `Clock`, theme tokens (`EditorialTokens`, `MarkdownThemeTokens`), and registries (`IslandRegistry`) explicitly through constructors, function parameters, or `CompositionLocal`s. The only permitted top-level objects are stateless immutable codecs/factories (e.g., `ContractJson`).
4. **SemVer & BOM Alignment:** All `cy-kmp-kit` artifacts share group `codes.cy.kit` and aligned SemVer (`0.1.0`), consumed via `libs.versions.toml` in the starter template.
5. **KDoc on Public Symbols:** Every public class, interface, function, extension, and constant must carry concise KDoc explaining contract semantics and CQS purity.

### 20. Preserved Production & Multiplatform Invariants

1. **Single-Subscription FlowMVI + Essenty Bridge:** Never split state and action observation across two `store.collect` calls (splits `ActionShareBehavior.Distribute()`). Keep `lifecycle.repeatOnLifecycle(STARTED, context = coroutineContext)` + single `store.collect { coroutineScope { launch { states.collect }; launch { actions.collect } } }` with explicit `CoroutineContext` injection so `UnconfinedTestDispatcher` tests work deterministically.
2. **Decompose `Value<out T : Any>` Non-Null Bound:** Never parameterize Decompose `Value` with nullable types; represent empty selections via sealed non-null types or explicit sentinel states.
3. **SwiftUI `ObservableObject` Compliance:** Always `import Combine` alongside `ObservableObject`/`@Published`, marshal state updates onto `DispatchQueue.main`, and decouple Kotlin framework bindings behind Swift protocols (`DecomposeValueSource`) + injected closures (`ComposeIslandView`).
4. **Kotlin Toolchain `: exported` Propagation:** Downstream consumers do NOT transitively see library dependencies unless marked `: exported` in `module.yaml`. Mark core contract dependencies `: exported` in client/server/compose extension libs.
5. **Isolated Direct-to-S3 Binary PUT Client:** Presigned binary PUTs must use a dedicated unauthenticated `HttpClient` (never carrying `Authorization` or `Cf-Access-Jwt-Assertion`) and strip engine-managed headers (`Host`, `Content-Type`, `Content-Length`) from signed `requiredHeaders` to avoid CIO/browser `UnsafeHeaderException`.
6. **WasmJS File Upload Without `asDynamic()`:** Bridge `<input type="file">` via `FileReader.readAsDataURL`, top-level `@OptIn(ExperimentalWasmJsInterop::class) js(...)` callbacks passing primitive `(filename, mimeType, base64)`, and `kotlin.io.encoding.Base64.decode`.
7. **PgJDBC + Insert-Only Least-Privilege Roles:** Execute append-only inserts with `Statement.NO_GENERATED_KEYS` (`executeUpdateNoReturning`) so PgJDBC does not emit implicit `RETURNING *` requiring `SELECT` privileges on `INSERT`-only telemetry tables.
8. **Ktor `staticFiles` + HTTP `HEAD`:** Install `AutoHeadResponse` whenever `HEAD` probes hit static Wasm bundles, and mount `staticFiles` excluding `index.html` so dynamic SEO routes remain authoritative.
9. **Kotlin/Native Test Method Names:** Never include commas (`,`) inside backticked `@Test` names on modules targeting `iosArm64` / `iosSimulatorArm64`.
