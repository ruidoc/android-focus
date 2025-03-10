package com.example.adproject.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.util.FirewallManager

/**
 * 服务监控广播接收器
 * 用于监听系统事件，确保VPN服务持续运行
 */
class ServiceMonitorReceiver : BroadcastReceiver() {
    private val TAG = "ServiceMonitorReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            // 监听网络状态变化
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                checkAndRestartVpnIfNeeded(context)
            }
            
            // 监听电源连接状态变化
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                checkAndRestartVpnIfNeeded(context)
            }
            
            // 监听屏幕状态变化
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF -> {
                // 屏幕状态变化时更新心跳
                if (FirewallManager.isVpnActive(context)) {
                    FirewallManager.updateHeartbeat(context)
                }
            }
            
            // 监听应用包更新
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    Log.d(TAG, "Our app was updated, checking VPN status")
                    checkAndRestartVpnIfNeeded(context)
                }
            }
            
            // 监听时间变化（每分钟）
            Intent.ACTION_TIME_TICK -> {
                // 每分钟检查一次VPN状态
                checkAndRestartVpnIfNeeded(context)
            }
        }
    }
    
    private fun checkAndRestartVpnIfNeeded(context: Context) {
        if (FirewallManager.shouldRestartVpn(context)) {
            Log.d(TAG, "VPN service needs restart, restarting...")
            
            // 延迟启动服务，避免在系统状态变化时立即启动
            Thread {
                try {
                    Thread.sleep(2000) // 等待2秒
                    
                    if (FirewallManager.isVpnActive(context)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(Intent(context, FirewallVpnService::class.java))
                        } else {
                            context.startService(Intent(context, FirewallVpnService::class.java))
                        }
                        Log.d(TAG, "VPN service restarted")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart VPN service", e)
                }
            }.start()
        } else {
            // 如果VPN服务正在运行，更新心跳
            if (FirewallManager.isVpnActive(context)) {
                FirewallManager.updateHeartbeat(context)
            }
        }
    }
} 