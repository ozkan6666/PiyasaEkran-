package com.kuyumcuekrani.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

enum class PriceSourceType(val displayName: String, val shortName: String) {
    HAREM("Harem Altın", "Harem"),
    ALTINKAYNAK("Altınkaynak", "Altınkaynak"),
    ODACI("Odacı Döviz", "Odacı"),
    KAPALICARSI("Kapalıçarşı", "Kapalıçarşı")
}

enum class PriceCategory(val title: String) {
    GOLD("ALTIN FİYATLARI"),
    CURRENCY("DÖVİZ KURLARI"),
    PRECIOUS_METALS("DEĞERLİ MADENLER & ONS")
}

enum class TrendDirection {
    UP, DOWN, STABLE
}

data class PriceItem(
    val source: PriceSourceType,
    val symbol: String,
    val name: String,
    val category: PriceCategory,
    val buyPrice: Double,
    val sellPrice: Double,
    val currency: String = "TL",
    val changePercent: Double = 0.0,
    val timestamp: String = "",
    val isAvailable: Boolean = true,
    val trend: TrendDirection = TrendDirection.STABLE
) {
    val spread: Double get() = if (isAvailable && sellPrice >= buyPrice) sellPrice - buyPrice else 0.0
    val spreadPercent: Double get() = if (isAvailable && buyPrice > 0.0) ((sellPrice - buyPrice) / buyPrice) * 100.0 else 0.0
}

object SmartNumberParser {
    fun parse(value: Any?): Double {
        if (value == null) return 0.0
        if (value is Number) return value.toDouble()
        var s = value.toString().trim()
        if (s.isEmpty() || s == "null" || s == "—" || s == "-") return 0.0

        s = s.replace("TL", "")
            .replace("₺", "")
            .replace("$", "")
            .replace("€", "")
            .replace("%", "")
            .replace(" ", "")
            .trim()

        val hasDot = s.contains(".")
        val hasComma = s.contains(",")

        return try {
            when {
                hasDot && hasComma -> {
                    val lastDot = s.lastIndexOf('.')
                    val lastComma = s.lastIndexOf(',')
                    if (lastComma > lastDot) {
                        s.replace(".", "").replace(",", ".").toDouble()
                    } else {
                        s.replace(",", "").toDouble()
                    }
                }
                hasComma -> {
                    s.replace(",", ".").toDouble()
                }
                hasDot -> {
                    val parts = s.split(".")
                    if (parts.size == 2 && parts[1].length == 3 && parts[0].length <= 2) {
                        // Binlik ayracı olarak nokta kullanımı (örn: 6.671 -> 6671.0)
                        s.replace(".", "").toDouble()
                    } else {
                        s.toDouble()
                    }
                }
                else -> {
                    s.toDouble()
                }
            }
        } catch (_: Exception) {
            0.0
        }
    }
}

object NetworkClient {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String, referer: String? = null): String? {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")

