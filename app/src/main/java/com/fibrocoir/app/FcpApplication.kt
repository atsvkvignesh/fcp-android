package com.fibrocoir.app

import android.app.Application
import com.fibrocoir.app.core.Store
import com.fibrocoir.app.core.Uploads
import com.fibrocoir.app.core.google.GoogleAuth
import com.fibrocoir.app.core.google.Sheets
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** One of each shared service for the whole app. */
class FcpApplication : Application() {

    lateinit var http: OkHttpClient private set
    lateinit var auth: GoogleAuth private set
    lateinit var sheets: Sheets private set
    lateinit var store: Store private set
    lateinit var uploads: Uploads private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))   // keep the line to Google warm
            .build()
        auth = GoogleAuth(this, http)
        sheets = Sheets(auth, http)
        store = Store(File(filesDir, "saved"))
        uploads = Uploads(http)
        Notifications.ensureChannel(this)
    }

    companion object {
        lateinit var instance: FcpApplication private set
    }
}
