package ua.chudo.shared.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductHighlight(
    val kind: String,
    val barcode: String,
    val article: String,
    val name: String,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("promo_price") val promoPrice: Double? = null,
) {
    val label: String
        get() = if (kind == "new") "Новинка" else promoPrice?.let { "Акція · $it грн" } ?: "Акція"
}
