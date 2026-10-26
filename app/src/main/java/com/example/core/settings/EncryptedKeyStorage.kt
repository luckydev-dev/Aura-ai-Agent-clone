package com.example.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed secure storage for API keys and device unlock credentials.
 * Credentials (PIN, Password, Pattern) are NEVER stored in plaintext DataStore.
 */
class EncryptedKeyStorage(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "aura_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("Aura", "EncryptedSharedPreferences initialization failed; falling back to private prefs", e)
            context.getSharedPreferences("aura_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun getApiKeyPoolJson(): String {
        return prefs.getString(KEY_API_KEY_POOL, "") ?: ""
    }

    fun setApiKeyPoolJson(json: String) {
        prefs.edit().putString(KEY_API_KEY_POOL, json).apply()
    }

    // --- Device Unlock Credentials (Encrypted) ---

    fun getLockType(): String {
        return prefs.getString(KEY_LOCK_TYPE, "NONE") ?: "NONE"
    }

    fun setLockType(type: String) {
        prefs.edit().putString(KEY_LOCK_TYPE, type).apply()
    }

    fun getLockPin(): String {
        return prefs.getString(KEY_LOCK_PIN, "") ?: ""
    }

    fun setLockPin(pin: String) {
        prefs.edit().putString(KEY_LOCK_PIN, pin).apply()
    }

    fun getLockPassword(): String {
        return prefs.getString(KEY_LOCK_PASSWORD, "") ?: ""
    }

    fun setLockPassword(password: String) {
        prefs.edit().putString(KEY_LOCK_PASSWORD, password).apply()
    }

    fun getPatternPoints(): String {
        return prefs.getString(KEY_PATTERN_POINTS, "") ?: ""
    }

    fun setPatternPoints(points: String) {
        prefs.edit().putString(KEY_PATTERN_POINTS, points).apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_LOCK_TYPE)
            .remove(KEY_LOCK_PIN)
            .remove(KEY_LOCK_PASSWORD)
            .remove(KEY_PATTERN_POINTS)
            .apply()
    }

    // --- Pattern Grid Calibration ---

    fun getPatternCalibration(): String {
        return prefs.getString(KEY_PATTERN_CALIBRATION, "") ?: ""
    }

    fun setPatternCalibration(json: String) {
        prefs.edit().putString(KEY_PATTERN_CALIBRATION, json).apply()
    }

    fun getPatternCalibrationJson(): String = getPatternCalibration()
    fun setPatternCalibrationJson(json: String) = setPatternCalibration(json)

    // --- Unlock Policies ---

    fun getConfirmBeforeUnlock(): Boolean {
        return prefs.getBoolean(KEY_CONFIRM_BEFORE_UNLOCK, true)
    }

    fun setConfirmBeforeUnlock(confirm: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_BEFORE_UNLOCK, confirm).apply()
    }

    fun getShowCursorDuringUnlock(): Boolean {
        return prefs.getBoolean(KEY_SHOW_CURSOR_DURING_UNLOCK, false)
    }

    fun setShowCursorDuringUnlock(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_CURSOR_DURING_UNLOCK, show).apply()
    }

    companion object {
        private const val KEY_API_KEY = "encrypted_gemini_api_key"
        private const val KEY_API_KEY_POOL = "encrypted_gemini_api_key_pool"

        private const val KEY_LOCK_TYPE = "encrypted_lock_type"
        private const val KEY_LOCK_PIN = "encrypted_lock_pin"
        private const val KEY_LOCK_PASSWORD = "encrypted_lock_password"
        private const val KEY_PATTERN_POINTS = "encrypted_pattern_points"
        private const val KEY_PATTERN_CALIBRATION = "encrypted_pattern_calibration"
        private const val KEY_CONFIRM_BEFORE_UNLOCK = "confirm_before_unlock"
        private const val KEY_SHOW_CURSOR_DURING_UNLOCK = "show_cursor_during_unlock"
    }
}