            if (referer != null) {
                reqBuilder.header("Referer", referer)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

interface IPriceSource {
    val sourceType: PriceSourceType
    suspend fun fetchPrices(): Result<List<PriceItem>>
}

class HaremPriceSource : IPriceSource {
    override val sourceType = PriceSourceType.HAREM

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://canlipiyasalar.haremaltin.com/tmp/altin.json" to "https://canlipiyasalar.haremaltin.com/",
            "https://www.haremaltin.com/dashboard/ajax/doviz" to "https://www.haremaltin.com/"
        )

        for ((url, referer) in urls) {
            try {
                val raw = NetworkClient.get(url, referer) ?: continue
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val list = mutableListOf<PriceItem>()
                val root = JSONObject(raw)
                val dataObj = if (root.has("data")) root.getJSONObject("data") else root

                val mappings = listOf(
                    Triple("ALTIN", "Gram Altın", PriceCategory.GOLD),
                    Triple("KULCEALTIN", "Has Altın", PriceCategory.GOLD),
                    Triple("AYAR22", "22 Ayar Bilezik", PriceCategory.GOLD),
                    Triple("AYAR14", "14 Ayar", PriceCategory.GOLD),
                    Triple("CEYREK_YENI", "Çeyrek Yeni", PriceCategory.GOLD),
                    Triple("CEYREK_ESKI", "Çeyrek Eski", PriceCategory.GOLD),
                    Triple("YARIM_YENI", "Yarım Yeni", PriceCategory.GOLD),
                    Triple("TEK_YENI", "Tam Yeni", PriceCategory.GOLD),
                    Triple("ATA_YENI", "Ata Altın", PriceCategory.GOLD),
                    Triple("GREMSE_YENI", "Gremse", PriceCategory.GOLD),
                    Triple("USDTRY", "USD/TRY", PriceCategory.CURRENCY),
                    Triple("EURTRY", "EUR/TRY", PriceCategory.CURRENCY),
                    Triple("GBPTRY", "GBP/TRY", PriceCategory.CURRENCY),
                    Triple("CHFTRY", "CHF/TRY", PriceCategory.CURRENCY),
                    Triple("ONS", "Ons Altın", PriceCategory.PRECIOUS_METALS),
                    Triple("GUMUSTRY", "Gümüş (Gram)", PriceCategory.PRECIOUS_METALS)
                )

                for ((sym, name, cat) in mappings) {
                    if (dataObj.has(sym)) {
                        val itemObj = dataObj.optJSONObject(sym) ?: continue
                        val buy = SmartNumberParser.parse(itemObj.opt("alis"))
                        val sell = SmartNumberParser.parse(itemObj.opt("satis"))
                        val chg = SmartNumberParser.parse(itemObj.opt("degisim"))
                        if (buy > 0.0 || sell > 0.0) {
                            list.add(
                                PriceItem(
                                    source = PriceSourceType.HAREM,
                                    symbol = sym,
                                    name = name,
                                    category = cat,
                                    buyPrice = if (buy > 0) buy else sell,
                                    sellPrice = if (sell > 0) sell else buy,
                                    currency = if (sym == "ONS") "USD" else "TL",
                                    changePercent = chg,
                                    timestamp = timeNow,
                                    trend = if (chg > 0) TrendDirection.UP else if (chg < 0) TrendDirection.DOWN else TrendDirection.STABLE
                                )
                            )
                        }
                    }
                }

                if (list.isNotEmpty()) return@withContext Result.success(list)
            } catch (_: Exception) {}
        }

        Result.failure(Exception("Harem Altın canlı sunucusuna ulaşılamadı."))
    }
}

class AltinkaynakPriceSource : IPriceSource {
    override val sourceType = PriceSourceType.ALTINKAYNAK

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val list = mutableListOf<PriceItem>()

        try {
            val html = NetworkClient.get("https://www.altinkaynak.com/", "https://www.altinkaynak.com/")
            if (html != null && html.contains("Altınkaynak")) {
                val patterns = listOf(
                    Triple("Has Altın", "Has Altın", PriceCategory.GOLD),
                    Triple("Külçe Altın", "Külçe Altın", PriceCategory.GOLD),
                    Triple("Ata Cumhuriyet", "Ata Altın", PriceCategory.GOLD),
                    Triple("Çeyrek Altın", "Çeyrek Yeni", PriceCategory.GOLD),
                    Triple("Dolar", "USD/TRY", PriceCategory.CURRENCY),
                    Triple("Euro", "EUR/TRY", PriceCategory.CURRENCY)
                )

                for ((label, stdName, cat) in patterns) {
                    val p = Pattern.compile("$label\\s*</td>.*?<td[^>]*>([0-9.,-]+)</td>\\s*<td[^>]*>([0-9.,]+)</td>\\s*<td[^>]*>([0-9.,]+)</td>", Pattern.DOTALL)
                    val m = p.matcher(html)
                    if (m.find()) {
                        val chg = SmartNumberParser.parse(m.group(1))
                        val buy = SmartNumberParser.parse(m.group(2))
                        val sell = SmartNumberParser.parse(m.group(3))
                        if (buy > 0.0 || sell > 0.0) {
                            list.add(
                                PriceItem(
                                    source = PriceSourceType.ALTINKAYNAK,
                                    symbol = label.uppercase(Locale.ENGLISH).replace(" ", "_"),
                                    name = stdName,
                                    category = cat,
                                    buyPrice = if (buy > 0) buy else sell,
                                    sellPrice = if (sell > 0) sell else buy,
                                    currency = "TL",
                                    changePercent = chg,
                                    timestamp = timeNow,
                                    trend = if (chg > 0) TrendDirection.UP else if (chg < 0) TrendDirection.DOWN else TrendDirection.STABLE
                                )
                            )
                        }
                    }
                }

                val onsMatcher = Pattern.compile("ONS\\s*:\\s*([0-9.,]+)").matcher(html)
                if (onsMatcher.find()) {
                    val onsVal = SmartNumberParser.parse(onsMatcher.group(1))
                    if (onsVal > 0.0) {
                        list.add(
                            PriceItem(
                                source = PriceSourceType.ALTINKAYNAK,
                                symbol = "ONS",
                                name = "Ons Altın",
                                category = PriceCategory.PRECIOUS_METALS,
                                buyPrice = onsVal,
                                sellPrice = onsVal,
                                currency = "USD",
                                timestamp = timeNow
                            )
                        )
                    }
                }

                if (list.isNotEmpty()) return@withContext Result.success(list)
            }
        } catch (_: Exception) {}

