package com.meshtalk.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meshtalk.app.MeshTalkActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED
            )) {
            Log.i("BootReceiver", "Boot completed — starting MeshTalk")
            val launchIntent = Intent(context, MeshTalkActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("boot_start", true)
            }
            context.startActivity(launchIntent)
        }
    }
}
