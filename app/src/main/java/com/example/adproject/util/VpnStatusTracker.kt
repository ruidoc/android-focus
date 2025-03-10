package com.example.adproject.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * VPN状态追踪器，用于跟踪VPN开启时间和持续时间
 */
object VpnStatusTracker {
    private const val TAG = "VpnStatusTracker"
    private const val PREFS_NAME = "vpn_status_prefs"
    private const val KEY_START_TIME = "vpn_start_time"
    private const val KEY_LAST_UPDATE_TIME = "vpn_last_update_time"
    
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<VpnStatusListener>()
    private var isTracking = false
    
    // 更新间隔（毫秒）
    private const val UPDATE_INTERVAL = 1000L // 1秒
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            notifyListeners()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }
    
    /**
     * 获取SharedPreferences
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 开始跟踪VPN状态
     */
    fun startTracking(context: Context) {
        if (!isTracking) {
            val currentTime = System.currentTimeMillis()
            val prefs = getPrefs(context)
            
            // 如果没有开始时间，设置当前时间为开始时间
            if (prefs.getLong(KEY_START_TIME, 0) == 0L) {
                prefs.edit {
                    putLong(KEY_START_TIME, currentTime)
                }
            }
            
            // 更新最后更新时间
            prefs.edit {
                putLong(KEY_LAST_UPDATE_TIME, currentTime)
            }
            
            // 开始定期更新
            handler.post(updateRunnable)
            isTracking = true
            
            Log.d(TAG, "Started tracking VPN status")
        }
    }
    
    /**
     * 停止跟踪VPN状态
     */
    fun stopTracking(context: Context) {
        if (isTracking) {
            handler.removeCallbacks(updateRunnable)
            
            // 清除开始时间
            getPrefs(context).edit {
                putLong(KEY_START_TIME, 0)
                putLong(KEY_LAST_UPDATE_TIME, 0)
            }
            
            isTracking = false
            notifyListeners()
            
            Log.d(TAG, "Stopped tracking VPN status")
        }
    }
    
    /**
     * 重置跟踪状态
     */
    fun resetTracking(context: Context) {
        val currentTime = System.currentTimeMillis()
        getPrefs(context).edit {
            putLong(KEY_START_TIME, currentTime)
            putLong(KEY_LAST_UPDATE_TIME, currentTime)
        }
        
        Log.d(TAG, "Reset tracking VPN status")
        notifyListeners()
    }
    
    /**
     * 获取VPN开始时间
     */
    fun getStartTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_START_TIME, 0)
    }
    
    /**
     * 获取VPN持续时间（毫秒）
     */
    fun getDuration(context: Context): Long {
        val startTime = getStartTime(context)
        if (startTime == 0L) {
            return 0
        }
        
        return System.currentTimeMillis() - startTime
    }
    
    /**
     * 获取格式化的持续时间字符串
     */
    fun getFormattedDuration(context: Context): String {
        val durationMillis = getDuration(context)
        if (durationMillis == 0L) {
            return "00:00:00"
        }
        
        val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * 添加状态监听器
     */
    fun addListener(listener: VpnStatusListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * 移除状态监听器
     */
    fun removeListener(listener: VpnStatusListener) {
        listeners.remove(listener)
    }
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onVpnStatusUpdated()
        }
    }
    
    /**
     * VPN状态监听器接口
     */
    interface VpnStatusListener {
        fun onVpnStatusUpdated()
    }
} 