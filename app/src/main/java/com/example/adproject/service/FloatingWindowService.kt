package com.example.adproject.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.adproject.MainActivity
import com.example.adproject.R
import com.example.adproject.util.FirewallManager
import com.example.adproject.util.VpnStatusTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingWindowService : Service() {
    
    private val TAG = "FloatingWindowService"
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var durationTextView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    
    private var updateJob: Job? = null
    
    // 状态
    private var isVpnActive = false
    private var blockedAppsCount = 0
    private var vpnDuration = "00:00:00"
    
    // 通知相关
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "floating_window_channel"
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: 创建悬浮窗服务")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动为前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        try {
            // 创建悬浮窗参数
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }
            
            Log.d(TAG, "onCreate: 创建布局")
            
            // 创建一个圆形布局作为悬浮窗
            val container = FrameLayout(this)
            
            // 创建圆形背景
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(Color.parseColor("#4285F4"))
            shape.setStroke(dpToPx(2), Color.WHITE)
            
            // 设置背景
            container.background = shape
            
            // 创建文本视图显示VPN持续时间
            durationTextView = TextView(this).apply {
                text = vpnDuration
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }
            
            // 添加文本视图到容器
            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            container.addView(durationTextView, textParams)
            
            // 设置容器大小
            val size = dpToPx(60)
            container.layoutParams = FrameLayout.LayoutParams(size, size)
            
            // 设置触摸监听
            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX + (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(container, params)
                        true
                    }
                    else -> false
                }
            }
            
            // 设置长按监听
            container.setOnLongClickListener {
                Log.d(TAG, "onLongClick: 关闭悬浮窗")
                stopSelf()
                true
            }
            
            floatingView = container
            
            // 获取WindowManager
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            Log.d(TAG, "onCreate: 添加悬浮窗到屏幕")
            
            // 添加悬浮窗到屏幕
            windowManager?.addView(floatingView, params)
            
            // 开始更新状态
            startUpdatingStatus()
            
            // 更新一次状态
            updateStatus()
            
            Log.d(TAG, "onCreate: 悬浮窗创建完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating floating window", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "防火墙悬浮窗"
            val descriptionText = "显示防火墙状态的悬浮窗"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // 注册通知渠道
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("防火墙悬浮窗")
            .setContentText("防火墙正在运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun startUpdatingStatus() {
        Log.d(TAG, "startUpdatingStatus: 开始更新状态")
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateStatus()
                delay(1000) // 每秒更新一次
            }
        }
    }
    
    private fun updateStatus() {
        try {
            isVpnActive = FirewallManager.isVpnActive(this)
            blockedAppsCount = FirewallManager.getBlockedApps(this).size
            vpnDuration = VpnStatusTracker.getFormattedDuration(this)
            
            // 更新UI
            updateFloatingWindowUI()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status", e)
        }
    }
    
    private fun updateFloatingWindowUI() {
        try {
            // 更新文本
            durationTextView?.text = vpnDuration
            
            // 更新背景颜色
            val color = if (isVpnActive) Color.parseColor("#4285F4") else Color.parseColor("#757575")
            val background = floatingView?.background as? GradientDrawable
            background?.setColor(color)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: 销毁悬浮窗服务")
        updateJob?.cancel()
        try {
            if (floatingView != null && windowManager != null) {
                windowManager?.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating window", e)
        }
    }
} 