        Result.failure(Exception("Altınkaynak verisi şu anda alınamıyor."))
    }
}

class OdaciPriceSource : IPriceSource {
    override val sourceType = PriceSourceType.ODACI

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val list = mutableListOf<PriceItem>()

        try {
            val html = NetworkClient.get("https://odaci.com/", "https://odaci.com/")
            if (html != null && html.contains("Odaci.com")) {
                val rowPattern = Pattern.compile("<tr[^>]*>\\s*<td[^>]*>([A-Z0-9]+)\\s+([^<]+)</td>\\s*<td[^>]*>([0-9.,]+)</td>\\s*<td[^>]*>([0-9.,]+)</td>", Pattern.DOTALL)
                val matcher = rowPattern.matcher(html)

                val symbolMap = mapOf(
                    "GRT" to Pair("Gram Altın", PriceCategory.GOLD),
                    "GRB" to Pair("Bozuk Gram", PriceCategory.GOLD),
                    "CEY" to Pair("Çeyrek Yeni", PriceCategory.GOLD),
                    "YRM" to Pair("Yarım Yeni", PriceCategory.GOLD),
                    "LRA" to Pair("Tam Yeni", PriceCategory.GOLD),
                    "ATA" to Pair("Ata Altın", PriceCategory.GOLD),
                    "GRY" to Pair("Gremse", PriceCategory.GOLD),
                    "E22" to Pair("22 Ayar", PriceCategory.GOLD),
                    "E14" to Pair("14 Ayar", PriceCategory.GOLD),
                    "USD" to Pair("USD/TRY", PriceCategory.CURRENCY),
                    "EUR" to Pair("EUR/TRY", PriceCategory.CURRENCY),
                    "GBP" to Pair("GBP/TRY", PriceCategory.CURRENCY),
                    "CHF" to Pair("CHF/TRY", PriceCategory.CURRENCY)
                )

                while (matcher.find()) {
                    val code = matcher.group(1).trim()
                    val buy = SmartNumberParser.parse(matcher.group(3))
                    val sell = SmartNumberParser.parse(matcher.group(4))

                    symbolMap[code]?.let { (stdName, cat) ->
                        if (buy > 0.0 || sell > 0.0) {
                            list.add(
                                PriceItem(
                                    source = PriceSourceType.ODACI,
                                    symbol = code,
                                    name = stdName,
                                    category = cat,
                                    buyPrice = if (buy > 0) buy else sell,
                                    sellPrice = if (sell > 0) sell else buy,
                                    currency = "TL",
                                    timestamp = timeNow
                                )
                            )
                        }
                    }
                }

                if (list.isNotEmpty()) return@withContext Result.success(list)
            }
        } catch (_: Exception) {}

