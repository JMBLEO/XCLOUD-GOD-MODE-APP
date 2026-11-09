package com.xcloud.godmode.network

import android.os.SystemClock
import android.util.Log
import com.xcloud.godmode.engine.CloudLaunchException
import com.xcloud.godmode.engine.MonotonicClock
import com.xcloud.godmode.network.SecureTokenEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

@JvmInline value class CatalogRecordId(val value: String)
@JvmInline value class XCloudTitleId(val value: String)
@JvmInline value class MicrosoftStoreProductId(val value: String)
@JvmInline value class OfferingId(val value: String)

enum class CatalogSource { AUTHENTICATED_CLOUD_CATALOG, RECENT_CLOUD_TITLES, OWNED_CLOUD_TITLES }
enum class CloudEligibility { ENABLED, DISABLED, UNKNOWN }

data class LaunchableCloudTitle(
    val recordId: CatalogRecordId,
    val displayName: String,
    val xCloudTitleId: XCloudTitleId,
    val productId: MicrosoftStoreProductId?,
    val offeringId: OfferingId,
    val catalogRevision: String?,
    val eligibility: CloudEligibility,
    val source: CatalogSource
) {
    init {
        require(displayName.isNotBlank()) { "El nombre visible no puede estar vacío" }
        require(xCloudTitleId.value.isNotBlank()) { "xCloudTitleId no puede estar vacío" }
        require(
            !xCloudTitleId.value.equals(displayName, ignoreCase = true) &&
            !xCloudTitleId.value.equals(normalizeDisplayName(displayName), ignoreCase = true)
        ) {
            "xCloudTitleId no puede coincidir con el nombre visible ni su forma normalizada"
        }
        if (productId != null) require(productId.value.isNotBlank()) { "productId vacío no permitido" }
    }
    companion object { fun normalizeDisplayName(value: String): String = value.filter(Char::isLetterOrDigit).uppercase() }
}

data class CloudCatalogSnapshotDto(val revision: String?, val offeringId: OfferingId, val titles: List<LaunchableCloudTitle>)
data class CloudCatalogSnapshot(
    val revision: String?,
    val offeringId: OfferingId,
    val titlesByRecordId: Map<CatalogRecordId, LaunchableCloudTitle>,
    val fetchedAtElapsedMs: Long
)

class CatalogProtocolException(message: String) : Exception(message)

interface CloudCatalogApi {
    suspend fun fetchLaunchableTitles(): CloudCatalogSnapshotDto
}

class CloudCatalogParser(private val defaultSource: CatalogSource = CatalogSource.AUTHENTICATED_CLOUD_CATALOG) {
    fun parse(root: JSONObject, expectedOfferingId: OfferingId): CloudCatalogSnapshotDto {
        val items = locateItems(root)
        val revision = root.optString("revision").takeIf(String::isNotBlank)
        val parsedTitles = buildList {
            for (index in 0 until items.length()) {
                val raw = items.optJSONObject(index) ?: continue
                parseItem(raw, expectedOfferingId, revision, defaultSource)?.let(::add)
            }
        }
        return CloudCatalogSnapshotDto(revision, expectedOfferingId, parsedTitles)
    }

    private fun locateItems(root: JSONObject): JSONArray {
        root.optJSONArray("results")?.let { return it }
        root.optJSONArray("titles")?.let { return it }
        root.optJSONArray("items")?.let { return it }
        root.optJSONObject("result")?.optJSONArray("titles")?.let { return it }
        throw CatalogProtocolException("No se encontró colección de títulos en el catálogo")
    }

    private fun parseItem(raw: JSONObject, expectedOfferingId: OfferingId, catalogRevision: String?, source: CatalogSource): LaunchableCloudTitle? {
        val details = raw.optJSONObject("details") ?: raw
        val displayName = firstNonBlank(details.optString("ProductTitle"), details.optString("title"), details.optString("name"), raw.optString("titleName")) ?: return null
        
        val xCloudTitleIdRaw = firstNonBlank(
            raw.optString("XCloudTitleId"), details.optString("XCloudTitleId"),
            raw.optString("xCloudTitleId"), details.optString("xCloudTitleId"),
            raw.optString("titleId"), details.optString("titleId")
        ) ?: return null

        val productIdRaw = firstNonBlank(raw.optString("productId"), details.optString("productId"), raw.optString("ProductId"), details.optString("ProductId"), raw.optString("bigId"))
        val recordIdRaw = firstNonBlank(raw.optString("id"), productIdRaw ?: "", xCloudTitleIdRaw) ?: return null

        if (xCloudTitleIdRaw.equals(displayName, ignoreCase = true) || xCloudTitleIdRaw.equals(LaunchableCloudTitle.normalizeDisplayName(displayName), ignoreCase = true)) return null

        val itemOffering = raw.optString("offeringId").takeIf(String::isNotBlank)?.let(::OfferingId) ?: expectedOfferingId
        if (itemOffering != expectedOfferingId) return null

        val eligibility = resolveEligibility(raw, details, source, xCloudTitleIdRaw)

        return LaunchableCloudTitle(
            CatalogRecordId(recordIdRaw), displayName, XCloudTitleId(xCloudTitleIdRaw), productIdRaw?.let(::MicrosoftStoreProductId),
            itemOffering, catalogRevision, eligibility, source
        )
    }

