package com.clashzerovpn

import android.app.Application
import com.github.kr328.clash.common.Global

class CZApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Global.init(this)
    }
}
