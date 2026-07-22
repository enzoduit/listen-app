package com.enzoduit.listen

import android.app.Application
import android.util.Log

class ListenApplication : Application() {

    companion object {
        private const val TAG = "ListenApp"
        private lateinit var _instance: ListenApplication
        val instance: ListenApplication get() = _instance
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this
        Log.i(TAG, "Listen app started")
    }
}
