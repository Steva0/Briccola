package com.briccola.app.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.briccola.app.R
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Gestisce il contatore d'uso dell'app e la presentazione del dialog "Valutaci" / In-App Review.
 */
object ReviewManager {

    private const val PREFS_NAME = "review_prefs"
    private const val KEY_LAUNCH_COUNT = "launch_count"
    private const val KEY_REVIEW_STATUS = "review_status"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_NEVER = "NEVER"
    const val STATUS_RATED = "RATED"

    private const val PROMPT_LAUNCH_THRESHOLD = 5

    /**
     * Incrementa il contatore degli avvii ad ogni apertura dell'app.
     * Restituisce true se la soglia è stata raggiunta e va mostrato il dialog.
     */
    fun incrementLaunchCount(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val status = prefs.getString(KEY_REVIEW_STATUS, STATUS_PENDING) ?: STATUS_PENDING
        if (status == STATUS_NEVER || status == STATUS_RATED) return false

        val count = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, count).apply()

        return count >= PROMPT_LAUNCH_THRESHOLD
    }

    /**
     * Imposta la scelta dell'utente:
     * - STATUS_RATED: Valutato ora -> mai più
     * - STATUS_NEVER: No grazie -> mai più
     * - STATUS_PENDING: Più tardi -> azzera il contatore a 0 e riproporrà dopo 5 avvii
     */
    fun recordDecision(context: Context, status: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (status == STATUS_PENDING) {
            prefs.edit().putInt(KEY_LAUNCH_COUNT, 0).apply()
        } else {
            prefs.edit().putString(KEY_REVIEW_STATUS, status).apply()
        }
    }

    /**
     * Mostra il dialog custom con le tre opzioni (Valuta ora, Più tardi, No grazie).
     */
    fun showReviewDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_review_prompt, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnRateNow = dialogView.findViewById<Button>(R.id.btn_rate_now)
        val btnLater = dialogView.findViewById<Button>(R.id.btn_rate_later)
        val btnNever = dialogView.findViewById<Button>(R.id.btn_rate_never)

        btnRateNow?.setOnClickListener {
            recordDecision(activity, STATUS_RATED)
            dialog.dismiss()
            openPlayStoreOrInAppReview(activity)
        }

        btnLater?.setOnClickListener {
            recordDecision(activity, STATUS_PENDING)
            dialog.dismiss()
        }

        btnNever?.setOnClickListener {
            recordDecision(activity, STATUS_NEVER)
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Tenta di avviare l'In-App Review API di Google Play; se in ambiente di test locale o se non disponibile,
     * apre direttamente la pagina dell'app sul Play Store.
     */
    fun openPlayStoreOrInAppReview(activity: Activity) {
        if (com.briccola.app.BuildConfig.DEBUG) {
            // In build di test/debug l'API In-App Review di Google è silenziata da Google Play Services.
            // Apriamo direttamente la scheda dello store per permettere di testare il flusso.
            openPlayStoreDirectly(activity)
            return
        }

        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Completato con successo o chiuso
                }
            } else {
                openPlayStoreDirectly(activity)
            }
        }
    }

    /**
     * Apre la pagina dell'app sul Google Play Store (market:// o browser).
     */
    fun openPlayStoreDirectly(context: Context) {
        val appPackageName = context.packageName
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
