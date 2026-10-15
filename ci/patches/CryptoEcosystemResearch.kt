package com.localtrading.council.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Public, read-only crypto ecosystem research. Signals are leads, not verified airdrop eligibility or promised yield. */
object CryptoEcosystemResearch {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    fun fetchSnapshot(): String {
        val sections = linkedMapOf<String, MutableList<String>>(
            "DEX / token pools" to mutableListOf(), "DeFi / yield / TVL" to mutableListOf(),
            "NFT / gaming" to mutableListOf(), "RWA / tokenized assets" to mutableListOf(),
            "Airdrop / incentives — verify eligibility" to mutableListOf(),
            "Fundraising / ecosystem catalysts" to mutableListOf()
        )
        runCatching {
            val rows = JSONArray(get("https://api.llama.fi/protocols"))
            val ranked = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .filter { it.optDouble("tvl", 0.0) > 0.0 }.sortedByDescending { it.optDouble("tvl", 0.0) }.take(180)
            ranked.forEach { p ->
                val name = p.optString("name", "Unknown")
                val category = p.optString("category", "").lowercase(Locale.ROOT)
                val chain = p.optString("chain", p.optJSONArray("chains")?.optString(0).orEmpty())
                val tvl = p.optDouble("tvl", 0.0)
                val line = "$name | chain=$chain | TVL_USD=${"%.0f".format(Locale.US, tvl)} | category=$category"
                when {
                    category.contains("nft") || category.contains("gaming") -> sections["NFT / gaming"]!!.add(line)
                    category.contains("rwa") || category.contains("real world") -> sections["RWA / tokenized assets"]!!.add(line)
                    category.contains("yield") || category.contains("liquid staking") || category.contains("lending") -> sections["DeFi / yield / TVL"]!!.add(line)
                }
                if (p.optBoolean("listedAtAirdrop", false) || p.optBoolean("airdrop", false))
                    sections["Airdrop / incentives — verify eligibility"]!!.add("$name | possible incentive metadata; verify official campaign, dates, eligibility and sybil rules")
            }
        }
        runCatching {
            val rows = JSONObject(get("https://yields.llama.fi/pools")).optJSONArray("data") ?: JSONArray()
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .filter { it.optDouble("tvlUsd", 0.0) >= 1_000_000.0 && it.optDouble("apy", -1.0) in 0.0..100.0 }
                .sortedByDescending { it.optDouble("tvlUsd", 0.0) }.take(12).forEach { p ->
                    sections["DeFi / yield / TVL"]!!.add(
                        "${p.optString("project")} / ${p.optString("symbol")} | chain=${p.optString("chain")} | TVL_USD=${"%.0f".format(Locale.US, p.optDouble("tvlUsd"))} | APY=${"%.2f".format(Locale.US, p.optDouble("apy"))}% | pool=${p.optString("pool")}"
                    )
                }
        }
        runCatching {
            val rows = JSONArray(get("https://api.llama.fi/raises"))
            for (i in 0 until minOf(rows.length(), 100)) {
                val p = rows.optJSONObject(i) ?: continue
                val name = p.optString("name", "")
                val category = p.optString("category", "").lowercase(Locale.ROOT)
                val line = "$name | category=${p.optString("category")} | amount=${p.optString("amount")} | date=${p.optString("date")}"
                sections["Fundraising / ecosystem catalysts"]!!.add(line)
                if (category.contains("rwa") || name.contains("tokeniz", true)) sections["RWA / tokenized assets"]!!.add(line)
            }
        }
        runCatching {
            for (network in listOf("solana", "base", "eth", "arbitrum", "bsc", "polygon", "optimism", "avax")) {
                val rows = JSONObject(get("https://api.geckoterminal.com/api/v2/networks/$network/trending_pools")).optJSONArray("data") ?: continue
                for (i in 0 until minOf(rows.length(), 8)) {
                    val attrs = rows.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val reserve = attrs.optString("reserve_in_usd").toDoubleOrNull()
                        ?: attrs.optJSONObject("reserve_in_usd")?.optString("value")?.toDoubleOrNull() ?: 0.0
                    val volume = attrs.optJSONObject("volume_usd")?.optString("h24")?.toDoubleOrNull() ?: 0.0
                    val change = attrs.optJSONObject("price_change_percentage")?.optString("h24")?.toDoubleOrNull() ?: 0.0
                    if (reserve >= 100_000 && volume >= 50_000) sections["DEX / token pools"]!!.add(
                        "$network | ${attrs.optString("name")} | pool=${attrs.optString("address")} | reserve_USD=${"%.0f".format(Locale.US, reserve)} | volume24h_USD=${"%.0f".format(Locale.US, volume)} | change24h_pct=${"%.2f".format(Locale.US, change)}"
                    )
                }
            }
        }
        val triage = sections.flatMap { (domain, items) ->
            items.distinct().map { item -> Triage(domain, item, triageScore(domain, item), triageRisks(domain, item)) }
        }.sortedByDescending { it.score }.take(18)

