package com.jarvis.jarvis.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

object CallAction {
    fun execute(context: Context, number: String, isSpeaker: Boolean) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CallAction", "CALL_PHONE permission not granted")
            return
        }

        try {
            val uri = Uri.parse("tel:$number")
            val extras = Bundle()
            if (isSpeaker) {
                extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            telecomManager.placeCall(uri, extras)
            Log.i("CallAction", "Placed call to $number with speaker=$isSpeaker")
        } catch (e: SecurityException) {
            Log.e("CallAction", "Failed to place call", e)
        }
    }
}
