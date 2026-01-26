package com.babysleepmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.babysleepmonitor.R
import com.babysleepmonitor.data.OnvifCamera
import com.babysleepmonitor.network.OnvifDiscoveryManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * Dialog for discovering ONVIF cameras on the local network.
 */
class CameraDiscoveryDialog : DialogFragment() {
    
    companion object {
        const val TAG = "CameraDiscoveryDialog"
        
        fun newInstance(): CameraDiscoveryDialog {
            return CameraDiscoveryDialog()
        }
    }
    
    private lateinit var discoveryManager: OnvifDiscoveryManager
    private val cameras = mutableListOf<OnvifCamera>()
    private lateinit var adapter: CameraAdapter
    
    // Views
    private lateinit var progressContainer: LinearLayout
    private lateinit var rvCameras: RecyclerView
    private lateinit var emptyContainer: LinearLayout
    private lateinit var errorContainer: LinearLayout
    private lateinit var tvError: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRetry: MaterialButton
    
    private var onCameraSelectedListener: ((OnvifCamera) -> Unit)? = null
    
    fun setOnCameraSelectedListener(listener: (OnvifCamera) -> Unit) {
        onCameraSelectedListener = listener
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use MaterialComponents theme instead of Material3 to fix UnsupportedOperationException
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_camera_discovery, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        discoveryManager = OnvifDiscoveryManager(requireContext())
        
        // Initialize views
        progressContainer = view.findViewById(R.id.progressContainer)
        rvCameras = view.findViewById(R.id.rvCameras)
        emptyContainer = view.findViewById(R.id.emptyContainer)
        errorContainer = view.findViewById(R.id.errorContainer)
        tvError = view.findViewById(R.id.tvError)
        tvProgress = view.findViewById(R.id.tvProgress)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnRetry = view.findViewById(R.id.btnRetry)
        
        // Setup RecyclerView
        adapter = CameraAdapter(cameras) { camera ->
            // Show authentication dialog for the selected camera
            showAuthDialog(camera)
        }
        rvCameras.layoutManager = LinearLayoutManager(context)
        rvCameras.adapter = adapter
        
        // Button listeners
        btnCancel.setOnClickListener { dismiss() }
        btnRetry.setOnClickListener { startDiscovery() }
        
        // Start discovery
        startDiscovery()
    }
    
    private fun showAuthDialog(camera: OnvifCamera) {
        val authDialog = CameraAuthDialog.newInstance(camera.hostname, camera.displayName, camera.xAddr)
        authDialog.setOnConnectedListener { connectedCamera ->
            // Auth was successful, pass the camera with stream URI to listener
            onCameraSelectedListener?.invoke(connectedCamera)
            dismiss()
        }
        authDialog.show(parentFragmentManager, CameraAuthDialog.TAG)
    }
    
    override fun onStart() {
        super.onStart()
        // Make dialog wider
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    private fun startDiscovery() {
        showProgress()
        cameras.clear()
        adapter.notifyDataSetChanged()
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                tvProgress.text = "Scanning for ONVIF cameras..."
                
                val foundCameras = discoveryManager.discoverCamerasWithDetails(
                    timeoutMs = 5000,
                    onCameraFound = { camera ->
                        // Update UI as cameras are found
                        activity?.runOnUiThread {
                            if (!cameras.any { it.hostname == camera.hostname }) {
                                cameras.add(camera)
                                adapter.notifyItemInserted(cameras.size - 1)
                                
                                if (cameras.isNotEmpty()) {
                                    showCameras()
                                }
                            }
                        }
                    }
                )
                
                // Final update
                if (foundCameras.isEmpty()) {
                    showEmpty()
                } else {
                    cameras.clear()
                    cameras.addAll(foundCameras)
                    adapter.notifyDataSetChanged()
                    showCameras()
                }
                
            } catch (e: Exception) {
                showError(e.message ?: "Discovery failed")
            }
        }
    }
    
    private fun showProgress() {
        progressContainer.visibility = View.VISIBLE
        rvCameras.visibility = View.GONE
        emptyContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        btnRetry.visibility = View.GONE
    }
    
    private fun showCameras() {
        progressContainer.visibility = View.GONE
        rvCameras.visibility = View.VISIBLE
        emptyContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        btnRetry.visibility = View.GONE
    }
    
    private fun showEmpty() {
        progressContainer.visibility = View.GONE
        rvCameras.visibility = View.GONE
        emptyContainer.visibility = View.VISIBLE
        errorContainer.visibility = View.GONE
        btnRetry.visibility = View.VISIBLE
    }
    
    private fun showError(message: String) {
        progressContainer.visibility = View.GONE
        rvCameras.visibility = View.GONE
        emptyContainer.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        tvError.text = message
        btnRetry.visibility = View.VISIBLE
    }
    
    /**
     * RecyclerView adapter for camera list
     */
    private class CameraAdapter(
        private val cameras: List<OnvifCamera>,
        private val onItemClick: (OnvifCamera) -> Unit
    ) : RecyclerView.Adapter<CameraAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvCameraName)
            val tvHost: TextView = view.findViewById(R.id.tvCameraHost)
            val tvModel: TextView = view.findViewById(R.id.tvCameraModel)
            val card: MaterialCardView = view as MaterialCardView
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_camera, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val camera = cameras[position]
            
            holder.tvName.text = camera.displayName
            holder.tvHost.text = camera.hostname
            
            if (!camera.model.isNullOrBlank() && camera.displayName != camera.model) {
                holder.tvModel.visibility = View.VISIBLE
                holder.tvModel.text = "Model: ${camera.model}"
            } else {
                holder.tvModel.visibility = View.GONE
            }
            
            holder.card.setOnClickListener {
                onItemClick(camera)
            }
        }
        
        override fun getItemCount() = cameras.size
    }
}