        return buildString {
            appendLine("CRYPTO ECOSYSTEM RESEARCH SNAPSHOT (public read-only data; timestamp=${System.currentTimeMillis()})")
            appendLine()
            appendLine("## Opportunity triage (heuristic prioritization, NOT a buy/sell signal)")
            if (triage.isEmpty()) appendLine("No evidence-backed candidate in this cycle.")
            triage.forEachIndexed { index, item ->
                val confidence = when {
                    item.score >= 65.0 -> "MEDIUM — verify independently"
                    item.score >= 35.0 -> "LOW — research lead only"
                    else -> "VERY LOW — insufficient evidence"
                }
                appendLine("${index + 1}. ${item.domain} | triage_score=${"%.1f".format(Locale.US, item.score)}/100 | confidence=$confidence")
                appendLine("   Evidence: ${item.evidence.take(340)}")
                appendLine("   Risk checklist: ${item.risks}")
            }
            appendLine("No centralized-exchange account or CEX feed is used by this collector. Missing data means UNKNOWN.")
            sections.forEach { (title, items) ->
                appendLine()
                appendLine("## $title")
                if (items.isEmpty()) appendLine("No fresh source data returned in this cycle.")
                else items.distinct().take(12).forEach { appendLine("- " + it.take(420)) }
            }
            appendLine()
            appendLine("MANDATORY ANALYSIS: DEX liquidity/volume/slippage/pool age/contract risks; DeFi sustainable yield vs incentives, TVL, smart-contract/oracle/bridge/impermanent-loss/liquidation risks; airdrop official-source verification, eligibility unknown until checked, time/cost/sybil/scam risks; NFT floor/volume/concentration/marketplace risks; RWA issuer/jurisdiction/redemption/custody/proof-of-reserves/transfer restrictions.")
            appendLine("Rank research hypotheses by evidence, cost/time, downside and confidence. Never imply eligibility, safety or profit. Say NO OPPORTUNITY when evidence is weak. These selected public feeds are not comprehensive.")
        }.takeLast(14000)
    }

    private data class Triage(val domain: String, val evidence: String, val score: Double, val risks: String)

    /** Transparent prescreening only. Missing evidence lowers score; it never invents eligibility or returns. */
    private fun triageScore(domain: String, item: String): Double {
        val text = item.lowercase(Locale.ROOT)
        var score = 18.0
        if ("tvl_usd=" in text) score += 12.0
        if ("volume24h_usd=" in text) score += 12.0
        if ("reserve_usd=" in text) score += 12.0
        if ("apy=" in text) score += 8.0
        if ("chain=" in text) score += 5.0
        if ("pool=" in text) score += 5.0
        if ("date=" in text) score += 3.0
        if ("unknown" in text || "verify" in text || "possible incentive" in text) score -= 12.0
        if ("rwa" in domain.lowercase(Locale.ROOT)) score -= 5.0
        if ("nft" in domain.lowercase(Locale.ROOT)) score -= 4.0
        if ("airdrop" in domain.lowercase(Locale.ROOT)) score -= 10.0
        if ("apy=" in text) {
            val apy = Regex("""APY=([0-9]+(?:\.[0-9]+)?)%""").find(item)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (apy != null && apy > 30.0) score -= 12.0
        }
        return score.coerceIn(0.0, 100.0)
    }

    private fun triageRisks(domain: String, item: String): String {
        val lower = (domain + " " + item).lowercase(Locale.ROOT)
        val risks = mutableListOf<String>()
        if ("dex" in lower || "pool=" in lower) risks += "pool liquidity, slippage, token contract, pool age"
        if ("defi" in lower || "apy=" in lower || "tvl" in lower) risks += "smart contract, oracle/bridge, TVL outflow, yield sustainability"
        if ("airdrop" in lower || "incentive" in lower) risks += "official campaign, eligibility, sybil rules, fees and scam links"
        if ("nft" in lower || "gaming" in lower) risks += "floor/volume manipulation, concentration, marketplace liquidity"
        if ("rwa" in lower || "tokeniz" in lower) risks += "issuer, jurisdiction, redemption, custody and transfer restrictions"
        if ("fundraising" in lower || "amount=" in lower) risks += "announcement recency, source confirmation, unlocks and valuation"
        return risks.distinct().joinToString("; ").ifBlank { "source freshness and independent verification" }
    }
    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MultiAITradingCouncil-Android/0.5").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: error("Empty response")
            require(response.isSuccessful) { "HTTP ${response.code} from public research source" }
            return body
        }
    }
}
