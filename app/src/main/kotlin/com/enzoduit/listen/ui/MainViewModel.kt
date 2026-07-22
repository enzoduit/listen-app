package com.enzoduit.listen.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel : ViewModel() {
    val bleStatus = MutableLiveData("Idle")
    val uploadCount = MutableLiveData(0)
    val lastUploadTime = MutableLiveData("Never")
    val logLines = MutableLiveData<List<String>>(emptyList())

    private val maxLogLines = 200
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun updateBleStatus(status: String) {
        bleStatus.postValue(status)
    }

    fun incrementUpload() {
        val count = (uploadCount.value ?: 0) + 1
        uploadCount.postValue(count)
        lastUploadTime.postValue(timeFmt.format(Date()))
    }

    fun setUploadCount(count: Int) {
        uploadCount.postValue(count)
        lastUploadTime.postValue(timeFmt.format(Date()))
    }

    fun appendLog(line: String) {
        val timestamp = timeFmt.format(Date())
        val entry = "[$timestamp] $line"
        val current = logLines.value?.toMutableList() ?: mutableListOf()
        current.add(0, entry) // newest at top
        if (current.size > maxLogLines) current.subList(maxLogLines, current.size).clear()
        logLines.postValue(current)
    }
}
