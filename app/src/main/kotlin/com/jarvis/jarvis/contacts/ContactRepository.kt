package com.jarvis.jarvis.contacts

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Contact(
    val id: String,
    val displayName: String,
    val firstName: String,
    val primaryNumber: String
)

object ContactRepository {

    private var cachedContacts = emptyList<Contact>()

    @SuppressLint("Range")
    suspend fun loadContacts(context: Context): List<Contact> = withContext(Dispatchers.IO) {
        if (cachedContacts.isNotEmpty()) {
            return@withContext cachedContacts
        }

        val contactsList = mutableListOf<Contact>()
        val contentResolver = context.contentResolver
        
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            val uniqueContacts = mutableSetOf<String>()

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                if (!uniqueContacts.contains(id)) {
                    val name = cursor.getString(nameIndex) ?: ""
                    val number = cursor.getString(numberIndex) ?: ""
                    val firstName = name.split(" ").firstOrNull() ?: name

                    if (name.isNotBlank() && number.isNotBlank()) {
                        contactsList.add(Contact(id, name, firstName, number))
                        uniqueContacts.add(id)
                    }
                }
            }
        }

        cachedContacts = contactsList
        return@withContext cachedContacts
    }

    suspend fun getAll(context: Context): List<Contact> {
        return if (cachedContacts.isEmpty()) loadContacts(context) else cachedContacts
    }
    
    fun clearCache() {
        cachedContacts = emptyList()
    }
}
