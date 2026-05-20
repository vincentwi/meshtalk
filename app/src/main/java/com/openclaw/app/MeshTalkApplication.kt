package com.openclaw.app

import android.app.Application
import android.util.Log
import com.ffalcon.mercury.android.sdk.MercurySDK

class MeshTalkApplication : Application() {

    companion object {
        private const val TAG = "MeshTalkApplication"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            MercurySDK.init(this)
            Log.i(TAG, "MercurySDK.init() completed")
        } catch (e: Exception) {
            Log.e(TAG, "MercurySDK.init() failed", e)
        }
    }
}
