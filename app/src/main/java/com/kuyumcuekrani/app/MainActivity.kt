package com.kuyumcuekrani.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Category { ALTIN, DOVIZ, MADEN }
enum class Trend { UP, DOWN, STABLE }

data class PriceItem(
    val source: String,
    val symbol: String,
    val name: String,
    val category: Category,
    val buyPrice: Double,
    val sellPrice: Double,
    val currency: String = "TL",
    val changePercent: Double = 0.0,
    val trend: Trend = Trend.STABLE
)

interface PriceSource {
    val sourceName: String
    suspend fun fetchPrices(): Result<List<PriceItem>>
}

class HaremPriceSource(private val client: OkHttpClient) : PriceSource {
    override val sourceName: String = "Harem Altın"

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://canlipiyasalar.haremaltin.com/tmp/altin.json")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://canlipiyasalar.haremaltin.com/")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string() ?: return@withContext Result.failure(Exception("Boş cevap"))
                val root = JSONObject(body)
                val dataObj = if (root.has("data")) root.getJSONObject("data") else root

                val list = mutableListOf<PriceItem>()
                val mappings = listOf(
                    Triple("ALTIN", "Gram Altın", Category.ALTIN),
                    Triple("KULCEALTIN", "Has Altın", Category.ALTIN),
                    Triple("CEYREK_YENI", "Çeyrek Yeni", Category.ALTIN),
                    Triple("CEYREK_ESKI", "Çeyrek Eski", Category.ALTIN),
                    Triple("YARIM_YENI", "Yarım Yeni", Category.ALTIN),
                    Triple("YARIM_ESKI", "Yarım Eski", Category.ALTIN),
                    Triple("TEK_YENI", "Tam Yeni", Category.ALTIN),
                    Triple("TEK_ESKI", "Tam Eski", Category.ALTIN),
                    Triple("ATA_YENI", "Ata Altın", Category.ALTIN),
                    Triple("GREMSE_YENI", "Gremse", Category.ALTIN),
                    Triple("AYAR22", "22 Ayar Bilezik", Category.ALTIN),
                    Triple("AYAR14", "14 Ayar", Category.ALTIN),
                    Triple("USDTRY", "USD/TRY", Category.DOVIZ),
                    Triple("EURTRY", "EUR/TRY", Category.DOVIZ),
                    Triple("GBPTRY", "GBP/TRY", Category.DOVIZ),
                    Triple("ONS", "Ons Altın", Category.MADEN),
                    Triple("GUMUSTRY", "Gümüş (TL)", Category.MADEN),
                    Triple("PLATIN", "Platin", Category.MADEN),
                    Triple("PALADYUM", "Paladyum", Category.MADEN)
                )

