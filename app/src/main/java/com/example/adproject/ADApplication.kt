package com.example.adproject

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.adproject.receiver.ServiceMonitorReceiver
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.util.FirewallManager
import com.example.adproject.widget.FirewallWidgetProvider

/**
 * 应用类，用于初始化应用级别的组件
 */
class ADApplication : Application() {
    private val TAG = "ADApplication"
    private val serviceMonitorReceiver = ServiceMonitorReceiver()
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
        
        // 注册动态广播接收器
        registerReceivers()
        
        // 检查VPN状态，如果需要则重启
        checkVpnStatus()
        
        // 初始化模拟的华为实况窗
        initMockWidget()
    }
    
    private fun registerReceivers() {
        try {
            // 注册屏幕状态变化接收器
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(serviceMonitorReceiver, screenFilter)
            
            // 注册时间变化接收器（每分钟）
            val timeFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
            }
            registerReceiver(serviceMonitorReceiver, timeFilter)
            
            // 注册电源状态变化接收器
            val powerFilter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(serviceMonitorReceiver, powerFilter)
            
            Log.d(TAG, "Broadcast receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers", e)
        }
    }
    
    private fun checkVpnStatus() {
        // 检查VPN是否应该处于活动状态
        if (FirewallManager.isVpnActive(this)) {
            Log.d(TAG, "VPN should be active, checking status")
            
            // 检查是否需要重启VPN
            if (FirewallManager.shouldRestartVpn(this)) {
                Log.d(TAG, "VPN needs restart, restarting...")
                
                // 延迟启动服务，确保应用完全初始化
                Thread {
                    try {
                        Thread.sleep(3000) // 等待3秒
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(Intent(this, FirewallVpnService::class.java))
                        } else {
                            startService(Intent(this, FirewallVpnService::class.java))
                        }
                        
                        Log.d(TAG, "VPN service started from Application")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start VPN service", e)
                    }
                }.start()
            } else {
                // 更新心跳
                FirewallManager.updateHeartbeat(this)
            }
        }
    }
    
    private fun initMockWidget() {
        // 初始化模拟的华为实况窗
        val widgetProvider = FirewallWidgetProvider.getInstance()
        widgetProvider.onWidgetCreate(this, 1) // 使用固定ID 1
        Log.d(TAG, "Mock widget initialized")
    }
    
    override fun onTerminate() {
        super.onTerminate()
        
        // 注销广播接收器
        try {
            unregisterReceiver(serviceMonitorReceiver)
            Log.d(TAG, "Broadcast receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receivers", e)
        }
        
        // 清理模拟的华为实况窗
        val widgetProvider = FirewallWidgetProvider.getInstance()
        widgetProvider.onWidgetDelete(this, 1)
        Log.d(TAG, "Mock widget cleaned up")
    }
} 