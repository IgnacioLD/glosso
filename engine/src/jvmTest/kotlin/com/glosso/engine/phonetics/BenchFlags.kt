package com.glosso.engine.phonetics

/**
 * Scoring flags the bench can switch on to measure them against the default,
 * without changing what production does. `GLOSSO_DISTINCT_VARIANT_CAP=1` counts
 * only distinct readings towards the variant cap (see
 * [PhoneticComparator.DISTINCT_VARIANT_CAP_DEFAULT]).
 */
internal object BenchFlags {
    val distinctVariantCap: Boolean =
        System.getenv("GLOSSO_DISTINCT_VARIANT_CAP")?.let { it == "1" }
            ?: PhoneticComparator.DISTINCT_VARIANT_CAP_DEFAULT

    fun describe(): String = "distinct_variant_cap=$distinctVariantCap"
}
