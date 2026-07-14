package it.lagunav.openlagunamaps

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import android.widget.Toast
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
import it.lagunav.openlagunamaps.engine.OfflinePackInstaller

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: Fragment? = null
    private var isMapReady = false
    private var lastBackPressMs = 0L

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
    }

    /**
     * Apre il drawer laterale. Chiamata dai fragment (es. MapFragment) 
     * che integrano il pulsante menu nella propria UI.
     */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Mantieni la splash screen finché la mappa non è pronta o finché non scatta un timeout.
        // Il timeout è più alto del solito 2s perché la prima volta include anche la copia del
        // pacchetto mappa offline precotto (~150MB) nello storage interno, prima che la mappa
        // possa essere creata (vedi OfflinePackInstaller).
        splashScreen.setKeepOnScreenCondition { !isMapReady }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isMapReady = true }, 8000L)

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

        // Sempre tema giorno (modalità notte rimossa): forzato prima di setContentView, così
        // il tema resta coerente indipendentemente dalla modalità scura di sistema.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nascondi Strumenti Dev per impostazione predefinita
        val devToolsItem = binding.navView.menu.findItem(R.id.nav_devtools)
        devToolsItem.isVisible = false

        // Listener per abilitare gli strumenti dev con un long click sul logo
        val headerView = binding.navView.getHeaderView(0)
        val logo = headerView.findViewById<android.widget.ImageView>(R.id.nav_header_logo)
        logo.setOnLongClickListener {
            devToolsItem.isVisible = !devToolsItem.isVisible
            val status = if (devToolsItem.isVisible) "abilitati" else "disabilitati"
            Toast.makeText(this, "Strumenti Dev $status", Toast.LENGTH_SHORT).show()
            true
        }

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

        // Schermata iniziale: Mappa (con GPS reale). La creazione va rimandata a dopo la copia
        // del pacchetto offline precotto (se non già presente): MapLibre apre/crea il proprio
        // database non appena la prima MapView viene istanziata, quindi la copia deve avvenire
        // prima, non dopo.
        OfflinePackInstaller.installIfNeeded(applicationContext) {
            showFragment(R.id.nav_map, "Mappa") { MapFragment() }
            binding.navView.setCheckedItem(R.id.nav_map)
        }

        // Gestione tasto back: prima chiude eventuali overlay aperti nella mappa (dettaglio
        // punto, salva punto, pianificazione percorso, luoghi salvati, ricerca), poi torna alla
        // Mappa se si è su un'altra voce di menu, e solo se già sulla Mappa "vuota" chiede
        // conferma (doppio tocco) prima di uscire dall'app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = binding.drawerLayout
                val mapFragment = currentFragment as? MapFragment

                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    mapFragment != null && mapFragment.handleBackPress() -> { /* overlay chiuso */ }
                    mapFragment == null -> {
                        showFragment(R.id.nav_map, "Mappa") { MapFragment() }
                        binding.navView.setCheckedItem(R.id.nav_map)
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressMs < 2000L) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        } else {
                            lastBackPressMs = now
                            Toast.makeText(this@MainActivity, "Premi di nuovo per uscire", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }
}
