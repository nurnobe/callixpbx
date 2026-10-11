package com.callix.pbx.ui.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.callix.pbx.R
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.databinding.FragmentLocationBinding
import com.callix.pbx.service.LocationService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import timber.log.Timber

/**
 * A STATUS screen, not a control.
 *
 * The owner's requirement is that a member of staff cannot leave location sharing
 * off, so the On/Off switch that used to live here was removed together with the
 * whole "off duty" code path. What is left:
 *
 *  - a read-only status line that only ever reports ON
 *  - the one-time permission steps (foreground now, "all the time" separately)
 *  - escalation to system Settings when permission was denied outright
 *  - a manual "check my location" readout for troubleshooting
 *
 * The service itself is started unconditionally at login; this screen only
 * re-arms it, which is harmless.
 */
class LocationFragment : Fragment() {

    private var _binding: FragmentLocationBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferenceManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Step 1: FINE permission
    private val finePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Android 11+ background permission is a separate step.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestBackgroundPermission()
            } else {
                ensureLocationService()
            }
        } else {
            showSettingsRationale()
        }
    }

    // Step 2: BACKGROUND permission (Android 11+)
    private val backgroundPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Foreground-only capture still works: this is a
        // foregroundServiceType="location" service started while the app is
        // visible, which is sufficient on API 29+. A refusal here is not a
        // failure and changes nothing the user is allowed to switch off.
        ensureLocationService()
        if (!granted) {
            Timber.d("LocationFragment: background permission not granted, foreground-only capture")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getInstance(requireContext())
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        setupUI()
        updateTrackingStatus()

        // Opening this screen re-arms tracking and, if the permission was never
        // granted, explains why it is needed. There is no path that turns it off.
        if (hasFinePermission()) {
            ensureLocationService()
            maybeOfferBackground()
        } else {
            showExplainDialog { requestLocationPermissions() }
        }
    }

    private fun setupUI() {
        binding.btnAllowBackground.setOnClickListener { requestBackgroundPermission() }
        binding.btnShareLocation.setOnClickListener { showCurrentLocation() }
    }

    private fun hasFinePermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Show the escalation button only when it can actually improve anything. */
    private fun maybeOfferBackground() {
        binding.btnAllowBackground.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasBackgroundPermission())
                View.VISIBLE
            else
                View.GONE
    }

    // =========================================================
    // Permission Flow
    // =========================================================

    private fun requestLocationPermissions() {
        when {
            hasFinePermission() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestBackgroundPermission()
                } else {
                    ensureLocationService()
                }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                showExplainDialog { finePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            }
            else -> {
                finePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ensureLocationService()
            return
        }
        if (hasBackgroundPermission()) {
            ensureLocationService()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Background Location")
            .setMessage(
                "To report location while the app is in the background, open Settings and " +
                "choose \"Allow all the time\".\n\n" +
                "Until then, location is still reported while the app is in use."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                backgroundPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Later") { _, _ ->
                ensureLocationService()
            }
            .show()
    }

    private fun showExplainDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("Location Permission Needed")
            .setMessage(
                "Callix reports your location every five minutes while you are signed in, " +
                "so administrators can verify your latest work location.\n\n" +
                "This is required while you are signed in and cannot be turned off."
            )
            .setPositiveButton("Continue") { _, _ -> onConfirm() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showSettingsRationale() {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission Required")
            .setMessage("Please grant location permission in App Settings, then return here.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + requireContext().packageName)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================
    // Service Control — start only, never stop
    // =========================================================

    private fun ensureLocationService() {
        prefs.isOnDuty = true
        prefs.isLocationTracking = true
        val intent = Intent(requireContext(), LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        updateTrackingStatus()
        maybeOfferBackground()
        Timber.d("LocationFragment: location service re-armed")
    }

    // =========================================================
    // UI Helpers
    // =========================================================

    private fun updateTrackingStatus() {
        val granted = hasFinePermission()
        binding.tvLocationStatus.text = if (granted)
            "🟢 Location sharing is active"
        else
            "🔴 Location permission not granted — tap below to allow it"

        binding.tvLocationStatus.setTextColor(
            if (granted) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#F44336")
        )
    }

    private fun showCurrentLocation() {
        if (!hasFinePermission()) {
            binding.tvCurrentLocation.text = "Permission not granted"
            return
        }
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val accuracy = location.accuracy.toInt()
                    val qualityEmoji = when {
                        accuracy <= 20 -> "🎯"
                        accuracy <= 80 -> "📍"
                        else -> "⚠️"
                    }
                    // The gate that can discard a fix is 5 km, not 150 m: accuracy
                    // is a LABEL here, so a coarse fix is still reported.
                    val note = if (accuracy > 5000) "\n(Too weak to report — over 5 km)" else ""
                    binding.tvCurrentLocation.text =
                        qualityEmoji + " Lat: " + String.format("%.6f", location.latitude) + "\n" +
                        qualityEmoji + " Lon: " + String.format("%.6f", location.longitude) + "\n" +
                        "🎯 Accuracy: " + accuracy + "m" + note
                } else {
                    binding.tvCurrentLocation.text =
                        "Location not available yet — try again in a moment"
                }
            }
        } catch (e: SecurityException) {
            binding.tvCurrentLocation.text = "Permission not granted"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
