package com.briccola.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.briccola.app.R
import com.briccola.app.databinding.DialogLogbookDetailBinding
import com.briccola.app.engine.GpxParser
import com.briccola.app.engine.KeyboardUtils
import com.briccola.app.engine.SpeedUnit
import com.briccola.app.engine.Track
import com.briccola.app.engine.TrackRecorderEngine
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogbookDetailBottomSheet(
    private var track: Track,
    private val onShowOnMap: (Track) -> Unit,
    private val onTrackChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogLogbookDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLogbookDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etTrackName.setText(track.name)

        binding.btnCloseDialog.setOnClickListener {
            dismiss()
        }

        binding.etTrackName.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.nestedScrollLogbook.postDelayed({
                    binding.nestedScrollLogbook.smoothScrollTo(0, binding.btnSaveName.bottom)
                }, 250L)
            }
        }

        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        binding.tvDetailDate.text = "Data: ${dateFormat.format(Date(track.startTime))}"

        val context = requireContext()
        val speedUnit = SpeedUnit.get(context)

        val (distVal, distUnit) = when (speedUnit) {
            SpeedUnit.KMH -> Pair(track.distanceMeters / 1000f, "km")
            SpeedUnit.KNOTS -> Pair(track.distanceMeters / 1852f, "nm")
            SpeedUnit.MPH -> Pair(track.distanceMeters / 1609.344f, "mi")
        }
        binding.tvDetailDistance.text = String.format(Locale.getDefault(), "Distanza: %.2f %s", distVal, distUnit)

        val hours = track.durationSeconds / 3600
        val minutes = (track.durationSeconds % 3600) / 60
        binding.tvDetailDuration.text = "Durata: ${hours}h ${minutes}m"

        val (speedVal, speedUnitName) = when (speedUnit) {
            SpeedUnit.KMH -> Pair(track.avgSpeedKnots * 1.852f, "km/h")
            SpeedUnit.KNOTS -> Pair(track.avgSpeedKnots, "nodi")
            SpeedUnit.MPH -> Pair(track.avgSpeedKnots * 1.150779f, "mph")
        }
        binding.tvDetailSpeed.text = String.format(Locale.getDefault(), "Velocità media: %.1f %s", speedVal, speedUnitName)

        val saveNameAction = {
            val newName = binding.etTrackName.text.toString().trim()
            if (newName.isNotEmpty() && newName != track.name) {
                track.name = newName
                TrackRecorderEngine.renameTrack(requireContext(), track.id, newName)
                onTrackChanged()
            }
        }

        binding.btnSaveName.setOnClickListener {
            saveNameAction()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etTrackName.windowToken, 0)
            binding.etTrackName.clearFocus()

            binding.btnSaveName.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
                binding.btnSaveName.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()
        }

        binding.btnShowOnMap.setOnClickListener {
            saveNameAction()
            onShowOnMap(track)
            dismiss()
        }

        binding.btnExportGpx.setOnClickListener {
            saveNameAction()
            try {
                val gpxContent = GpxParser.exportToGpx(track)
                val sanitizedName = track.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val file = File(requireContext().cacheDir, "$sanitizedName.gpx")
                file.writeText(gpxContent)

                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Traccia GPX: ${track.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Condividi traccia GPX"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        binding.btnDeleteTrack.setOnClickListener {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_delete_confirmation, null)
            val dialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()

            dialogView.findViewById<View>(R.id.btn_cancel_delete)?.setOnClickListener {
                dialog.dismiss()
            }
            dialogView.findViewById<View>(R.id.btn_confirm_delete)?.setOnClickListener {
                TrackRecorderEngine.deleteTrack(requireContext(), track.id)
                onTrackChanged()
                dialog.dismiss()
                dismiss()
            }
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
        }

        KeyboardUtils.setupKeyboardDismissOnTouch(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
