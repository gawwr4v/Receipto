package com.example.receipto

import android.app.Application
import android.util.Log

class OpenCVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Load OpenCV native library as early as possible
        try {
            System.loadLibrary("opencv_java4")
            Log.d("OpenCVApplication", " OpenCV native library loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OpenCVApplication", " FAILED to load OpenCV native library", e)
            e.printStackTrace()
        }
    }
}