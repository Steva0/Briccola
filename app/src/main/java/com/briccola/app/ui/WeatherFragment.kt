package com.briccola.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.briccola.app.databinding.FragmentWeatherBinding
import com.briccola.app.engine.DailyWeather
import com.briccola.app.engine.TideEngine
import com.briccola.app.engine.WeatherData
import com.briccola.app.engine.WeatherEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup.MarginLayoutParams

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
        binding.btnWeatherRefresh.setOnClickListener {
            loadWeather()
            loadDailyForecast()
            // Ricarica la marea per il giorno selezionato
            val day = dailyList.getOrNull(selectedDayIndex)
            val dayStartMs = day?.let {
                try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(it.dateIso)?.time
                } catch (_: Exception) { null }
            }
            loadTide(dayStartMs)
        }
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

        binding.btnMenu.setOnClickListener {
            (activity as? com.briccola.app.MainActivity)?.openDrawer()
        }

        // Padding per Edge-to-Edge nella schermata Meteo
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            
            v.setPadding(0, statusBarHeight, 0, 0)
            
            // Aggiunge spazio in fondo alla ScrollView per non far coprire il tasto Aggiorna
            binding.layoutWeatherContent.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight
            }

            insets
        }
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
        val row1 = view?.findViewById<LinearLayout>(com.briccola.app.R.id.layout_day_selector_row1) ?: return
        val row2 = view?.findViewById<LinearLayout>(com.briccola.app.R.id.layout_day_selector_row2) ?: return
        row1.removeAllViews()
        row2.removeAllViews()
        dayChips.clear()

        binding.scrollDaySelector.post {
            val totalWidth = binding.scrollDaySelector.width
            val chipWidth = (totalWidth / 2.2).toInt() // Mostriamo poco più di due chip per far capire che si scorre

            dailyList.forEachIndexed { index, day ->
                val (icon, _) = WeatherEngine.describeWeatherCode(day.weatherCode)
                val chip = TextView(requireContext()).apply {
                    val lp = LinearLayout.LayoutParams(chipWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(8, 8, 8, 8)
                    layoutParams = lp
                    text = "%s %s".format(icon, dayLabel(index, day.dateIso))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(12, 16, 12, 16)
                    setBackgroundResource(com.briccola.app.R.drawable.bg_day_chip)
                    setOnClickListener { selectDay(index) }
                }
                dayChips += chip

                if (index % 2 == 0) row1.addView(chip) else row2.addView(chip)
            }
            updateChipHighlight()
        }
    }

    private fun selectDay(index: Int) {
        if (index !in dailyList.indices) return
        selectedDayIndex = index
        updateChipHighlight()
        scrollToSelectedChip()
        refreshUnifiedCard()

        // Ricarica la marea per il giorno selezionato
        val day = dailyList[index]
        val dayStartMs = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(day.dateIso)?.time
        } catch (_: Exception) { null }
        loadTide(dayStartMs)
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
            if (selected) {
                chip.setTextColor(android.graphics.Color.WHITE)
                // Usiamo una versione programmatica per mantenere i bordi arrotondati anche da selezionato
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(android.graphics.Color.parseColor("#006699"))
                }
                chip.background = shape
            } else {
                chip.setTextColor(android.graphics.Color.parseColor("#333333"))
                chip.setBackgroundResource(com.briccola.app.R.drawable.bg_day_chip)
            }
        }
    }

    /** Unica funzione che decide cosa mostrare nella card. */
    private fun refreshUnifiedCard() {
        val live = currentData
        val day = dailyList.getOrNull(selectedDayIndex) ?: return
        
        // Mostra sempre la card oraria per il giorno selezionato
        displayHourlyWeather(day.hourly)

        // Data per l'header della card (es. "Lunedì 24 agosto")
        val fullDateLabel = try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).parse(day.dateIso)
            SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(date!!).replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { day.dateIso }
        binding.tvDayHeader.text = fullDateLabel

        if (selectedDayIndex == 0 && live != null) {
            val (icon, desc) = WeatherEngine.describeWeatherCode(live.weatherCode)
            binding.tvDayCondIcon.text = icon
            binding.tvDayCondText.text = "%.0f °C — %s".format(live.tempC, desc)
            
            val windDir = WeatherEngine.windDirectionLabel(live.windDirectionDeg)
            binding.tvDayWind.text = "Vento: %.0f km/h %s".format(live.windSpeedKmh, windDir)
            binding.tvDayPrecip.text = "Precipitazioni: %.1f mm".format(live.precipitationMm)
            binding.tvDayWaves.text = live.waveHeightM?.let { "Onde (mare): %.1f m".format(it) }
                ?: "Onde (mare): non disponibili"
            return
        }

        val (icon, desc) = WeatherEngine.describeWeatherCode(day.weatherCode)
        binding.tvDayCondIcon.text = icon
        binding.tvDayCondText.text = "%.0f / %.0f °C — %s".format(day.tempMinC, day.tempMaxC, desc)

        val windDir = WeatherEngine.windDirectionLabel(day.windDirectionDeg)
        binding.tvDayWind.text = "Vento: max %.0f km/h %s".format(day.windSpeedMaxKmh, windDir)
        binding.tvDayPrecip.text = "Precipitazioni: %.1f mm".format(day.precipitationSumMm)
        binding.tvDayWaves.text = day.waveHeightMaxM?.let { "Onde (mare): max %.1f m".format(it) }
            ?: "Onde (mare): non disponibili"
    }

    private fun displayHourlyWeather(hourly: List<com.briccola.app.engine.HourlyWeather>) {
        val container = binding.layoutHourlyWeather
        container.removeAllViews()
        val now = System.currentTimeMillis()
        
        // Se è oggi, filtriamo le ore passate. Se è un giorno futuro, mostriamo tutto.
        val isTodaySelected = selectedDayIndex == 0
        val itemsToDisplay = if (isTodaySelected) {
            hourly.filter { it.timeMs > now - 3600_000L }
        } else {
            hourly
        }

        itemsToDisplay.forEach { item ->
            val time = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(item.timeMs))
            val (icon, _) = WeatherEngine.describeWeatherCode(item.weatherCode)
            
            val view = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(20, 10, 20, 10)
                
                addView(TextView(requireContext()).apply {
                    text = time
                    textSize = 10f
                    setTextColor(android.graphics.Color.GRAY)
                })
                addView(TextView(requireContext()).apply {
                    text = icon
                    textSize = 20f
                    setPadding(0, 2, 0, 2)
                })
                addView(TextView(requireContext()).apply {
                    text = "%.0f°".format(item.tempC)
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
            }
            container.addView(view)
        }
    }

    /** Marea del giorno selezionato (tempo reale per oggi, astronomica per gli altri). */
    private fun loadTide(dayStartMs: Long? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                TideEngine.fetch(requireContext().applicationContext, dayStartMs)
            }
            if (_binding == null || data == null) return@launch
            binding.tideChart.setData(data)

            // Il livello "ora" ha senso solo se stiamo guardando oggi
            val isToday = dayStartMs == null || isSameDay(dayStartMs, System.currentTimeMillis())
            if (isToday) {
                binding.tvTideNow.text = "Livello ora: %.2f m".format(data.nowM)
            } else {
                binding.tvTideNow.text = "Marea prevista (intera giornata)"
            }

            binding.tvTideSource.visibility = View.GONE
        }
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
               cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
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
