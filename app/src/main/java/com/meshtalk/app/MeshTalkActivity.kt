package com.meshtalk.app

import android.os.Bundle
import com.ffalcon.mercury.android.sdk.view.BaseMirrorActivity
import com.meshtalk.app.databinding.ActivityMeshtalkBinding

class MeshTalkActivity : BaseMirrorActivity<ActivityMeshtalkBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBindingPair.updateView {
            tvStatus.text = "MeshTalk Ready"
        }
    }
}