        Result.failure(Exception("Odacı Döviz verisi alınamadı."))
    }
}

class KapalicarsiPriceSource : IPriceSource {
    override val sourceType = PriceSourceType.KAPALICARSI

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://kapalicarsi.apiluna.org",
            "https://kapali-carsi-altin-api.vercel.app/api/altin"
        )

        for (url in urls) {
            try {
                val raw = NetworkClient.get(url) ?: continue
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val list = mutableListOf<PriceItem>()

                if (raw.trim().startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val code = obj.optString("code")
                        val buy = SmartNumberParser.parse(obj.opt("alis"))
                        val sell = SmartNumberParser.parse(obj.opt("satis"))

                        if (buy > 0.0 || sell > 0.0) {
                            val cat = when {
                                code.contains("USD") || code.contains("EUR") || code.contains("GBP") -> PriceCategory.CURRENCY
                                code == "ONS" || code.contains("GUMUS") -> PriceCategory.PRECIOUS_METALS
                                else -> PriceCategory.GOLD
                            }
                            val displayName = when (code) {
                                "ALTIN" -> "Gram Altın"
                                "CEYREK_YENI" -> "Çeyrek Yeni"
                                "YARIM_YENI" -> "Yarım Yeni"
                                "TEK_YENI" -> "Tam Yeni"
                                "ATA_YENI" -> "Ata Altın"
                                "KULCEALTIN" -> "Has Altın"
                                "USDTRY" -> "USD/TRY"
                                "EURTRY" -> "EUR/TRY"
                                "ONS" -> "Ons Altın"
                                else -> code.replace("_", " ")
                            }

                            list.add(
                                PriceItem(
                                    source = PriceSourceType.KAPALICARSI,
                                    symbol = code,
                                    name = displayName,
                                    category = cat,
                                    buyPrice = if (buy > 0) buy else sell,
                                    sellPrice = if (sell > 0) sell else buy,
                                    currency = if (code == "ONS") "USD" else "TL",
                                    timestamp = timeNow
                                )
                            )
                        }
                    }
                    if (list.isNotEmpty()) return@withContext Result.success(list)
                }
            } catch (_: Exception) {}
        }

        Result.failure(Exception("Kapalıçarşı canlı verisi şu anda alınamıyor."))
    }
}

class PriceRepository {
    private val sources: Map<PriceSourceType, IPriceSource> = mapOf(
        PriceSourceType.HAREM to HaremPriceSource(),
        PriceSourceType.ALTINKAYNAK to AltinkaynakPriceSource(),
        PriceSourceType.ODACI to OdaciPriceSource(),
        PriceSourceType.KAPALICARSI to KapalicarsiPriceSource()
    )

    suspend fun getPrices(sourceType: PriceSourceType): Result<List<PriceItem>> {
        val src = sources[sourceType] ?: return Result.failure(Exception("Geçersiz kaynak."))
        return src.fetchPrices()
    }

    suspend fun getMultiSourcePrices(types: List<PriceSourceType>): Map<PriceSourceType, List<PriceItem>> {
        val map = mutableMapOf<PriceSourceType, List<PriceItem>>()
        for (type in types) {
            val res = sources[type]?.fetchPrices()
            if (res != null && res.isSuccess) {
                map[type] = res.getOrDefault(emptyList())
            }
        }
        return map
    }
}

sealed class ScreenState {
    data object Loading : ScreenState()
    data class Success(val prices: List<PriceItem>, val timestamp: String, val isFromCache: Boolean = false) : ScreenState()
    data class Error(val message: String, val lastSuccessPrices: List<PriceItem>?, val lastSuccessTime: String?) : ScreenState()
}

class MainViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = PriceRepository()
    private val prefs = application.getSharedPreferences("kuyumcu_prefs", Context.MODE_PRIVATE)

    private val _currentSource = MutableStateFlow(
        PriceSourceType.entries.find { it.name == prefs.getString("selected_source", PriceSourceType.HAREM.name) } ?: PriceSourceType.HAREM
    )
    val currentSource: StateFlow<PriceSourceType> = _currentSource.asStateFlow()

    private val _refreshInterval = MutableStateFlow(prefs.getInt("refresh_interval", 5))
    val refreshInterval: StateFlow<Int> = _refreshInterval.asStateFlow()

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Loading)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(
        prefs.getStringSet("favorites", setOf("Gram Altın", "Çeyrek Yeni", "USD/TRY", "Ata Altın")) ?: emptySet()
    )
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _comparisonData = MutableStateFlow<Map<PriceSourceType, List<PriceItem>>>(emptyMap())
    val comparisonData: StateFlow<Map<PriceSourceType, List<PriceItem>>> = _comparisonData.asStateFlow()

    private var pollingJob: Job? = null
    private val sourceCaches = mutableMapOf<PriceSourceType, List<PriceItem>>()
    private val sourceCacheTimes = mutableMapOf<PriceSourceType, String>()
    private val previousPricesMap = mutableMapOf<String, Double>()

    init {
        startPolling()
    }

    fun selectSource(source: PriceSourceType) {
        if (_currentSource.value == source) return
        _currentSource.value = source
        prefs.edit().putString("selected_source", source.name).apply()

        val cached = sourceCaches[source]
        val cachedTime = sourceCacheTimes[source]
        if (cached != null && cached.isNotEmpty()) {
            _screenState.value = ScreenState.Success(cached, cachedTime ?: "", isFromCache = true)
        } else {
            _screenState.value = ScreenState.Loading
        }
        startPolling()
    }

    fun setRefreshInterval(seconds: Int) {
        _refreshInterval.value = seconds
        prefs.edit().putInt("refresh_interval", seconds).apply()
        startPolling()
    }

    fun refreshCurrentSource() {
        viewModelScope.launch {
            loadPricesForSource(_currentSource.value)
        }
    }

    fun toggleFavorite(productName: String) {
        val updated = _favorites.value.toMutableSet()
        if (updated.contains(productName)) updated.remove(productName) else updated.add(productName)
        _favorites.value = updated
        prefs.edit().putStringSet("favorites", updated).apply()
    }

    fun loadComparison() {
        viewModelScope.launch {
            _comparisonData.value = repo.getMultiSourcePrices(PriceSourceType.entries)
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                loadPricesForSource(_currentSource.value)
                val sec = _refreshInterval.value
                if (sec <= 0) break
                delay(sec * 1000L)
            }
        }
    }

    private suspend fun loadPricesForSource(source: PriceSourceType) {
        val result = repo.getPrices(source)
        result.onSuccess { rawList ->
            val updatedWithTrends = rawList.map { item ->
                val key = "${source.name}_${item.symbol}"
                val prev = previousPricesMap[key]
                val trend = when {
                    prev == null || prev == item.buyPrice -> item.trend
                    item.buyPrice > prev -> TrendDirection.UP
                    else -> TrendDirection.DOWN
                }
                previousPricesMap[key] = item.buyPrice
                item.copy(trend = trend)
            }
            val time = updatedWithTrends.firstOrNull()?.timestamp ?: SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            sourceCaches[source] = updatedWithTrends
            sourceCacheTimes[source] = time
            _screenState.value = ScreenState.Success(updatedWithTrends, time, isFromCache = false)
        }.onFailure { _ ->
            val cached = sourceCaches[source]
            val time = sourceCacheTimes[source]
            _screenState.value = ScreenState.Error(
                message = "${source.displayName} verisi alınamadı.",
                lastSuccessPrices = cached,
                lastSuccessTime = time
            )
        }
    }
}

val GoldPrimary = Color(0xFFD4AF37)
val GoldDark = Color(0xFFAA820A)
val ColorRise = Color(0xFF00C853)
val ColorFall = Color(0xFFD50000)
val HeaderDark = Color(0xFF1E2124)
val HeaderLight = Color(0xFFE8ECEF)

