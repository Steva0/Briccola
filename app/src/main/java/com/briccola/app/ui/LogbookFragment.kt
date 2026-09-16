package com.briccola.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.briccola.app.MainActivity
import com.briccola.app.databinding.FragmentLogbookBinding
import com.briccola.app.databinding.ItemTrackBinding
import com.briccola.app.engine.GpxParser
import com.briccola.app.engine.SpeedUnit
import com.briccola.app.engine.Track
import com.briccola.app.engine.TrackRecorderEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogbookFragment : Fragment() {

    private var _binding: FragmentLogbookBinding? = null
    private val binding get() = _binding!!

    private val trackAdapter = TrackAdapter(
        onItemClick = { track -> showTrackDetails(track) }
    )

    private val importGpxLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        val importedTrack = GpxParser.importFromGpx(inputStream)
                        if (importedTrack != null) {
                            TrackRecorderEngine.saveTrack(requireContext(), importedTrack)
                            loadTracks()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogbookBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTracks.adapter = trackAdapter

        binding.btnMenu.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        binding.btnImportGpx.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
            }
            importGpxLauncher.launch(intent)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            
            binding.root.setPadding(0, statusBarHeight, 0, 0)
            binding.scrollLogbook.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = navBarHeight
            }
            insets
        }

        loadTracks()
    }

    override fun onResume() {
        super.onResume()
        loadTracks()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadTracks()
        }
    }

    private fun loadTracks() {
        val tracks = TrackRecorderEngine.getAllTracks(requireContext())
        trackAdapter.submitList(ArrayList(tracks)) {
            trackAdapter.notifyDataSetChanged()
        }
        binding.tvEmptyLogbook.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showTrackDetails(track: Track) {
        val bottomSheet = LogbookDetailBottomSheet(
            track = track,
            onShowOnMap = { selectedTrack ->
                (activity as? MainActivity)?.showTrackOnMap(selectedTrack)
            },
            onTrackChanged = {
                loadTracks()
            }
        )
        bottomSheet.show(parentFragmentManager, "LogbookDetail")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TrackAdapter(
        private val onItemClick: (Track) -> Unit
    ) : ListAdapter<Track, TrackAdapter.ViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), onItemClick)
        }

        class ViewHolder(private val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root) {
            private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

            fun bind(track: Track, onClick: (Track) -> Unit) {
                binding.tvTrackName.text = track.name
                binding.tvTrackDate.text = dateFormat.format(Date(track.startTime))

                val context = binding.root.context
                val speedUnit = SpeedUnit.get(context)
                val (distVal, distUnit) = when (speedUnit) {
                    SpeedUnit.KMH -> Pair(track.distanceMeters / 1000f, "km")
                    SpeedUnit.KNOTS -> Pair(track.distanceMeters / 1852f, "nm")
                    SpeedUnit.MPH -> Pair(track.distanceMeters / 1609.344f, "mi")
                }

                val hours = track.durationSeconds / 3600
                val minutes = (track.durationSeconds % 3600) / 60
                binding.tvTrackSummary.text = String.format(Locale.getDefault(), "Distanza: %.2f %s - Durata: %dh %dm", distVal, distUnit, hours, minutes)

                binding.root.setOnClickListener { onClick(track) }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
        }
    }
}
