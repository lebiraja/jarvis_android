package com.jarvis.jarvis.contacts

import android.content.Context
import me.xdrop.fuzzywuzzy.FuzzySearch

object ContactMatcher {

    data class ResolvedContact(val name: String, val number: String, val score: Int)

    suspend fun find(query: String, context: Context): ResolvedContact? {
        val normalizedQuery = query.trim().lowercase()
        
        // 1. Check user-defined aliases first
        val nicknameNumber = NicknameStore.getNumberForNickname(context, normalizedQuery)
        if (nicknameNumber != null) {
            return ResolvedContact(normalizedQuery, nicknameNumber, 100)
        }

        // 2. Fetch full device contacts
        val contacts = ContactRepository.getAll(context)
        
        var bestScore = 0
        var bestContact: ResolvedContact? = null

        for (contact in contacts) {
            val candidates = listOf(
                contact.displayName.lowercase(),
                contact.firstName.lowercase()
            ).filter { it.isNotBlank() }

            val score = candidates.maxOf { candidate ->
                val ratio = FuzzySearch.ratio(normalizedQuery, candidate)
                val partial = FuzzySearch.partialRatio(normalizedQuery, candidate)
                maxOf(ratio, partial)
            }

            if (score > bestScore) {
                bestScore = score
                bestContact = ResolvedContact(contact.displayName, contact.primaryNumber, score)
            }
        }

        return if (bestScore >= 70 && bestContact != null) {
            bestContact
        } else {
            null
        }
    }
}
