package com.jarvis.jarvis.contacts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "nicknames")

object NicknameStore {

    suspend fun saveNickname(context: Context, nickname: String, phoneNumber: String) {
        val key = stringPreferencesKey(nickname.lowercase())
        context.dataStore.edit { preferences ->
            preferences[key] = phoneNumber
        }
    }

    suspend fun getNumberForNickname(context: Context, nickname: String): String? {
        val key = stringPreferencesKey(nickname.lowercase())
        val preferences = context.dataStore.data.first()
        return preferences[key]
    }
    
    suspend fun getAllNicknames(context: Context): Map<String, String> {
        val preferences = context.dataStore.data.first()
        return preferences.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }
    }
}
