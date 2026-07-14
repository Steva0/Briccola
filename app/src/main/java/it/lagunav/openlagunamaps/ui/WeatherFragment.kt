package it.lagunav.openlagunamaps.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import it.lagunav.openlagunamaps.databinding.FragmentWeatherBinding
import it.lagunav.openlagunamaps.engine.DailyWeather
import it.lagunav.openlagunamaps.engine.TideEngine
import it.lagunav.openlagunamaps.engine.WeatherData
import it.lagunav.openlagunamaps.engine.WeatherEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherFragment : Fragment() {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!

    private var currentData: WeatherData? = null
    private var dailyList: List<DailyWeather> = emptyList()
    private var selectedDayIndex = 0
    private val dayChips = mutableListOf<TextView>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnWeatherRefresh.setOnClickListener { loadWeather(); loadTide(); loadDailyForecast() }
        binding.btnWeatherRetry.setOnClickListener { loadWeather() }
        binding.btnDayPrev.setOnClickListener { selectDay(selectedDayIndex - 1) }
        binding.btnDayNext.setOnClickListener { selectDay(selectedDayIndex + 1) }
        binding.tideChart.onScrub = { timeMs, valueM ->
            val time = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(timeMs))
            binding.tvTideNow.text = "Livello alle %s: %.2f m".format(time, valueM)
        }
        loadWeather()
        loadTide()
        loadDailyForecast()
    }

    // =================================================================
    // PREVISIONI GIORNO PER GIORNO — selettore ("Oggi", "Domani", nomi giorno) + frecce,
    // card unica sotto (refreshUnifiedCard) che mostra SEMPRE una sola fonte alla volta:
    // il dato live "adesso" per Oggi, la previsione giornaliera per gli altri giorni — per non
    // mostrare due descrizioni diverse (adesso vs riepilogo del giorno) senza spiegarle.
    // =================================================================

    private fun loadDailyForecast() {
        viewLifecycleOwner.lifecycleScope.launch {
            val days = withContext(Dispatchers.IO) { WeatherEngine.fetchDaily() }
            if (_binding == null || days == null) return@launch
            dailyList = days
            buildDaySelectorChips()
            refreshUnifiedCard()
        }
    }

    private fun dayLabel(index: Int, dateIso: String): String = when (index) {
        0 -> "Oggi"
        1 -> "Domani"
        else -> try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(dateIso)
            SimpleDateFormat("EEE d", Locale.ITALY).format(date!!).replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { dateIso }
    }

    private fun buildDaySelectorChips() {
        val container = binding.layoutDaySelector
        container.removeAllViews()
        dayChips.clear()
        dailyList.forEachIndexed { index, day ->
            val chip = TextView(requireContext()).apply {
                text = dayLabel(index, day.dateIso)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(28, 16, 28, 16)
                setOnClickListener { selectDay(index) }
            }
            dayChips += chip
            container.addView(chip)
        }
        updateChipHighlight()
    }

    private fun selectDay(index: Int) {
        if (index !in dailyList.indices) return
        selectedDayIndex = index
        updateChipHighlight()
        scrollToSelectedChip()
        refreshUnifiedCard()
    }

    /** Fa scorrere il selettore in modo che il giorno selezionato sia sempre visibile (centrato)
     *  — altrimenti usando le frecce ai lati la selezione può finire fuori dallo schermo senza
     *  che si veda quale giorno è stato scelto. */
    private fun scrollToSelectedChip() {
        val chip = dayChips.getOrNull(selectedDayIndex) ?: return
        binding.scrollDaySelector.post {
            val scroll = binding.scrollDaySelector
            val targetX = chip.left - (scroll.width - chip.width) / 2
            scroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
        }
    }

    private fun updateChipHighlight() {
        dayChips.forEachIndexed { index, chip ->
            val selected = index == selectedDayIndex
            chip.setTextColor(if (selected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#333333"))
            chip.setBackgroundColor(if (selected) android.graphics.Color.parseColor("#1976D2") else android.graphics.Color.TRANSPARENT)
        }
    }

    /** Unica funzione che decide cosa mostrare nella card: per "Oggi", se il dato live è
     *  disponibile, mostra quello (è la condizione ORA, non il riepilogo di tutta la giornata —
     *  possono legittimamente differire, es. "adesso nuvoloso" ma "oggi in generale rovesci di
     *  pioggia" se piove in un'altra ora del giorno) con un sottotitolo per il min/max del giorno.
     *  Per gli altri giorni (o se il live non è disponibile) mostra la previsione giornaliera. */
    private fun refreshUnifiedCard() {
        val live = currentData
        if (selectedDayIndex == 0 && live != null) {
            val (icon, desc) = WeatherEngine.describeWeatherCode(live.weatherCode)
            binding.tvDayIcon.text = icon
            binding.tvDayDesc.text = desc
            binding.tvDayTemp.text = "%.0f °C".format(live.tempC)
            val today = dailyList.getOrNull(0)
            binding.tvDaySubtitle.text = if (today != null)
                "Adesso — min %.0f° / max %.0f° oggi".format(today.tempMinC, today.tempMaxC)
            else "Adesso"

            val windDir = WeatherEngine.windDirectionLabel(live.windDirectionDeg)
            binding.tvDayWind.text = "Vento: %.0f km/h %s".format(live.windSpeedKmh, windDir)
            binding.tvDayPrecip.text = "Precipitazioni: %.1f mm".format(live.precipitationMm)
            binding.tvDayWaves.text = live.waveHeightM?.let { "Onde (mare): %.1f m".format(it) }
                ?: "Onde (mare): non disponibili"
            return
        }

        val day = dailyList.getOrNull(selectedDayIndex) ?: return
        val (icon, desc) = WeatherEngine.describeWeatherCode(day.weatherCode)
        binding.tvDayIcon.text = icon
        binding.tvDayDesc.text = desc
        binding.tvDayTemp.text = "%.0f / %.0f °C".format(day.tempMinC, day.tempMaxC)
        binding.tvDaySubtitle.text = "Previsione del giorno"

        val windDir = WeatherEngine.windDirectionLabel(day.windDirectionDeg)
        binding.tvDayWind.text = "Vento: max %.0f km/h %s".format(day.windSpeedMaxKmh, windDir)
        binding.tvDayPrecip.text = "Precipitazioni: %.1f mm".format(day.precipitationSumMm)
        binding.tvDayWaves.text = day.waveHeightMaxM?.let { "Onde (mare): max %.1f m".format(it) }
            ?: "Onde (mare): non disponibili"
    }

    /** Solo marea astronomica precalcolata (offline): la precisione della fonte online non
     *  serve per l'uso che se ne fa qui, ed è più semplice avere una sola fonte sempre coerente
     *  e disponibile anche senza connessione. */
    private fun loadTide() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                TideEngine.fetchOffline(requireContext().applicationContext)
            }
            if (_binding == null || data == null) return@launch
            binding.tideChart.setData(data)
            binding.tvTideNow.text = "Livello ora: %.2f m".format(data.nowM)
        }
    }

    /** true se il dispositivo ha una connessione con accesso a internet in questo momento
     *  (non garantisce che il sito specifico sia raggiungibile, solo che la rete c'è). */
    private fun hasInternetConnection(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadWeather() {
        if (!hasInternetConnection()) {
            showError("Nessuna connessione a internet")
            return
        }
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { WeatherEngine.fetch() }
            if (_binding == null) return@launch
            if (data == null) showError("Impossibile scaricare il meteo") else showContent(data)
        }
    }

    private fun showLoading() {
        binding.layoutWeatherContent.visibility = View.GONE
        binding.layoutWeatherError.visibility = View.GONE
        binding.layoutWeatherLoading.visibility = View.VISIBLE
    }

    private fun showError(title: String) {
        binding.layoutWeatherContent.visibility = View.GONE
        binding.layoutWeatherLoading.visibility = View.GONE
        binding.layoutWeatherError.visibility = View.VISIBLE
        binding.tvWeatherErrorTitle.text = title
    }

    private fun showContent(data: WeatherData) {
        binding.layoutWeatherLoading.visibility = View.GONE
        binding.layoutWeatherError.visibility = View.GONE
        binding.layoutWeatherContent.visibility = View.VISIBLE

        currentData = data
        refreshUnifiedCard()

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt))
        binding.tvWeatherUpdated.text = "Aggiornato alle $time"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
