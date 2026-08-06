package com.briccola.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.briccola.app.R
import com.briccola.app.databinding.FragmentSettingsBinding
import com.briccola.app.engine.LocalAssetInstaller
import com.briccola.app.engine.LocalTileServer
import com.briccola.app.engine.MapDownloader
import com.briccola.app.engine.NavigationLimits
import com.briccola.app.engine.SpeedUnit
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDraftSlider()
        setupSpinners()
        setupSpeedUnit()
        setupMapManagement()
        loadSettings()

        binding.btnMenu.setOnClickListener {
            (activity as? com.briccola.app.MainActivity)?.openDrawer()
        }

        // Padding per Edge-to-Edge nelle impostazioni
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupMapManagement() {
        updateMapStatusUi()

        binding.btnMapAction.setOnClickListener {
            if (!MapDownloader.areMapsInstalled(requireContext())) {
                startMapDownload()
            }
        }

        binding.btnMapDelete.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Elimina Mappa")
                .setMessage("Sei sicuro di voler eliminare i dati della mappa offline? Dovrai internet per vedere lo sfondo.")
                .setPositiveButton("Elimina") { _, _ ->
                    deleteMapFiles()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun updateMapStatusUi() {
        val installed = MapDownloader.areMapsInstalled(requireContext())
        if (installed) {
            binding.tvMapStatusLabel.text = "Stato: Installata"
            binding.tvMapDescription.text = "Sfondo ad alta risoluzione installato (165 MB)."
            binding.btnMapAction.visibility = View.GONE
            binding.btnMapDelete.visibility = View.VISIBLE
            binding.layoutMapDownloading.visibility = View.GONE
        } else {
            binding.tvMapStatusLabel.text = "Stato: Non installata"
            binding.tvMapDescription.text = "Scarica lo sfondo stradale e orografico per l'uso senza internet."
            binding.btnMapAction.visibility = View.VISIBLE
            binding.btnMapAction.text = "Scarica"
            binding.btnMapDelete.visibility = View.GONE
            binding.layoutMapDownloading.visibility = View.GONE
        }
    }

    private fun startMapDownload() {
        binding.btnMapAction.isEnabled = false
        binding.layoutMapDownloading.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            MapDownloader.downloadMaps(requireContext().applicationContext).collect { state ->
                when (state) {
                    is MapDownloader.DownloadState.Preparing -> {
                        binding.btnMapAction.visibility = View.GONE
                        binding.tvMapStatusLabel.text = "Preparazione in corso..."
                    }
                    is MapDownloader.DownloadState.Progress -> {
                        binding.btnMapAction.visibility = View.GONE
                        binding.layoutMapDownloading.visibility = View.VISIBLE
                        binding.progressMapDownload.progress = state.percentage
                        binding.tvMapDownloadPercent.text = "${state.percentage}%"
                        
                        val etaStr = if (state.percentage > 3 && state.etaSeconds > 0) {
                            val mins = state.etaSeconds / 60
                            val secs = state.etaSeconds % 60
                            " (circa %02d:%02d)".format(mins, secs)
                        } else ""
                        
                        binding.tvMapStatusLabel.text = "Scaricati %.1f MB di %.1f MB%s".format(
                            state.mbDownloaded, state.mbTotal, etaStr
                        )
                    }
                    is MapDownloader.DownloadState.Completed -> {
                        LocalTileServer.resetCache()
                        updateMapStatusUi()
                        binding.btnMapAction.isEnabled = true
                    }
                    is MapDownloader.DownloadState.Error -> {
                        binding.btnMapAction.isEnabled = true
                        binding.btnMapAction.visibility = View.VISIBLE
                        updateMapStatusUi()
                        binding.tvMapStatusLabel.text = "Errore: ${state.message}"
                    }
                }
            }
        }
    }

    private fun deleteMapFiles() {
        LocalAssetInstaller.DB_ASSETS.forEach { (name, _) ->
            val file = File(requireContext().filesDir, name)
            if (file.exists()) file.delete()
        }
        val versionFile = File(requireContext().filesDir, "tiles_version.txt")
        if (versionFile.exists()) versionFile.delete()
        
        LocalTileServer.resetCache()
        updateMapStatusUi()
    }

    private fun setupSpeedUnit() {
        val current = SpeedUnit.get(requireContext())
        binding.radioSpeedUnit.check(
            when (current) {
                SpeedUnit.KMH -> R.id.radio_kmh
                SpeedUnit.KNOTS -> R.id.radio_knots
                SpeedUnit.MPH -> R.id.radio_mph
            }
        )
        binding.radioSpeedUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = when (checkedId) {
                R.id.radio_knots -> SpeedUnit.KNOTS
                R.id.radio_mph -> SpeedUnit.MPH
                else -> SpeedUnit.KMH
            }
            SpeedUnit.set(requireContext(), unit)
        }
    }

    private fun setupDraftSlider() {
        binding.sliderDraft.addOnChangeListener { _, value, _ ->
            binding.tvDraftValue.text = String.format(Locale.getDefault(), "%.1f m", value)
            saveSetting("boat_draft", value)
        }
    }

    private fun setupSpinners() {
        // License Spinner
        val licenses = listOf(
            getString(R.string.license_none),
            getString(R.string.license_12),
            getString(R.string.license_unlimited)
        )
        val licenseAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, licenses)
        binding.spinnerLicense.adapter = licenseAdapter

        // Equipment Spinner
        val equipment = listOf(
            getString(R.string.equip_1),
            getString(R.string.equip_3),
            getString(R.string.equip_6),
            getString(R.string.equip_12),
            getString(R.string.equip_50),
            getString(R.string.equip_unlimited)
        )
        val equipAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, equipment)
        binding.spinnerEquipment.adapter = equipAdapter

        binding.spinnerLicense.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                NavigationLimits.setLicenseIndex(requireContext(), position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerEquipment.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                NavigationLimits.setEquipmentIndex(requireContext(), position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("laguna_prefs", Context.MODE_PRIVATE)

        val draft = prefs.getFloat("boat_draft", 0.5f)
        binding.sliderDraft.value = draft
        binding.tvDraftValue.text = String.format(Locale.getDefault(), "%.1f m", draft)

        binding.spinnerLicense.setSelection(NavigationLimits.getLicenseIndex(requireContext()))
        binding.spinnerEquipment.setSelection(NavigationLimits.getEquipmentIndex(requireContext()))
    }

    private fun saveSetting(key: String, value: Any) {
        val prefs = requireContext().getSharedPreferences("laguna_prefs", Context.MODE_PRIVATE)
        with(prefs.edit()) {
            when (value) {
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
            }
            apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}