package ua.chudo.shared.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// Модели Rust-ядра (core/src/model.rs): поля camelCase, enum — SCREAMING_SNAKE_CASE.

@Serializable enum class Lang { UA, RU }
@Serializable enum class StockStatus { IN_STOCK, OUT_OF_STOCK, UNKNOWN }
@Serializable enum class QueryKind { EAN13, EAN8, ARTICLE, TEXT }
@Serializable enum class BlockKind { CLOUDFLARE_CHALLENGE, FORBIDDEN, RATE_LIMITED }

@Serializable data class Price(val finalPrice: Double, val regularPrice: Double, val discountPercent: Double, val currency: String)

@Serializable data class ProductSummary(
    val id: Long,
    val article: String? = null,
    val name: String,
    val urlKey: String,
    val urlSuffix: String,
    val stock: StockStatus,
    val price: Price? = null,
    val image: String? = null,
    val exactMatch: Boolean,
)

@Serializable data class ProductDetails(
    val id: Long,
    val article: String? = null,
    val name: String,
    val urlKey: String,
    val urlSuffix: String,
    val stock: StockStatus,
    val onlyXLeft: Double? = null,
    val price: Price? = null,
    val images: List<String> = emptyList(),
    val description: String = "",
    val categories: List<String> = emptyList(),
)

@Serializable data class ShopAvailability(val address: String, val available: Boolean)
@Serializable data class Region(val code: String, val name: String)
@Serializable data class ClassifiedQuery(val kind: QueryKind, val normalized: String)

/** Ошибка ядра (разбор ответа сайта, неверный штрихкод…). */
class CoreException(message: String) : Exception(message)

/** Вызов Rust: `chudo_call(method, argsJson)` → `{"ok":…}` / `{"err":…}`. Платформенная часть — JNA / cinterop. */
internal expect object NativeCore {
    fun call(method: String, argsJson: String): String
}

/** Rust-ядро — одинаковый API на Android и iOS. */
object Core {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> call(method: String, args: JsonObject, s: KSerializer<T>): T {
        val out = json.parseToJsonElement(NativeCore.call(method, args.toString())).jsonObject
        out["err"]?.let { throw CoreException((it as? JsonPrimitive)?.content ?: it.toString()) }
        return json.decodeFromJsonElement(s, out["ok"] ?: JsonPrimitive(null as String?))
    }

    private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
    private fun langArg(lang: Lang): JsonElement = JsonPrimitive(lang.name)

    fun classifyQuery(input: String) = call("classify_query", args { put("input", input) }, ClassifiedQuery.serializer())
    fun isBarcode(kind: QueryKind) = kind == QueryKind.EAN13 || kind == QueryKind.EAN8
    fun ean13Full(code: String) = call("ean13_full", args { put("code", code) }, String.serializer())
    fun ean13Bars(code: String) = call("ean13_bars", args { put("code", code) }, ListSerializer(Boolean.serializer()))
    fun buildSearchBody(query: String, pageSize: Int) = call("build_search_body", args { put("query", query); put("pageSize", pageSize) }, String.serializer())
    fun buildDetailsBody(urlKey: String) = call("build_details_body", args { put("urlKey", urlKey) }, String.serializer())
    fun parseSearch(body: String, query: String) = call("parse_search", args { put("json", body); put("query", query) }, ListSerializer(ProductSummary.serializer()))
    fun parseDetails(body: String) = call("parse_details", args { put("json", body) }, ProductDetails.serializer())
    fun parseShops(body: String) = call("parse_shops", args { put("json", body) }, ListSerializer(ShopAvailability.serializer()))
    fun detectBlock(status: Int, body: String) = call("detect_block", args { put("status", status); put("body", body) }, BlockKind.serializer().nullable)
    fun regions() = call("regions", args {}, ListSerializer(Region.serializer()))
    fun defaultRegion() = call("default_region", args {}, String.serializer())
    fun upcCandidates(body: String, barcode: String, limit: Int) = call("upc_candidates", args { put("json", body); put("barcode", barcode); put("limit", limit) }, ListSerializer(String.serializer()))
    fun upcTitle(body: String) = call("upc_title", args { put("json", body) }, String.serializer().nullable)
    fun graphqlUrl() = call("graphql_url", args {}, String.serializer())
    fun storeHeader(lang: Lang = Lang.UA) = call("store_header", args { put("lang", langArg(lang)) }, String.serializer())
    fun productUrl(urlKey: String, urlSuffix: String, lang: Lang = Lang.UA) = call("product_url", args { put("lang", langArg(lang)); put("urlKey", urlKey); put("urlSuffix", urlSuffix) }, String.serializer())
    fun shopsUrl(productId: Long, region: String, lang: Lang = Lang.UA) = call("shops_url", args { put("lang", langArg(lang)); put("productId", productId); put("region", region) }, String.serializer())
    fun siteSearchUrl(query: String, lang: Lang = Lang.UA) = call("site_search_url", args { put("lang", langArg(lang)); put("query", query) }, String.serializer())
    fun upcLookupUrl(barcode: String) = call("upc_lookup_url", args { put("barcode", barcode) }, String.serializer())
}
