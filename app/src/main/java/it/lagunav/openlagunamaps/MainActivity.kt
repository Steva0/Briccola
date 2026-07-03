package it.lagunav.openlagunamaps

import android.content.Context
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import it.lagunav.openlagunamaps.databinding.ActivityMainBinding
import it.lagunav.openlagunamaps.ui.MapFragment
import it.lagunav.openlagunamaps.ui.WeatherFragment
import it.lagunav.openlagunamaps.ui.SettingsFragment
import android.content.Intent
import android.net.Uri
import it.lagunav.openlagunamaps.ui.AboutFragment
import it.lagunav.openlagunamaps.ui.DonateFragment
import it.lagunav.openlagunamaps.ui.DevToolsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: Fragment? = null
    private var isMapReady = false

    /**
     * Segnala che la mappa (o l'inizializzazione principale) è completata,
     * permettendo alla splash screen di sparire.
     */
    fun setReady() {
        isMapReady = true
    }

    /**
     * Cambia la voce di menu SENZA ricreare i fragment già visti (evita di reinflate la
     * MapView/lo stile MapLibre a ogni cambio, che è lento): il primo accesso li crea con
     * add(), i successivi li recuperano per tag e li mostrano di nuovo con show()/hide().
     * Il fragment che esce di scena resta vivo ma nascosto in background.
     */
    private fun showFragment(itemId: Int, title: CharSequence?, factory: () -> Fragment) {
        val tag = "menu_fragment_$itemId"
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        currentFragment?.let { if (it.tag != tag) transaction.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
            currentFragment = existing
        } else {
            val newFragment = factory()
            transaction.add(R.id.fragment_container, newFragment, tag)
            currentFragment = newFragment
        }
        transaction.commit()
        supportActionBar?.title = title
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Mantieni la splash screen finché la mappa non è pronta o finché non scatta un timeout (2s)
        splashScreen.setKeepOnScreenCondition { !isMapReady }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isMapReady = true }, 2000L)

        // Personalizzazione dell'uscita della splash screen per un effetto "premium"
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = splashScreenView.iconView

            // Animazione di "scatto verso l'alto" con dissolvenza
            val translationY = android.animation.ObjectAnimator.ofFloat(
                iconView,
                android.view.View.TRANSLATION_Y,
                0f,
                -iconView.height.toFloat() * 1.5f
            )
            
            val alpha = android.animation.ObjectAnimator.ofFloat(
                iconView,
                android.view.View.ALPHA,
                1f,
                0f
            )

            val exitSet = android.animation.AnimatorSet()
            exitSet.playTogether(translationY, alpha)
            exitSet.duration = 500L
            exitSet.interpolator = android.view.animation.AnticipateInterpolator()
            
            exitSet.doOnEnd { splashScreenView.remove() }
            exitSet.start()

            // Scomparsa fluida dello sfondo azzurro
            splashScreenView.view.animate()
                .alpha(0f)
                .setDuration(300L)
                .setStartDelay(100L)
                .start()
        }

        // Applica night mode PRIMA di setContentView, così tutto il tema è coerente
        val nightMode = getSharedPreferences("laguna_prefs", Context.MODE_PRIVATE)
            .getBoolean("night_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (nightMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            val factory: (() -> Fragment)? = when (menuItem.itemId) {
                R.id.nav_map -> ({ MapFragment() })
                R.id.nav_weather -> ({ WeatherFragment() })
                R.id.nav_settings -> ({ SettingsFragment() })
                R.id.nav_devtools -> ({ DevToolsFragment() })
                R.id.nav_about -> ({ AboutFragment() })
                R.id.nav_donate -> ({ DonateFragment() })
                else -> null
            }

            factory?.let { showFragment(menuItem.itemId, menuItem.title, it) }

            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Schermata iniziale: Mappa (con GPS reale)
        showFragment(R.id.nav_map, "Mappa") { MapFragment() }
        binding.navView.setCheckedItem(R.id.nav_map)

        // Gestione tasto back moderno
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }
}
