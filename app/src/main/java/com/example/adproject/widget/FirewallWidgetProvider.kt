package com.example.adproject.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import com.example.adproject.MainActivity
import com.example.adproject.R
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.util.FirewallManager
import com.example.adproject.util.VpnStatusTracker

/**
 * 模拟华为实况窗提供者
 * 由于无法直接访问华为HMS SDK，我们创建一个模拟实现
 */
class FirewallWidgetProvider : VpnStatusTracker.VpnStatusListener {
    private val TAG = "FirewallWidgetProvider"
    
    // 模拟的widget ID列表
    private val widgetIds = mutableListOf<Int>()
    private var context: Context? = null
    
    fun onWidgetCreate(context: Context, widgetId: Int) {
        Log.d(TAG, "onWidgetCreate: widgetId=$widgetId")
        
        this.context = context.applicationContext
        
        // 添加widget ID
        if (!widgetIds.contains(widgetId)) {
            widgetIds.add(widgetId)
        }
        
        // 注册VPN状态监听器
        VpnStatusTracker.addListener(this)
        
        // 创建并更新实况窗
        updateWidget(context, widgetId)
    }
    
    fun onWidgetDelete(context: Context, widgetId: Int) {
        Log.d(TAG, "onWidgetDelete: widgetId=$widgetId")
        
        // 移除widget ID
        widgetIds.remove(widgetId)
        
        // 如果没有widget，移除VPN状态监听器
        if (widgetIds.isEmpty()) {
            VpnStatusTracker.removeListener(this)
        }
    }
    
    fun onWidgetUpdate(context: Context, widgetId: Int) {
        Log.d(TAG, "onWidgetUpdate: widgetId=$widgetId")
        
        // 更新实况窗
        updateWidget(context, widgetId)
    }
    
    fun onWidgetClick(context: Context, widgetId: Int, clickId: String) {
        Log.d(TAG, "onWidgetClick: widgetId=$widgetId, clickId=$clickId")
        
        when (clickId) {
            "widget_container" -> {
                // 点击实况窗，打开应用
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
            }
            "btn_toggle" -> {
                // 点击开关按钮，切换VPN状态
                val isVpnActive = FirewallManager.isVpnActive(context)
                if (isVpnActive) {
                    FirewallVpnService.stop(context)
                } else {
                    val vpnIntent = FirewallVpnService.prepareVpn(context)
                    if (vpnIntent == null) {
                        FirewallVpnService.start(context)
                    } else {
                        // 需要VPN权限，打开应用
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        context.startActivity(intent)
                    }
                }
                
                // 更新实况窗
                updateWidget(context, widgetId)
            }
        }
    }
    
    override fun onVpnStatusUpdated() {
        // 当VPN状态更新时，更新所有实况窗
        context?.let { ctx ->
            for (widgetId in widgetIds) {
                updateWidget(ctx, widgetId)
            }
        }
    }
    
    private fun updateWidget(context: Context, widgetId: Int) {
        // 在实际应用中，这里会使用RemoteViews更新实况窗
        // 由于我们没有真正的华为实况窗SDK，这里只是模拟
        
        // 获取VPN状态
        val isVpnActive = FirewallManager.isVpnActive(context)
        val durationText = VpnStatusTracker.getFormattedDuration(context)
        val blockedApps = FirewallManager.getBlockedApps(context)
        
        // 记录日志，模拟更新实况窗
        Log.d(TAG, "Updating widget $widgetId:")
        Log.d(TAG, "  VPN状态: ${if (isVpnActive) "已启用" else "已禁用"}")
        Log.d(TAG, "  运行时间: $durationText")
        Log.d(TAG, "  已拦截应用数: ${blockedApps.size}")
    }
    
    companion object {
        // 单例实例
        private val INSTANCE = FirewallWidgetProvider()
        
        fun getInstance(): FirewallWidgetProvider {
            return INSTANCE
        }
    }
} 