package com.example.adproject.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adproject.MainActivity
import com.example.adproject.R
import com.example.adproject.util.FirewallManager
import com.example.adproject.util.VpnStatusTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

class FirewallVpnService : VpnService() {
    private val TAG = "FirewallVpnService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "firewall_channel"
    private val WAKELOCK_TAG = "FirewallVpnService:WakeLock"
    private val HEARTBEAT_INTERVAL = 20000L // 20秒
    private val NOTIFICATION_UPDATE_INTERVAL = 1000L // 1秒

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                Log.d(TAG, "Sending heartbeat")
                FirewallManager.updateHeartbeat(applicationContext)
                handler.postDelayed(this, HEARTBEAT_INTERVAL)
            }
        }
    }
    
    // 通知更新Runnable
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                // 更新通知以显示VPN运行时间
                updateNotification()
                handler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL)
            }
        }
    }
    
    // VPN状态监听器
    private val vpnStatusListener = object : VpnStatusTracker.VpnStatusListener {
        override fun onVpnStatusUpdated() {
            // 当VPN状态更新时，更新通知
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startHeartbeat()
        
        // 注册VPN状态监听器
        VpnStatusTracker.addListener(vpnStatusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.get()) {
            // 如果服务已经在运行，只更新心跳
            FirewallManager.updateHeartbeat(this)
            return START_REDELIVER_INTENT
        }

        // 确保服务在前台运行
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning.set(true)
        FirewallManager.setVpnActive(this, true)
        startHeartbeat()
        
        // 开始跟踪VPN状态
        VpnStatusTracker.startTracking(this)
        
        // 开始更新通知
        startNotificationUpdates()

        scope.launch {
            try {
                runVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN service error", e)
            } finally {
                isRunning.set(false)
                FirewallManager.setVpnActive(this@FirewallVpnService, false)
                stopHeartbeat()
                stopNotificationUpdates()
                
                // 停止跟踪VPN状态
                VpnStatusTracker.stopTracking(this@FirewallVpnService)
            }
        }

        // 返回START_REDELIVER_INTENT，这样系统会在服务被杀死后尝试重新启动它，并重新传递最后的Intent
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN service onDestroy called")
        stopVpn()
        job.cancel()
        stopHeartbeat()
        stopNotificationUpdates()
        releaseWakeLock()
        
        // 移除VPN状态监听器
        VpnStatusTracker.removeListener(vpnStatusListener)
        
        super.onDestroy()
        
        // 尝试重启服务
        restartService()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "VPN service onTaskRemoved called")
        // 当应用从最近任务列表中移除时，不关闭VPN服务
        // 只更新通知，表明应用已被关闭但服务仍在运行
        val notification = createNotification(appClosed = true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // 确保心跳仍在运行
        startHeartbeat()
        
        // 确保通知更新仍在运行
        startNotificationUpdates()
        
        // 尝试重启服务，确保它不会被系统杀死
        restartService()
        
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN service onRevoke called")
        stopVpn()
        super.onRevoke()
    }

    private fun stopVpn() {
        isRunning.set(false)
        FirewallManager.setVpnActive(this, false)
        stopHeartbeat()
        stopNotificationUpdates()
        vpnInterface?.close()
        vpnInterface = null
        
        // 停止跟踪VPN状态
        VpnStatusTracker.stopTracking(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            )
            wakeLock?.acquire(10*60*1000L /*10 minutes*/)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
            wakeLock = null
        }
    }
    
    private fun startHeartbeat() {
        Log.d(TAG, "Starting heartbeat")
        stopHeartbeat() // 先停止之前的心跳，避免重复
        handler.post(heartbeatRunnable)
    }
    
    private fun stopHeartbeat() {
        Log.d(TAG, "Stopping heartbeat")
        handler.removeCallbacks(heartbeatRunnable)
    }
    
    private fun startNotificationUpdates() {
        Log.d(TAG, "Starting notification updates")
        stopNotificationUpdates() // 先停止之前的更新，避免重复
        handler.post(notificationUpdateRunnable)
    }
    
    private fun stopNotificationUpdates() {
        Log.d(TAG, "Stopping notification updates")
        handler.removeCallbacks(notificationUpdateRunnable)
    }
    
    private fun updateNotification() {
        val notification = createNotification(appClosed = false)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun restartService() {
        if (isRunning.get()) {
            val restartIntent = Intent(applicationContext, FirewallVpnService::class.java)
            restartIntent.putExtra("restart", true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    private fun runVpn() {
        val blockedApps = FirewallManager.getBlockedApps(this)
        if (blockedApps.isEmpty()) {
            Log.i(TAG, "No apps to block, stopping VPN")
            stopSelf()
            return
        }

        // 获取所有已安装的应用
        val pm = packageManager
        val installedApps = pm.getInstalledPackages(0).map { it.packageName }
        
        // 配置VPN接口
        val builder = Builder()
            .setSession("Firewall VPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .setBlocking(true) // 设置为阻塞模式，提高稳定性

        // 添加所有未被拦截的应用到disallowed列表
        // 这样只有被拦截的应用会走VPN通道
        for (packageName in installedApps) {
            // 跳过自己的应用
            if (packageName == this.packageName) continue
            
            // 如果应用不在拦截列表中，则添加到disallowed列表
            if (!blockedApps.contains(packageName)) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Package not found: $packageName", e)
                }
            }
        }

        // 建立VPN连接
        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN connection")
            return
        }

        Log.i(TAG, "VPN established, blocking traffic for ${blockedApps.size} apps")

        // 处理VPN流量
        val buffer = ByteBuffer.allocate(32767)
        val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

        try {
            // 创建一个UDP通道用于处理流量
            val tunnel = DatagramChannel.open()
            // 连接到一个不存在的地址，确保所有流量都被丢弃
            tunnel.connect(InetSocketAddress("127.0.0.1", 8087))
            protect(tunnel.socket())

            while (isRunning.get()) {
                try {
                    // 读取VPN接口的数据
                    val length = inputStream.read(buffer.array())
                    if (length > 0) {
                        // 在这里可以分析和处理数据包
                        // 简单实现：丢弃所有数据包，实现完全阻断
                        buffer.limit(length)
                        
                        // 我们不转发任何数据，这样所有走VPN的应用都无法访问网络
                        // 如果需要允许某些流量通过，可以在这里处理
                        
                        // 记录日志
                        if (length > 20) { // IP头部至少20字节
                            val version = (buffer.get(0).toInt() and 0xF0) shr 4
                            if (version == 4) { // IPv4
                                val srcIp = "${buffer.get(12).toInt() and 0xFF}.${buffer.get(13).toInt() and 0xFF}.${buffer.get(14).toInt() and 0xFF}.${buffer.get(15).toInt() and 0xFF}"
                                val dstIp = "${buffer.get(16).toInt() and 0xFF}.${buffer.get(17).toInt() and 0xFF}.${buffer.get(18).toInt() and 0xFF}.${buffer.get(19).toInt() and 0xFF}"
                                Log.d(TAG, "Blocked IPv4 packet: $srcIp -> $dstIp, length=$length")
                            } else if (version == 6) { // IPv6
                                Log.d(TAG, "Blocked IPv6 packet, length=$length")
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from VPN interface", e)
                    if (!isRunning.get()) break
                    // 短暂休眠后继续
                    Thread.sleep(100)
                }
            }
            
            tunnel.close()
        } catch (e: IOException) {
            Log.e(TAG, "VPN tunnel error", e)
        } finally {
            try {
                inputStream.close()
                outputStream.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing streams", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Firewall Service"
            val descriptionText = "Firewall VPN Service"
            val importance = NotificationManager.IMPORTANCE_HIGH // 提高通知重要性
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(true) // 显示通知角标
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // 在锁屏上显示
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(appClosed: Boolean = false): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FirewallVpnService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 获取VPN运行时间
        val durationText = VpnStatusTracker.getFormattedDuration(this)
        
        val contentText = if (appClosed) {
            "应用已关闭，防火墙仍在后台运行 ($durationText)"
        } else {
            "正在拦截选定应用的网络访问 ($durationText)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("应用防火墙")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 设置为持续通知，用户无法滑动清除
            .setPriority(NotificationCompat.PRIORITY_MAX) // 设置最高优先级
            .setCategory(NotificationCompat.CATEGORY_SERVICE) // 设置为服务类别
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 在锁屏上显示
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止防火墙", stopPendingIntent)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }

        fun prepareVpn(context: Context): Intent? {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                return vpnIntent
            }
            return null
        }
        
        fun isRunning(context: Context): Boolean {
            return FirewallManager.isVpnActive(context)
        }
        
        // 检查VPN服务是否需要重启
        fun checkAndRestartIfNeeded(context: Context) {
            if (FirewallManager.shouldRestartVpn(context)) {
                Log.d("FirewallVpnService", "VPN service needs restart, restarting...")
                start(context)
            }
        }
        
        // 获取VPN运行时间
        fun getVpnDuration(context: Context): String {
            return VpnStatusTracker.getFormattedDuration(context)
        }
    }
} 