                for ((key, name, cat) in mappings) {
                    if (dataObj.has(key)) {
                        val item = dataObj.getJSONObject(key)
                        val buy = parseVal(item.optString("alis"))
                        val sell = parseVal(item.optString("satis"))
                        val chg = parseVal(item.optString("degisim"))
                        if (buy > 0.0 && sell > 0.0) {
                            val trend = when {
                                chg > 0.0 -> Trend.UP
                                chg < 0.0 -> Trend.DOWN
                                else -> Trend.STABLE
                            }
                            list.add(
                                PriceItem(
                                    source = sourceName,
                                    symbol = key,
                                    name = name,
                                    category = cat,
                                    buyPrice = buy,
                                    sellPrice = sell,
                                    changePercent = chg,
                                    currency = if (key == "ONS" || key == "PLATIN" || key == "PALADYUM") "USD" else "TL",
                                    trend = trend
                                )
                            )
                        }
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseVal(s: String): Double {
        return s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    }
}

class AltinkaynakPriceSource(private val client: OkHttpClient) : PriceSource {
    override val sourceName: String = "Altınkaynak"

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://data.altinkaynak.com/DataService.asmx/GetGold")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val list = mutableListOf<PriceItem>()
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class OdaciPriceSource(private val client: OkHttpClient) : PriceSource {
    override val sourceName: String = "Odacı"

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://odaci.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val list = mutableListOf<PriceItem>()
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class KapalicarsiPriceSource(private val client: OkHttpClient) : PriceSource {
    override val sourceName: String = "Kapalıçarşı"

    override suspend fun fetchPrices(): Result<List<PriceItem>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://canlipiyasalar.haremaltin.com/tmp/altin.json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val list = mutableListOf<PriceItem>()
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class PriceViewModel : ViewModel() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val sources: List<PriceSource> = listOf(
        HaremPriceSource(client),
        AltinkaynakPriceSource(client),
        OdaciPriceSource(client),
        KapalicarsiPriceSource(client)
    )

    private val _selectedSource = MutableStateFlow(sources[0])
    val selectedSource: StateFlow<PriceSource> = _selectedSource.asStateFlow()

    private val _prices = MutableStateFlow<List<PriceItem>>(emptyList())
    val prices: StateFlow<List<PriceItem>> = _prices.asStateFlow()

    private val _lastUpdate = MutableStateFlow("")
    val lastUpdate: StateFlow<String> = _lastUpdate.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    fun selectSource(source: PriceSource) {
        if (_selectedSource.value != source) {
            _selectedSource.value = source
            _prices.value = emptyList()
            _errorMessage.value = null
            fetchCurrentSource()
        }
    }

    fun refresh() {
        fetchCurrentSource()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchCurrentSource()
                delay(5000L)
            }
        }
    }

    private fun fetchCurrentSource() {
        viewModelScope.launch {
            _isLoading.value = true
            val src = _selectedSource.value
            val res = src.fetchPrices()
            res.onSuccess { data ->
                _prices.value = data
                _errorMessage.value = null
                _lastUpdate.value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            }.onFailure {
                _errorMessage.value = "${src.sourceName} verisi alınamadı."
            }
            _isLoading.value = false
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: PriceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KuyumcuTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun KuyumcuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            primary = Color(0xFFFFD700),
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            background = Color(0xFFF4F6F9),
            surface = Color.White,
            primary = Color(0xFFD4AF37),
            onBackground = Color(0xFF212121),
            onSurface = Color(0xFF212121)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainAppScreen(viewModel: PriceViewModel) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Storefront, null) },
                    label = { Text("Tabela") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.CompareArrows, null) },
                    label = { Text("Karşılaştır") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Ayarlar") }
                )
            }
        }
    ) { p ->
        Box(modifier = Modifier.padding(p)) {
            when (tab) {
                0 -> BoardScreen(viewModel)
                1 -> ComparisonScreen()
                2 -> AboutSettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(vm: PriceViewModel) {
    val prices by vm.prices.collectAsState()
    val source by vm.selectedSource.collectAsState()
    val lastUp by vm.lastUpdate.collectAsState()
    val err by vm.errorMessage.collectAsState()
    val loading by vm.isLoading.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("KUYUMCU EKRANI", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Canlı Piyasa", fontSize = 12.sp, color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                    Button(
                        onClick = { menuOpen = true },
                        modifier = Modifier.menuAnchor(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(source.sourceName, fontSize = 12.sp)
                    }
                    ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        vm.sources.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.sourceName) },
                                onClick = {
                                    vm.selectSource(s)
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, "Yenile")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Kaynak: ${source.sourceName}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (lastUp.isNotEmpty()) {
                Text("Son güncelleme: $lastUp", fontSize = 12.sp, color = Color.Gray)
            }
        }

        if (err != null) {
            Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(err ?: "", color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }

        if (loading && prices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val gold = prices.filter { it.category == Category.ALTIN }
                val curr = prices.filter { it.category == Category.DOVIZ }
                val metals = prices.filter { it.category == Category.MADEN }

                if (gold.isNotEmpty()) {
                    item { SectionHeader("ALTIN") }
                    items(gold) { TableRow(it) }
                }
                if (curr.isNotEmpty()) {
                    item { SectionHeader("DÖVİZ") }
                    items(curr) { TableRow(it) }
                }
                if (metals.isNotEmpty()) {
                    item { SectionHeader("DEĞERLİ MADENLER") }
                    items(metals) { TableRow(it) }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun TableRow(item: PriceItem) {
    val spread = item.sellPrice - item.buyPrice
    val spreadPct = if (item.buyPrice > 0.0) (spread / item.buyPrice) * 100 else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.2f)) {
                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val spreadText = String.format(Locale.US, "%.2f", spread)
                val spreadPctText = String.format(Locale.US, "%.2f", spreadPct)
                Text("Makas: $spreadText (%$spreadPctText)", fontSize = 10.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                Text("Alış", fontSize = 10.sp, color = Color.Gray)
                Text(String.format(Locale.US, "%.2f", item.buyPrice), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                Text("Satış", fontSize = 10.sp, color = Color.Gray)
                val trendColor = when (item.trend) {
                    Trend.UP -> Color(0xFF2E7D32)
                    Trend.DOWN -> Color(0xFFC62828)
                    Trend.STABLE -> Color.Gray
                }
                val trendSym = when (item.trend) {
                    Trend.UP -> "▲"
                    Trend.DOWN -> "▼"
                    Trend.STABLE -> "—"
                }
                Text(
                    "${String.format(Locale.US, "%.2f", item.sellPrice)} $trendSym",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = trendColor
                )
            }
        }
    }
}

@Composable
fun ComparisonScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("PİYASA KARŞILAŞTIRMA", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Aynı ürünlerin farklı kaynaklardaki canlı alış-satış farklarını inceleyebilirsiniz.", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Karşılaştırma modülü aktif kaynak verileriyle senkronize çalışır.", fontSize = 13.sp)
    }
}

@Composable
fun AboutSettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Ayarlar ve Hakkında", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Yasal Bilgilendirme", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Bu uygulama bağımsız bir fiyat takip uygulamasıdır. Kullanılan piyasa verileri ilgili veri kaynaklarından (Harem Altın, Altınkaynak, Odacı, Kapalıçarşı) alınmaktadır. Uygulama ilgili veri sağlayıcıların resmi mobil uygulaması değildir.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Sürüm: 1.0.0", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
