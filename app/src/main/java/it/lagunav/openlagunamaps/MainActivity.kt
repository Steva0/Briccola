package it.lagunav.openlagunamaps

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
            val fragment: Fragment? = when (menuItem.itemId) {
                R.id.nav_map -> MapFragment()
                R.id.nav_weather -> WeatherFragment()
                R.id.nav_settings -> SettingsFragment()
                R.id.nav_devtools -> DevToolsFragment()
                R.id.nav_about -> AboutFragment()
                R.id.nav_donate -> DonateFragment()
                else -> null
            }

            fragment?.let {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, it)
                    .commit()
                supportActionBar?.title = menuItem.title
            }

            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Carica il fragment di debug all'avvio (temporaneo per sviluppo)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, DevToolsFragment())
            .commit()
        binding.navView.setCheckedItem(R.id.nav_devtools)
        supportActionBar?.title = "Dev Tools"

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
