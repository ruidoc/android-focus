package com.example.adproject.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.util.FirewallManager

/**
 * 启动接收器，用于在设备启动完成后自动启动VPN服务
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 检查上次VPN是否处于活动状态
                if (FirewallManager.isVpnActive(context)) {
                    Log.d(TAG, "VPN was active before reboot, restarting...")
                    
                    // 延迟启动服务，确保系统完全启动
                    Thread {
                        try {
                            // 等待一段时间，确保系统完全启动
                            Thread.sleep(10000)
                            
                            // 启动VPN服务
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(Intent(context, FirewallVpnService::class.java))
                            } else {
                                context.startService(Intent(context, FirewallVpnService::class.java))
                            }
                            
                            Log.d(TAG, "VPN service started after boot")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start VPN service after boot", e)
                        }
                    }.start()
                } else {
                    Log.d(TAG, "VPN was not active before reboot, not starting")
                }
            }
        }
    }
} 