@Composable
fun KuyumcuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = GoldPrimary,
            background = Color(0xFF121417),
            surface = Color(0xFF1B1E22),
            surfaceVariant = Color(0xFF282C34),
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = GoldDark,
            background = Color(0xFFF5F6F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFEFEF),
            onPrimary = Color.White,
            onBackground = Color(0xFF1A1A1A),
            onSurface = Color(0xFF1A1A1A)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainApp(viewModel: MainViewModel) {
    var currentTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Storefront, contentDescription = null) },
                    label = { Text("Tabela", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = {
                        currentTab = 1
                        viewModel.loadComparison()
                    },
                    icon = { Icon(Icons.Default.CompareArrows, contentDescription = null) },
                    label = { Text("Karşılaştır", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Ayarlar", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentTab) {
                0 -> BoardScreen(viewModel)
                1 -> ComparisonScreen(viewModel)
                2 -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
fun BoardScreen(viewModel: MainViewModel) {
    val currentSource by viewModel.currentSource.collectAsState()
    val state by viewModel.screenState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var onlyFavorites by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ColorRise)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "KUYUMCU EKRANI",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "Kaynak: ${currentSource.displayName}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onlyFavorites = !onlyFavorites }) {
                    Icon(
                        imageVector = if (onlyFavorites) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favoriler",
                        tint = if (onlyFavorites) GoldPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { viewModel.refreshCurrentSource() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        SourceSelectorRow(
            selected = currentSource,
            onSelect = { viewModel.selectSource(it) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        when (val s = state) {
            is ScreenState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = GoldPrimary)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("${currentSource.displayName} verileri alınıyor...", fontSize = 13.sp)
                    }
                }
            }
            is ScreenState.Success -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (s.isFromCache) "Son Başarılı Veri: ${s.timestamp}" else "Canlı Piyasa • ${s.timestamp}",
                        fontSize = 11.sp,
                        color = if (s.isFromCache) GoldPrimary else ColorRise,
                        fontWeight = FontWeight.Bold
                    )
                    if (onlyFavorites) {
                        Text(
                            text = "Yalnızca Favoriler",
                            fontSize = 11.sp,
                            color = GoldPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                PriceTableView(
                    prices = if (onlyFavorites) s.prices.filter { favorites.contains(it.name) } else s.prices,
                    favorites = favorites,
                    onToggleFav = { viewModel.toggleFavorite(it) }
                )
            }
            is ScreenState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = s.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        if (s.lastSuccessTime != null) {
                            Text(
                                text = "Son başarılı veri: ${s.lastSuccessTime}",
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (s.lastSuccessPrices != null && s.lastSuccessPrices.isNotEmpty()) {
                    PriceTableView(
                        prices = if (onlyFavorites) s.lastSuccessPrices.filter { favorites.contains(it.name) } else s.lastSuccessPrices,
                        favorites = favorites,
                        onToggleFav = { viewModel.toggleFavorite(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun SourceSelectorRow(selected: PriceSourceType, onSelect: (PriceSourceType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PriceSourceType.entries.forEach { src ->
            val isSel = src == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(src) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = src.shortName,
                    fontSize = 11.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun PriceTableView(
    prices: List<PriceItem>,
    favorites: Set<String>,
    onToggleFav: (String) -> Unit
) {
    val goldList = prices.filter { it.category == PriceCategory.GOLD }
    val currencyList = prices.filter { it.category == PriceCategory.CURRENCY }
    val metalsList = prices.filter { it.category == PriceCategory.PRECIOUS_METALS }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (goldList.isNotEmpty()) {
            item { CategoryHeader(PriceCategory.GOLD.title) }
            items(goldList, key = { "g_" + it.symbol + it.name }) { item ->
                PriceRow(item, isFav = favorites.contains(item.name), onToggleFav = { onToggleFav(item.name) })
            }
        }

        if (currencyList.isNotEmpty()) {
            item { CategoryHeader(PriceCategory.CURRENCY.title) }
            items(currencyList, key = { "c_" + it.symbol + it.name }) { item ->
                PriceRow(item, isFav = favorites.contains(item.name), onToggleFav = { onToggleFav(item.name) })
            }
        }

        if (metalsList.isNotEmpty()) {
            item { CategoryHeader(PriceCategory.PRECIOUS_METALS.title) }
            items(metalsList, key = { "m_" + it.symbol + it.name }) { item ->
                PriceRow(item, isFav = favorites.contains(item.name), onToggleFav = { onToggleFav(item.name) })
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun CategoryHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSystemInDarkTheme()) HeaderDark else HeaderLight, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1.3f),
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "ALIŞ",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = "SATIŞ",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = "MAKAS",
            modifier = Modifier.weight(0.9f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun PriceRow(item: PriceItem, isFav: Boolean, onToggleFav: () -> Unit) {
    val trendColor by animateColorAsState(
        targetValue = when (item.trend) {
            TrendDirection.UP -> ColorRise
            TrendDirection.DOWN -> ColorFall
            TrendDirection.STABLE -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 250),
        label = "trendColor"
    )

    val trendSymbol = when (item.trend) {
        TrendDirection.UP -> "▲ "
        TrendDirection.DOWN -> "▼ "
        TrendDirection.STABLE -> "— "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
            .clickable { onToggleFav() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
            if (isFav) {
                Text(text = "★ ", color = GoldPrimary, fontSize = 12.sp)
            }
            Text(
                text = item.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1
            )
        }

        Text(
            text = if (item.isAvailable && item.buyPrice > 0) String.format(Locale.GERMANY, "%,.2f", item.buyPrice) else "—",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trendSymbol,
                color = trendColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (item.isAvailable && item.sellPrice > 0) String.format(Locale.GERMANY, "%,.2f", item.sellPrice) else "—",
                textAlign = TextAlign.End,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = trendColor
            )
        }

        Column(modifier = Modifier.weight(0.9f), horizontalAlignment = Alignment.End) {
            Text(
                text = if (item.isAvailable && item.spread > 0) String.format(Locale.GERMANY, "%,.1f", item.spread) else "—",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            if (item.spreadPercent > 0.0) {
                Text(
                    text = "%${String.format(Locale.US, "%.1f", item.spreadPercent)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun ComparisonScreen(viewModel: MainViewModel) {
    val compData by viewModel.comparisonData.collectAsState()
    val compareProducts = listOf("Gram Altın", "Çeyrek Yeni", "Tam Yeni", "Ata Altın", "USD/TRY", "EUR/TRY", "Ons Altın")

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PİYASA KARŞILAŞTIRMA",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = { viewModel.loadComparison() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Yenile")
            }
        }
        Text(
            text = "Farklı kaynakların anlık alış-satış farklarını karşılaştırın.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(compareProducts) { prodName ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = prodName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        PriceSourceType.entries.forEach { src ->
                            val item = compData[src]?.find { it.name.equals(prodName, ignoreCase = true) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = src.shortName,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1.2f),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "Alış: " + (if (item != null && item.buyPrice > 0) String.format(Locale.GERMANY, "%,.2f", item.buyPrice) else "—"),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Text(
                                    text = "Satış: " + (if (item != null && item.sellPrice > 0) String.format(Locale.GERMANY, "%,.2f", item.sellPrice) else "—"),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1.2f),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val interval by viewModel.refreshInterval.collectAsState()
    val intervals = listOf(
        Pair(5, "5 sn"),
        Pair(10, "10 sn"),
        Pair(15, "15 sn"),
        Pair(30, "30 sn"),
        Pair(60, "60 sn"),
        Pair(0, "Kapalı")
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "AYARLAR", fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Otomatik Yenileme Sıklığı", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { (sec, label) ->
                val sel = interval == sec
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { viewModel.setRefreshInterval(sec) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "Hakkında & Doğrulanmış Kaynaklar", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Uygulama: Kuyumcu Ekranı v1.0.2\nKaynaklar: Harem Altın, Altınkaynak, Odacı Döviz, Kapalıçarşı",
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Bu uygulama bağımsız bir fiyat takip uygulamasıdır. Kullanılan piyasa verileri ilgili veri kaynaklarının kamuya açık servislerinden doğrudan alınmaktadır. Kaynaklar arasında veri kopyalama yapılmaz; herhangi bir kaynağa erişilemediğinde sahte fiyat üretilmez.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 15.sp
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KuyumcuTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }
}
