package com.aistudio.xcloudgodmode.ai.services

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val Context.dataStore by preferencesDataStore(name = "token_vault")

/**
 * XboxSessionProfile: Model for a saved Xbox account in the Multi-Account Vault.
 */
data class XboxSessionProfile(
    val xuid: String,
    val gamertag: String,
    val uhs: String,
    val gamerpic: String,
    val xCloudGsToken: String,
    val xHomeGsToken: String,
    val refreshToken: String,
    val expiresAtMs: Long // Timestamp of gsToken expiration (usually 4 hours after emission)
)

/**
 * TokenVaultManager: Handles storage of multiple Xbox accounts and Fast Switching.
 */
class TokenVaultManager(private val context: Context) {
    private val ACCOUNTS_KEY = stringPreferencesKey("saved_xbox_accounts")
    private val ACTIVE_XUID_KEY = stringPreferencesKey("active_xuid")
    private val gson = Gson()

    // 1. Get all saved accounts
    val savedAccounts: Flow<List<XboxSessionProfile>> = context.dataStore.data.map { prefs ->
        val json = prefs[ACCOUNTS_KEY] ?: "[]"
        val type = object : TypeToken<List<XboxSessionProfile>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    // 2. Get active profile (Fast Switching)
    val activeProfile: Flow<XboxSessionProfile?> = context.dataStore.data.map { prefs ->
        val activeXuid = prefs[ACTIVE_XUID_KEY] ?: return@map null
        val json = prefs[ACCOUNTS_KEY] ?: "[]"
        val type = object : TypeToken<List<XboxSessionProfile>>() {}.type
        val accounts: List<XboxSessionProfile> = gson.fromJson(json, type) ?: emptyList()
        accounts.find { it.xuid == activeXuid }
    }

    // 3. Save or Update a profile
    suspend fun saveOrUpdateProfile(profile: XboxSessionProfile) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<XboxSessionProfile>>() {}.type
            val accounts: MutableList<XboxSessionProfile> = gson.fromJson(json, type) ?: mutableListOf()

            val existingIndex = accounts.indexOfFirst { it.xuid == profile.xuid }
            if (existingIndex >= 0) {
                accounts[existingIndex] = profile
            } else {
                accounts.add(profile)
            }

            prefs[ACCOUNTS_KEY] = gson.toJson(accounts)
            prefs[ACTIVE_XUID_KEY] = profile.xuid // Set as active
        }
    }

    // 4. Switch active account
    suspend fun switchActiveAccount(xuid: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_XUID_KEY] = xuid
        }
    }

    // 5. Remove account
    suspend fun removeAccount(xuid: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[ACCOUNTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<XboxSessionProfile>>() {}.type
            val accounts: MutableList<XboxSessionProfile> = gson.fromJson(json, type) ?: mutableListOf()

            accounts.removeAll { it.xuid == xuid }
            prefs[ACCOUNTS_KEY] = gson.toJson(accounts)

            if (prefs[ACTIVE_XUID_KEY] == xuid) {
                prefs.remove(ACTIVE_XUID_KEY)
            }
        }
    }
}
