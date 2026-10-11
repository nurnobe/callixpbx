package com.callix.pbx.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.callix.pbx.R
import com.callix.pbx.data.local.ExtensionDirectory
import com.callix.pbx.data.local.PreferenceManager
import com.callix.pbx.databinding.ActivityMainBinding
import com.callix.pbx.service.MissedCallNotifier
import com.callix.pbx.service.SipManager
import com.callix.pbx.ui.auth.LoginActivity
import com.callix.pbx.ui.calls.CallHistoryFragment
import com.callix.pbx.ui.contacts.ContactsFragment
import com.callix.pbx.ui.dashboard.DashboardFragment
import com.callix.pbx.ui.settings.SettingsFragment
import com.callix.pbx.util.KeepAlive
import com.callix.pbx.util.GithubUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    companion object {
        /** Extra naming a tab to open on launch. Used by the missed-call alert. */
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_CALLS = "calls"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var sipManager: SipManager

    /**
     * A number handed to the dialer by another screen (today: the call log).
     *
     * It is consumed by the navigation listener below, because the dialer is
     * reached by SELECTING ITS TAB -- writing the number straight into a fragment
     * would leave the tab strip highlighting the previous tab while the dialer is
     * on screen. Cleared on read so that a later manual tap on "Dialpad" opens an
     * empty pad rather than silently restoring an old number.
     */
    private var pendingDialNumber: String? = null

    /**
     * The notification permission is requested once, on first run of the main
     * screen. No state is kept from the result: [MissedCallNotifier] re-checks the
     * grant on every post, so a user who allows it later from Settings is picked
     * up without the app needing to be restarted.
     *
     * The full-screen-intent request follows the answer, so the user is never
     * shown two system dialogs at the same time.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.d("POST_NOTIFICATIONS granted=%s", granted)
        askForFullScreenIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getInstance(this)
        sipManager = SipManager.getInstance(this)

        // Check if logged in
        if (!prefs.isLoggedIn) {
            navigateToLogin()
            return
        }

        setupBottomNavigation()

        // Without this the system silently drops the missed-call alert on Android 13+.
        requestCriticalPermissionsIfNeeded()

        // Belt and braces: the app must be reachable even if the SIP service was
        // stopped (by a vendor battery manager, or by the task being swiped away on
        // a device that ignores stopWithTask). Idempotent -- starting an already
        // running service only delivers this same ACTION_START again.
        KeepAlive.startSipService(this)

        // Refresh the extension directory, so an incoming call from an extension can
        // be labelled with its owner's name even when the INVITE carries no display
        // name. This is the right moment for it: the main screen is already talking
        // to the server, and the ring itself must never wait for a network round
        // trip. Failure is logged inside and is not surfaced -- a stale name is not
        // something to interrupt the user about.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ExtensionDirectory.refresh(this@MainActivity)
            }
        }

        checkForGithubUpdate()

        if (savedInstanceState == null) {
            // A missed-call alert opens the call log; otherwise the app opens on the
            // Dialpad, which is the owner's requirement -- launching the app must
            // land ready to place a call, not on the status dashboard.
            if (!openTabFromIntent(intent)) {
                loadFragment(DialpadFragment())
                binding.bottomNav.selectedItemId = R.id.nav_dialpad
            }
        }
    }

    /** The activity is `singleTask`, so a second launch arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabFromIntent(intent)
    }

    /**
     * Honour a tab request carried on the intent, so that tapping the missed-call
     * notification lands on the call log instead of on whatever tab was last open.
     *
     * @return true when a tab was opened, so the caller knows not to load the default.
     */
    private fun openTabFromIntent(intent: Intent?): Boolean {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) != TAB_CALLS) return false

        // The user is now looking at the missed calls, so the alert has been served.
        MissedCallNotifier.markSeen(this)

        if (binding.bottomNav.selectedItemId == R.id.nav_calls) {
            // Already on that tab, so the listener will not fire -- refresh directly.
            loadFragment(CallHistoryFragment())
        } else {
            binding.bottomNav.selectedItemId = R.id.nav_calls   // listener loads it
        }
        return true
    }

    /**
     * Ask for the three grants this app cannot work without, one at a time and in
     * a deliberate order.
     *
     * The notification grant comes first because it is a real runtime dialog. The
     * other two are settings pages, so they are offered as explanations and only
     * opened if the user agrees -- an app that fires the user into a system
     * settings screen without saying why gets the permission refused.
     */
    private fun requestCriticalPermissionsIfNeeded() {
        if (!KeepAlive.canPostNotifications(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askForFullScreenIntent()
    }

    /**
     * An incoming call can only take the screen from the background if the system
     * lets it. Android 14 made that grant revocable, so it is checked rather than
     * assumed -- silently losing full-screen calls on a new device is exactly the
     * bug this prevents.
     */
    private fun askForFullScreenIntent() {
        if (KeepAlive.canUseFullScreenIntent(this)) {
            askForBatteryExemption()
            return
        }
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_full_screen_title)
            .setMessage(R.string.permission_full_screen_body)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                try {
                    startActivity(KeepAlive.fullScreenIntentSettingsIntent(this))
                } catch (t: Throwable) {
                    // Some releases have no dedicated page; the app's own details
                    // screen always exists.
                    Timber.w(t, "No full-screen-intent settings page")
                    openAppDetails()
                }
            }
            .setNegativeButton(R.string.permission_later, null)
            .setOnDismissListener { askForBatteryExemption() }
            .show()
    }

    /**
     * Battery-optimisation exemption. This is the difference between a phone that
     * rings all night and one that stops ringing around 11pm, so it is asked for
     * explicitly rather than left to be discovered.
     */
    private fun askForBatteryExemption() {
        if (KeepAlive.isIgnoringBatteryOptimizations(this)) return
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_battery_title)
            .setMessage(R.string.permission_battery_body)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                try {
                    startActivity(KeepAlive.batteryOptimizationSettingsIntent(this))
                } catch (t: Throwable) {
                    Timber.w(t, "No battery-optimisation dialog")
                    openAppDetails()
                }
            }
            .setNegativeButton(R.string.permission_later, null)
            .show()
    }

    private fun openAppDetails() {
        try {
            startActivity(KeepAlive.appDetailsIntent(this))
        } catch (t: Throwable) {
            Timber.w(t, "No app details page on this device")
        }
    }

    /** Check GitHub Releases quietly, then offer a human-readable update dialog. */
    private fun checkForGithubUpdate() {
        lifecycleScope.launch {
            val release = try {
                withContext(Dispatchers.IO) { GithubUpdateManager.latestUpdate() }
            } catch (t: Throwable) {
                Timber.d(t, "GitHub update check unavailable")
                null
            } ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Update available")
                .setMessage("Callix PBX ${release.versionName} is ready. Update now for the latest improvements.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Update") { _, _ -> downloadAndInstallUpdate(release) }
                .show()
        }
    }

    private fun downloadAndInstallUpdate(release: GithubUpdateManager.Release) {
        lifecycleScope.launch {
            val progress = AlertDialog.Builder(this@MainActivity)
                .setTitle("Preparing update")
                .setMessage("Downloading Callix PBX ${release.versionName}…")
                .setCancelable(false)
                .create()
            progress.show()
            try {
                val apk = withContext(Dispatchers.IO) { GithubUpdateManager.download(this@MainActivity, release) }
                progress.dismiss()
                if (!GithubUpdateManager.startInstall(this@MainActivity, apk)) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Allow installs from Callix once, then tap Update again.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                Timber.w(t, "Update download failed")
                progress.dismiss()
                android.widget.Toast.makeText(this@MainActivity, "Couldn’t download the update.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val prefill = pendingDialNumber
            pendingDialNumber = null
            val fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_dialpad -> DialpadFragment.newInstance(prefill)
                R.id.nav_calls -> {
                    // Opening the log is what "seeing" the missed calls means.
                    MissedCallNotifier.markSeen(this)
                    CallHistoryFragment()
                }
                R.id.nav_contacts -> ContactsFragment()
                else -> null
            }
            fragment?.let {
                loadFragment(it)
                true
            } ?: false
        }
    }

    /**
     * Open the dialer with [number] already typed in, so a number the user tapped
     * in the call log is ready to call. Selecting the tab is what actually swaps
     * the fragment, which keeps the bottom bar honest about where the user is.
     */
    fun openDialpadWith(number: String?) {
        if (number.isNullOrBlank()) {
            // Nothing to carry: behave exactly like tapping the Dialpad tab.
            if (binding.bottomNav.selectedItemId != R.id.nav_dialpad) {
                binding.bottomNav.selectedItemId = R.id.nav_dialpad
            } else {
                loadFragment(DialpadFragment.newInstance(null))
            }
            return
        }
        if (binding.bottomNav.selectedItemId != R.id.nav_dialpad) {
            pendingDialNumber = number
            binding.bottomNav.selectedItemId = R.id.nav_dialpad   // listener consumes it
        } else {
            // Already on the dialer: the listener will not fire, so load directly.
            loadFragment(DialpadFragment.newInstance(number))
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fade_in,
                R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun logout() {
        sipManager.unregister()
        try {
            stopService(Intent(this, com.callix.pbx.service.SipService::class.java))
            stopService(Intent(this, com.callix.pbx.service.LocationService::class.java))
        } catch (e: Exception) {
            // ignore
        }
        prefs.clearUserData()
        navigateToLogin()
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
