package it.lagunav.openlagunamaps.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentSettingsBinding
import it.lagunav.openlagunamaps.engine.NavigationLimits
import it.lagunav.openlagunamaps.engine.SpeedUnit
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
        loadSettings()
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