package com.eduqode.app

import android.app.Application

class EduQodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
    }
}
