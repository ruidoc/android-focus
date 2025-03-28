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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import android.system.OsConstants

class FirewallVpnService : VpnService() {
    private val TAG = "FirewallVpnService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "firewall_channel"
    private val WAKELOCK_TAG = "FirewallVpnService:WakeLock"
    private val HEARTBEAT_INTERVAL = 20000L // 20秒
    private val NOTIFICATION_UPDATE_INTERVAL = 1000L // 1秒
    private val PROTOCOL_TCP = 6
    private val PROTOCOL_UDP = 17

    // 连接跟踪数据结构定义
    data class ConnectionKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int
    )
    
    // 连接状态，用于跟踪流量特征
    data class ConnectionState(
        val packageName: String?,
        val startTime: Long = System.currentTimeMillis(),
        var lastPacketTime: Long = System.currentTimeMillis(),
        var packetCount: Int = 0,
        var totalBytes: Long = 0,
        var largePacketCount: Int = 0, // 大于1400字节的包数量
        var consecutiveLargePackets: Int = 0, // 连续的大包数量
        var maxConsecutiveLargePackets: Int = 0, // 最大连续大包数量
        var isVideoTraffic: Boolean = false, // 是否判定为视频流量
        var bytesLastSecond: Long = 0, // 最近一秒的流量
        var lastBandwidthCheckTime: Long = System.currentTimeMillis(), // 上次带宽检查时间
        var bandwidthSamples: MutableList<Long> = mutableListOf(), // 带宽样本(bytes/second)
        var videoFeatureScore: Int = 0 // 视频特征得分
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager }
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
        
        // 立即启动前台服务，避免ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, createNotification())
        
        acquireWakeLock()
        startHeartbeat()
        
        // 注册VPN状态监听器
        VpnStatusTracker.addListener(vpnStatusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: ${intent?.action}")
        
        if (intent?.action == "STOP") {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.get()) {
            // 如果服务已经在运行，只更新心跳
            Log.d(TAG, "Service already running, updating heartbeat")
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

        Log.d(TAG, "Starting VPN service coroutine")
        scope.launch {
            try {
                runVpn()
            } catch (e: Exception) {
                Log.e(TAG, "VPN service error", e)
            } finally {
                Log.d(TAG, "VPN service coroutine finished")
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
        Log.d(TAG, "runVpn method started")
        val blockedApps = FirewallManager.getBlockedApps(this)
        val isBlockVideo = FirewallManager.isBlockVideo(this)
        
        Log.d(TAG, "Blocked apps: ${blockedApps.size}, block video: $isBlockVideo")
        
        if (blockedApps.isEmpty() && !isBlockVideo) {
            Log.i(TAG, "No apps to block and video blocking disabled, stopping VPN")
            stopSelf()
            return
        }

        // 获取所有已安装的应用
        val pm = packageManager
        val installedApps = pm.getInstalledPackages(0).map { it.packageName }
        
        // 配置VPN接口
        val builder = Builder()
            .setSession("Firewall VPN")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("114.114.114.114") // 添加国内DNS服务器
            .setMtu(1500)
            .setBlocking(true) // 设置为阻塞模式，提高稳定性

        // 如果只是屏蔽视频流量而不屏蔽特定应用，则所有应用都需要走VPN
        if (blockedApps.isEmpty() && isBlockVideo) {
            // 排除自己的应用
            try {
                builder.addDisallowedApplication(this.packageName)
                Log.d(TAG, "Excluding our app from VPN")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Package not found: ${this.packageName}", e)
            }
        } else {
            // 添加所有未被拦截的应用到disallowed列表
            // 这样只有被拦截的应用会走VPN通道
            for (packageName in installedApps) {
                // 跳过自己的应用
                if (packageName == this.packageName) continue
                
                // 如果应用不在拦截列表中，则添加到disallowed列表
                if (blockedApps.contains(packageName)) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.e(TAG, "Package not found: $packageName", e)
                    }
                }
            }
        }

        // 建立VPN连接
        Log.d(TAG, "Establishing VPN connection")
        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN connection")
            return
        }

        Log.i(TAG, "VPN established, blocking traffic for ${blockedApps.size} apps, block video: $isBlockVideo")

        // 处理VPN流量
        val buffer = ByteBuffer.allocate(32767)
        val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

        try {
            // 视频相关的Content-Type和扩展名定义
            val videoContentTypes = setOf(
                "video/mp4", "video/mpeg", "video/quicktime", "video/x-msvideo", 
                "video/x-ms-wmv", "video/webm", "video/ogg", "video/3gpp", "video/3gpp2",
                "application/x-mpegURL", "application/vnd.apple.mpegURL", // HLS
                "application/dash+xml", // DASH
                "application/octet-stream" // 可能是视频二进制流
            )
            
            // 视频文件扩展名
            val videoExtensions = setOf(
                ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".mpeg", ".mpg", ".3gp",
                ".m3u8", ".ts", ".m4s", ".mpd" // 流媒体分段文件
            )
            
            // 连接跟踪表
            val connectionTracker = mutableMapOf<ConnectionKey, ConnectionState>()
            
            // 清理过期连接的时间间隔(毫秒)
            val CLEANUP_INTERVAL = 30000L // 30秒
            var lastCleanupTime = System.currentTimeMillis()
            
            // 视频流量检测参数
            val LARGE_PACKET_THRESHOLD = 500 // 大包阈值(字节)，降低为500字节
            val CONSECUTIVE_LARGE_PACKETS_THRESHOLD = 2 // 连续大包阈值，降低为2个
            val HIGH_BANDWIDTH_THRESHOLD = 20 * 1024L // 高带宽阈值(bytes/second)，降低为20KB/s
            val VIDEO_DETECTION_SCORE_THRESHOLD = 1 // 视频检测分数阈值，降低为1分
            
            // 带宽计算窗口大小
            val BANDWIDTH_WINDOW_SIZE = 5 // 计算带宽的样本数量
            
            Log.d(TAG, "Starting VPN packet processing loop")
            while (isRunning.get()) {
                try {
                    // 清理过期连接
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastCleanupTime > CLEANUP_INTERVAL) {
                        val expiredConnections = connectionTracker.entries.filter { 
                            currentTime - it.value.lastPacketTime > 60000 // 60秒无活动的连接视为过期
                        }
                        for (entry in expiredConnections) {
                            connectionTracker.remove(entry.key)
                        }
                        Log.d(TAG, "Cleaned up ${expiredConnections.size} expired connections, remaining: ${connectionTracker.size}")
                        lastCleanupTime = currentTime
                    }
                    
                    // 读取VPN接口的数据
                    val length = inputStream.read(buffer.array())
                    if (length <= 0) {
                        Thread.sleep(100)
                        continue
                    }
                    
                    // 设置buffer限制
                    buffer.limit(length)
                    
                    // 提取数据包信息并进行分析
                    val packetInfo = extractPacketInfo(buffer, length)
                    if (packetInfo != null) {
                        val (srcIp, srcPort, dstIp, dstPort, protocol, ipHeaderLength) = packetInfo
                        
                        // 获取应用包名
                        val packageName = getPackageNameByUid(getUidByAddress(srcIp, srcPort, dstIp, dstPort))
                        
                        // 创建连接键
                        val connectionKey = ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
                        
                        // 获取或创建连接状态
                        val connectionState = connectionTracker.getOrPut(connectionKey) {
                            ConnectionState(packageName)
                        }
                        
                        // 分析流量特征，判断是否是视频流量
                        val isVideoTraffic = analyzeTrafficPattern(
                            buffer, 
                            length, 
                            ipHeaderLength,
                            protocol, 
                            connectionState,
                            packageName,
                            dstIp,
                            dstPort,
                            LARGE_PACKET_THRESHOLD,
                            CONSECUTIVE_LARGE_PACKETS_THRESHOLD,
                            HIGH_BANDWIDTH_THRESHOLD,
                            VIDEO_DETECTION_SCORE_THRESHOLD,
                            BANDWIDTH_WINDOW_SIZE,
                            videoContentTypes,
                            videoExtensions
                        )
                        // 打印是否是视频流量的判断结果
                        Log.d(TAG, "【$packageName】视频流量：$isVideoTraffic" +
                              "带宽=${connectionState.bandwidthSamples.average().toLong()/1024}KB/s")
                        
                        // 检查是否需要拦截
                        var shouldBlock = false
                        
                        // 简化判断：如果是视频流量且启用了视频拦截，直接拦截
                        if (isBlockVideo && isVideoTraffic) {
                            // shouldBlock = true
                            Log.i(TAG, "Blocked video traffic directly: $packageName, $srcIp:$srcPort -> $dstIp:$dstPort")
                        }
                        // 如果是被屏蔽的应用，拦截所有流量
                        else if (blockedApps.contains(packageName)) {
                            // shouldBlock = true
                            Log.i(TAG, "Blocked app traffic: $packageName, $srcIp:$srcPort -> $dstIp:$dstPort")
                        }
                        
                        if (shouldBlock) {
                            continue // 不转发此数据包
                        }
                    }
                    
                    // 如果不是被屏蔽的流量，直接转发
                    outputStream.write(buffer.array(), 0, buffer.limit())
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Error handling VPN traffic", e)
                    if (!isRunning.get()) break
                    // 短暂休眠后继续
                    Thread.sleep(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in VPN loop", e)
                    if (!isRunning.get()) break
                    Thread.sleep(100)
                }
            }
            
            Log.d(TAG, "VPN packet processing loop ended")
        } catch (e: IOException) {
            Log.e(TAG, "VPN tunnel error", e)
        } finally {
            Log.d(TAG, "Cleaning up VPN resources")
            try {
                inputStream.close()
                outputStream.close()
                vpnInterface?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing streams", e)
            }
        }
    }
    
    /**
     * 从数据包提取基本信息：源IP、源端口、目标IP、目标端口和协议
     */
    private fun extractPacketInfo(buffer: ByteBuffer, length: Int): PacketInfo? {
        if (length <= 20) return null // IP头部至少20字节
        
        val version = (buffer.get(0).toInt() and 0xF0) shr 4
        if (version != 4) return null // 目前只处理IPv4
        
        // 提取协议
        val protocol = buffer.get(9).toInt() and 0xFF
        
        // 提取源IP和目标IP
        val srcIp = InetAddress.getByAddress(byteArrayOf(
            buffer.get(12), buffer.get(13), buffer.get(14), buffer.get(15)
        )).hostAddress
        
        val dstIp = InetAddress.getByAddress(byteArrayOf(
            buffer.get(16), buffer.get(17), buffer.get(18), buffer.get(19)
        )).hostAddress
        
        // 获取IP头部长度
        val ipHeaderLength = (buffer.get(0).toInt() and 0x0F) * 4
        
        // 如果是TCP或UDP协议，提取端口信息
        if (protocol == PROTOCOL_TCP || protocol == PROTOCOL_UDP) {
            if (length <= ipHeaderLength + 4) return null // 确保有足够数据读取端口
            
            // 提取源端口和目标端口
            buffer.position(ipHeaderLength)
            val srcPort = (buffer.get().toInt() and 0xFF) shl 8 or (buffer.get().toInt() and 0xFF)
            val dstPort = (buffer.get().toInt() and 0xFF) shl 8 or (buffer.get().toInt() and 0xFF)
            
            // 重置buffer位置
            buffer.position(0)
            
            return PacketInfo(srcIp, srcPort, dstIp, dstPort, protocol, ipHeaderLength)
        }
        
        // 重置buffer位置
        buffer.position(0)
        return null
    }
    
    /**
     * 数据包信息数据类
     */
    data class PacketInfo(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int,
        val ipHeaderLength: Int
    )
    
    /**
     * 分析流量特征，判断是否是视频流量
     * 
     * @return 是否判定为视频流量
     */
    private fun analyzeTrafficPattern(
        buffer: ByteBuffer,
        length: Int,
        ipHeaderLength: Int,
        protocol: Int,
        connectionState: ConnectionState, // 直接使用ConnectionState类型
        packageName: String?,
        dstIp: String,
        dstPort: Int,
        largePacketThreshold: Int,
        consecutiveLargePacketsThreshold: Int,
        highBandwidthThreshold: Long,
        videoDetectionScoreThreshold: Int,
        bandwidthWindowSize: Int,
        videoContentTypes: Set<String>,
        videoExtensions: Set<String>
    ): Boolean {
        // 不需要类型转换
        val state = connectionState
        val now = System.currentTimeMillis()
        
        // 更新连接状态基本信息
        state.lastPacketTime = now
        state.packetCount++
        state.totalBytes += length
        
        // 记录详细日志，每100个包记录一次
        if (state.packetCount % 100 == 0) {
            Log.d(TAG, "Connection stats: $packageName, packets=${state.packetCount}, " +
                  "bytes=${state.totalBytes}, large packets=${state.largePacketCount}")
        }
        
        // 检查是否是大数据包
        val isLargePacket = length > largePacketThreshold
        if (isLargePacket) {
            state.largePacketCount++
            state.consecutiveLargePackets++
            state.maxConsecutiveLargePackets = 
                Math.max(state.maxConsecutiveLargePackets, state.consecutiveLargePackets)
        } else {
            state.consecutiveLargePackets = 0
        }
        
        // 计算带宽
        if (now - state.lastBandwidthCheckTime >= 1000) { // 每秒计算一次带宽
            val bytesPerSecond = state.bytesLastSecond
            state.bandwidthSamples.add(bytesPerSecond)
            
            // 保持样本窗口大小
            if (state.bandwidthSamples.size > bandwidthWindowSize) {
                state.bandwidthSamples.removeAt(0)
            }
            
            // 重置计数器
            state.bytesLastSecond = length.toLong()
            state.lastBandwidthCheckTime = now
        } else {
            state.bytesLastSecond += length.toLong()
        }
        
        // 视频特征评分
        var videoScore = 0
        
        // 特征1: 连续大包
        if (state.maxConsecutiveLargePackets >= consecutiveLargePacketsThreshold) {
            videoScore += 2
            Log.i(TAG, "Video feature: consecutive large packets=${state.maxConsecutiveLargePackets}, $packageName")
        }
        
        // 特征2: 高带宽
        val avgBandwidth = if (state.bandwidthSamples.isNotEmpty()) {
            state.bandwidthSamples.average().toLong()
        } else 0L
        
        if (avgBandwidth > highBandwidthThreshold) {
            videoScore += 2
            Log.i(TAG, "Video feature: high bandwidth=${avgBandwidth/1024}KB/s, $packageName")
        }
        
        // 特征3: 长连接
        val connectionDuration = now - state.startTime
        if (connectionDuration > 10000 && state.packetCount > 50) { // 10秒以上且超过50个包
            videoScore += 1
            Log.i(TAG, "Video feature: long connection=${connectionDuration/1000}s, packets=${state.packetCount}, $packageName")
        }
        
        // 特征4: HTTP内容分析(针对非加密流量)
        if (protocol == PROTOCOL_TCP && (dstPort == 80 || dstPort == 8080)) {
            val httpData = extractHttpRequest(buffer, ipHeaderLength)
            if (httpData.isNotEmpty()) {
                // 尝试提取Content-Type
                val contentTypeMatch = Regex("Content-Type:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE).find(httpData)
                val contentType = contentTypeMatch?.groupValues?.get(1)?.trim() ?: ""
                
                // 检查是否是视频Content-Type
                if (videoContentTypes.any { contentType.contains(it, ignoreCase = true) }) {
                    videoScore += 3 // 强烈的视频特征
                }
                
                // 检查URL路径
                val pathMatch = Regex("GET\\s+([^\\s]+)\\s+HTTP", RegexOption.IGNORE_CASE).find(httpData)
                val path = pathMatch?.groupValues?.get(1)?.trim() ?: ""
                
                // 检查是否是视频文件扩展名
                if (videoExtensions.any { path.endsWith(it, ignoreCase = true) }) {
                    videoScore += 3 // 强烈的视频特征
                }
                
                // 检查Range头(视频流常用)
                if (httpData.contains("Range: bytes=", ignoreCase = true)) {
                    videoScore += 2
                }
            }
        }
        
        // 更新视频特征得分
        state.videoFeatureScore = videoScore
        
        // 判断是否是视频流量
        val isVideoTraffic = videoScore >= videoDetectionScoreThreshold
        
        // 记录所有连接的流量特征，不仅仅是视频流量
        if (state.packetCount % 20 == 0) {  // 每20个包记录一次
            Log.i(TAG, "Connection stats: $packageName, " +
                  "protocol=${if (protocol == PROTOCOL_TCP) "TCP" else "UDP"}, " +
                  "score=$videoScore, bandwidth=${avgBandwidth/1024}KB/s, " +
                  "packets=${state.packetCount}, " +
                  "consecutive large packets=${state.maxConsecutiveLargePackets}")
        }
        
        // 如果是新检测到的视频流量，记录日志
        if (isVideoTraffic && !state.isVideoTraffic) {
            state.isVideoTraffic = true
            Log.i(TAG, "Detected video traffic: $packageName, " +
                  "protocol=${if (protocol == PROTOCOL_TCP) "TCP" else "UDP"}, " +
                  "score=$videoScore, bandwidth=${avgBandwidth/1024}KB/s, " +
                  "consecutive large packets=${state.maxConsecutiveLargePackets}")
        }
        
        return isVideoTraffic
    }

    /**
     * 根据IP地址和端口获取应用的UID
     */
    private fun getUidByAddress(srcIp: String, srcPort: Int, dstIp: String = "", dstPort: Int = 0): Int {
        try {
            // 尝试使用Android API获取UID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && connectivity != null) {
                val srcAddress = InetSocketAddress(InetAddress.getByName(srcIp), srcPort)
                val dstAddress = if (dstIp.isNotEmpty() && dstPort > 0) {
                    InetSocketAddress(InetAddress.getByName(dstIp), dstPort)
                } else {
                    InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0)
                }
                
                // 使用TCP协议
                val uid = connectivity?.getConnectionOwnerUid(PROTOCOL_TCP, srcAddress, dstAddress)
                if (uid != null && uid > 0) {
                    return uid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting UID by address", e)
        }
        return -1
    }
    
    // 根据UID获取包名
    private fun getPackageNameByUid(uid: Int): String? {
        try {
            val pm = packageManager
            val packages = pm.getPackagesForUid(uid)
            return packages?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting package name by UID", e)
            return null
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
        
        // 检查是否启用了视频屏蔽
        val isBlockVideo = FirewallManager.isBlockVideo(this)
        
        val contentText = if (appClosed) {
            "应用已关闭，防火墙仍在后台运行 ($durationText)"
        } else if (isBlockVideo) {
            "正在拦截视频流量和选定应用的网络访问 ($durationText)"
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

    /**
     * 从数据包中提取HTTP请求
     */
    private fun extractHttpRequest(buffer: ByteBuffer, ipHeaderLength: Int): String {
        try {
            // 获取TCP头部长度
            buffer.position(ipHeaderLength + 12)
            val tcpHeaderLength = ((buffer.get().toInt() and 0xF0) shr 4) * 4
            
            // 计算HTTP数据开始位置
            val httpStart = ipHeaderLength + tcpHeaderLength
            
            // 重置buffer位置
            buffer.position(0)
            
            // 确保有足够的数据
            if (buffer.limit() <= httpStart) {
                return ""
            }
            
            // 提取HTTP数据
            val httpData = ByteArray(Math.min(buffer.limit() - httpStart, 1024))
            buffer.position(httpStart)
            buffer.get(httpData, 0, httpData.size)
            
            // 重置buffer位置
            buffer.position(0)
            
            // 转换为字符串
            return String(httpData)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting HTTP request", e)
            return ""
        }
    }
    
    /**
     * 判断是否是视频请求
     */
    private fun isVideoRequest(httpRequest: String, host: String, path: String = "", contentType: String = "", accept: String = ""): Boolean {
        // 视频相关的Content-Type
        val videoContentTypes = setOf(
            "video/mp4", "video/mpeg", "video/quicktime", "video/x-msvideo", 
            "video/x-ms-wmv", "video/webm", "video/ogg", "video/3gpp", "video/3gpp2",
            "application/x-mpegURL", "application/vnd.apple.mpegURL", // HLS
            "application/dash+xml", // DASH
            "application/octet-stream" // 可能是视频二进制流
        )
        
        // 视频文件扩展名
        val videoExtensions = setOf(
            ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".mpeg", ".mpg", ".3gp",
            ".m3u8", ".ts", ".m4s", ".mpd" // 流媒体分段文件
        )
        
        // 检查Content-Type
        if (contentType.isNotEmpty()) {
            for (type in videoContentTypes) {
                if (contentType.contains(type, ignoreCase = true)) {
                    return true
                }
            }
        }
        
        // 检查Accept头
        if (accept.isNotEmpty()) {
            for (type in videoContentTypes) {
                if (accept.contains(type, ignoreCase = true)) {
                    return true
                }
            }
        }
        
        // 检查Range头（视频通常使用Range请求）
        if (httpRequest.contains("Range: bytes=") && (
            path.endsWith(".mp4") || path.endsWith(".flv") || path.endsWith(".m3u8") || 
            path.endsWith(".ts") || path.endsWith(".mpd") || path.endsWith(".m4s")
        )) {
            return true
        }
        
        // 检查文件扩展名
        for (ext in videoExtensions) {
            if (path.endsWith(ext, ignoreCase = true)) {
                return true
            }
        }
        
        // 检查URL路径中的视频关键词
        val videoKeywords = listOf("/video/", "/media/", "/stream/", "/play/", "/hls/", "/dash/")
        for (keyword in videoKeywords) {
            if (path.contains(keyword, ignoreCase = true)) {
                return true
            }
        }
        
        return false
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