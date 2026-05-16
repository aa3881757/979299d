package com.xiaoxun.redpacket

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class XiaoxunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenCV 4.9+ 的本地初始化（無需網路下載 manager）
        if (!OpenCVLoader.initLocal()) {
            Log.e("XiaoxunApp", "OpenCV init failed")
        } else {
            Log.i("XiaoxunApp", "OpenCV ${OpenCVLoader.OPENCV_VERSION} ready")
        }
    }
}