    private fun resolveEligibility(raw: JSONObject, details: JSONObject, source: CatalogSource, xCloudTitleId: String): CloudEligibility {
        if (raw.has("cloudEnabled")) return if (raw.optBoolean("cloudEnabled")) CloudEligibility.ENABLED else CloudEligibility.DISABLED
        if (raw.has("isPlayable")) return if (raw.optBoolean("isPlayable")) CloudEligibility.ENABLED else CloudEligibility.DISABLED
        if (details.has("cloudEnabled")) return if (details.optBoolean("cloudEnabled")) CloudEligibility.ENABLED else CloudEligibility.DISABLED
        return if (source == CatalogSource.AUTHENTICATED_CLOUD_CATALOG && xCloudTitleId.isNotBlank()) CloudEligibility.ENABLED else CloudEligibility.UNKNOWN
    }
    private fun firstNonBlank(vararg values: String): String? = values.firstOrNull { it.isNotBlank() }
}

class CloudCatalogRepository(private val api: CloudCatalogApi, private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }) {
    @Volatile private var snapshot: CloudCatalogSnapshot? = null
    suspend fun refresh(): CloudCatalogSnapshot {
        val dto = api.fetchLaunchableTitles()
        require(dto.titles.isNotEmpty()) { "Catálogo autenticado vacío" }
        return CloudCatalogSnapshot(dto.revision, dto.offeringId, dto.titles.associateBy { it.recordId }, clock.nowElapsedMs()).also { snapshot = it }
    }
    suspend fun resolveForLaunch(recordId: CatalogRecordId): LaunchableCloudTitle {
        val current = snapshot ?: refresh()
        val resolved = current.titlesByRecordId[recordId] ?: refresh().titlesByRecordId[recordId] ?: throw CloudLaunchException.TitleNotInCurrentCatalog(recordId.value)
        when (resolved.eligibility) {
            CloudEligibility.ENABLED -> Unit
            CloudEligibility.DISABLED -> throw CloudLaunchException.TitleNotCloudEnabled(resolved.displayName, "Marcado explícitamente como no habilitado para cloud")
            CloudEligibility.UNKNOWN -> throw CloudLaunchException.TitleNotCloudEnabled(resolved.displayName, "No acredita flags de ejecución cloud válidos")
        }
        return resolved
    }
}

data class CloudLaunchPreflight(val catalogRecordId: String, val displayName: String, val xCloudTitleId: String, val productId: String?, val offeringId: String, val catalogRevision: String?, val authHealth: Int, val tokenRemainingMs: Long)

class CloudLaunchPreflightValidator {
    fun validate(title: LaunchableCloudTitle, envelope: SecureTokenEnvelope, expectedOfferingId: OfferingId, authHealthScore: Int, nowElapsedMs: Long): CloudLaunchPreflight {
        require(title.eligibility == CloudEligibility.ENABLED) { "Preflight fallido: título no elegible" }
        require(title.offeringId == expectedOfferingId) { "Offering inconsistente" }
        require(title.xCloudTitleId.value.isNotBlank()) { "xCloudTitleId vacío" }
        require(!title.xCloudTitleId.value.equals(title.displayName, ignoreCase = true) && !title.xCloudTitleId.value.equals(LaunchableCloudTitle.normalizeDisplayName(title.displayName), ignoreCase = true)) { "Uso de nombre visible como ID" }
        val tokenRemainingMs = envelope.gsExpiresAtElapsedMs - nowElapsedMs
        require(tokenRemainingMs > 120_000L) { "Token expira en < 120s" }
        return CloudLaunchPreflight(title.recordId.value, title.displayName, title.xCloudTitleId.value, title.productId?.value, title.offeringId.value, title.catalogRevision, authHealthScore, tokenRemainingMs)
    }
}

fun auditFingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

data class SanitizedLaunchDiagnosticEntry(val timestampElapsedMs: Long, val catalogRecordFingerprint: String, val xCloudTitleFingerprint: String, val productFingerprint: String?, val offeringId: String, val httpStatus: Int, val authStatusCode: String?, val requestCorrelationId: String?)
data class PerformanceSnapshot(val inputLatencyMs: Long, val displayJitterMs: Float, val epoch: Long)

class CloudLaunchDiagnostics(private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }) {
    companion object { private const val TAG = "GodModeLaunchDiag" }
    private val history = ArrayDeque<SanitizedLaunchDiagnosticEntry>(32)
    private val perfHistory = ArrayDeque<PerformanceSnapshot>(128)

    @Synchronized
    fun recordPreflightAndResponse(preflight: CloudLaunchPreflight, status: Int, correlationId: String?, authStatusCode: String?) {
        val entry = SanitizedLaunchDiagnosticEntry(clock.nowElapsedMs(), auditFingerprint(preflight.catalogRecordId), auditFingerprint(preflight.xCloudTitleId), preflight.productId?.let(::auditFingerprint), preflight.offeringId, status, authStatusCode, correlationId)
        if (history.size >= 32) history.removeFirst()
        history.addLast(entry)
        Log.i(TAG, "LaunchAudit | recFp=${entry.catalogRecordFingerprint} | titleFp=${entry.xCloudTitleFingerprint} | prodFp=${entry.productFingerprint ?: "NONE"} | offering=${entry.offeringId} | http=${entry.httpStatus} | authStatus=${entry.authStatusCode ?: "NONE"} | corrId=${entry.requestCorrelationId ?: "NONE"}")
    }

    @Synchronized
    fun recordPerformanceSnapshot(snapshot: PerformanceSnapshot) {
        if (perfHistory.size >= 128) perfHistory.removeFirst()
        perfHistory.addLast(snapshot)
        if (snapshot.inputLatencyMs > 50) {
            Log.w(TAG, "PerformanceAlert | High Input Latency detected: ${snapshot.inputLatencyMs}ms")
        }
    }
}
