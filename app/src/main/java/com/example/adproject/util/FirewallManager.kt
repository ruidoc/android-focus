package com.example.adproject.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 防火墙管理类
 */
object FirewallManager {
    private const val TAG = "FirewallManager"
    private const val PREFS_NAME = "firewall_prefs"
    private const val KEY_BLOCKED_APPS = "blocked_apps"
    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_VPN_LAST_ACTIVE_TIME = "vpn_last_active_time"
    private const val KEY_VPN_HEARTBEAT = "vpn_heartbeat"
    private const val BACKUP_FILE_NAME = "firewall_backup.dat"
    private const val BLOCK_VIDEO_KEY = "block_video"
    
    // 心跳检查间隔（毫秒）
    private const val HEARTBEAT_INTERVAL = 30000L // 30秒

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取被拦截的应用包名列表
     */
    fun getBlockedApps(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val blockedApps = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        
        // 如果SharedPreferences中没有数据，尝试从备份文件恢复
        if (blockedApps.isEmpty()) {
            return restoreFromBackup(context, KEY_BLOCKED_APPS) as? Set<String> ?: emptySet()
        }
        
        return blockedApps
    }

    /**
     * 设置被拦截的应用包名列表
     */
    fun setBlockedApps(context: Context, blockedApps: Set<String>) {
        getPrefs(context).edit {
            putStringSet(KEY_BLOCKED_APPS, blockedApps)
        }
        
        // 创建备份
        createBackup(context, KEY_BLOCKED_APPS, blockedApps)
    }

    /**
     * 添加被拦截的应用
     */
    fun addBlockedApp(context: Context, packageName: String) {
        val blockedApps = getBlockedApps(context).toMutableSet()
        blockedApps.add(packageName)
        setBlockedApps(context, blockedApps)
    }

    /**
     * 移除被拦截的应用
     */
    fun removeBlockedApp(context: Context, packageName: String) {
        val blockedApps = getBlockedApps(context).toMutableSet()
        blockedApps.remove(packageName)
        setBlockedApps(context, blockedApps)
    }

    /**
     * 检查应用是否被拦截
     */
    fun isAppBlocked(context: Context, packageName: String): Boolean {
        return getBlockedApps(context).contains(packageName)
    }

    /**
     * 设置VPN是否激活
     */
    fun setVpnActive(context: Context, active: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit {
            putBoolean(KEY_VPN_ACTIVE, active)
            if (active) {
                // 记录最后一次激活时间
                putLong(KEY_VPN_LAST_ACTIVE_TIME, System.currentTimeMillis())
            }
        }
        
        // 创建备份
        createBackup(context, KEY_VPN_ACTIVE, active)
        
        // 如果激活VPN，开始心跳检查
        if (active) {
            updateHeartbeat(context)
        }
        
        Log.d(TAG, "VPN active state set to: $active")
    }

    /**
     * 获取VPN是否激活
     */
    fun isVpnActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_VPN
        }
    }
    
    /**
     * 更新VPN心跳
     */
    fun updateHeartbeat(context: Context) {
        val currentTime = System.currentTimeMillis()
        getPrefs(context).edit {
            putLong(KEY_VPN_HEARTBEAT, currentTime)
        }
        Log.d(TAG, "VPN heartbeat updated at $currentTime")
    }
    
    /**
     * 检查VPN是否需要重启
     * 如果VPN应该处于活动状态但心跳检查失败，返回true
     */
    fun shouldRestartVpn(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isActive = prefs.getBoolean(KEY_VPN_ACTIVE, false)
        
        if (isActive) {
            val lastHeartbeat = prefs.getLong(KEY_VPN_HEARTBEAT, 0)
            val currentTime = System.currentTimeMillis()
            
            // 如果心跳超过1分钟没有更新，但VPN应该是活动的，需要重启
            return currentTime - lastHeartbeat > 60 * 1000
        }
        
        return false
    }
    
    /**
     * 设置是否屏蔽视频流量
     */
    fun setBlockVideo(context: Context, block: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit {
            putBoolean(BLOCK_VIDEO_KEY, block)
        }
    }
    
    /**
     * 获取是否屏蔽视频流量
     */
    fun isBlockVideo(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(BLOCK_VIDEO_KEY, false)
    }
    
    /**
     * 检查是否应该屏蔽特定的流量
     */
    fun shouldBlockTraffic(context: Context, packageName: String, protocol: String, port: Int, host: String): Boolean {
        // 如果应用在屏蔽列表中，则屏蔽所有流量
        if (getBlockedApps(context).contains(packageName)) {
            return true
        }
        
        // 如果启用了视频屏蔽，检查是否是视频流量
        if (isBlockVideo(context) && isVideoTraffic(protocol, port, host)) {
            return true
        }
        
        return false
    }
    
    /**
     * 判断是否是视频流量
     */
    private fun isVideoTraffic(protocol: String, port: Int, host: String): Boolean {
        // 微信特殊处理 - 允许微信的基本功能
        if (host.contains("weixin.qq.com") || host.contains("wx.qq.com") || 
            host.contains("tencent.com") || host.contains("qq.com")) {
            
            // 只拦截视频号相关域名 - 更精确的列表
            val wechatVideoSpecificDomains = listOf(
                "finder.video.qq.com",
                "finder-video.qq.com",
                "finderdev.video.qq.com",
                "findermp.video.qq.com",
                "wxasrsf.video.qq.com",
                "szextshort.weixin.qq.com",
                "szminorshort.weixin.qq.com",
                "szshort.weixin.qq.com"
            )
            
            // 如果是微信视频号特定域名，则拦截
            if (wechatVideoSpecificDomains.any { domain -> host == domain }) {
                return true
            }
            
            // 如果是微信基础域名，检查是否包含视频相关路径 - 更精确的匹配
            val videoPathPatterns = listOf(
                "/finder/",
                "/channels/",
                "/video/",
                "/play/",
                "/stream/"
            )
            
            // 只有当完全匹配视频路径时才拦截
            for (pattern in videoPathPatterns) {
                if (host.contains(pattern)) {
                    // 确保这是一个完整的路径部分，而不是部分匹配
                    val index = host.indexOf(pattern)
                    val endIndex = index + pattern.length
                    
                    // 检查前后是否是路径分隔符或字符串结束
                    val isStartValid = index == 0 || host[index - 1] == '/' || host[index - 1] == '.'
                    val isEndValid = endIndex >= host.length || host[endIndex] == '/' || host[endIndex] == '?' || host[endIndex] == '&'
                    
                    if (isStartValid && isEndValid) {
                        return true
                    }
                }
            }
            
            // 默认允许微信域名的流量通过
            return false
        }
        
        // 视频流量特征检测
        // 1. 常见视频流媒体服务域名
        val videoHosts = listOf(
            "youtube.com", "youtu.be", "netflix.com", "hulu.com", "bilibili.com", 
            "iqiyi.com", "vimeo.com", "dailymotion.com", "twitch.tv", "tiktok.com",
            "douyin.com", "kuaishou.com", "hbomax.com", "disneyplus.com", "primevideo.com",
            "mgtv.com", "youku.com", "tudou.com", "pptv.com", "le.com",
            "ixigua.com", "douyu.com", "huya.com", "v.qq.com", "wetv.vip",
            "weibo.com/tv", "facebook.com/watch", "instagram.com/tv", "twitter.com/i/videos"
        )
        
        // 2. 常见视频流媒体CDN域名
        val videoCdnHosts = listOf(
            "googlevideo.com", "akamaihd.net", "cloudfront.net", "fastly.net", 
            "cdn.com", "cdnvideo.ru", "level3.net", "llnwd.net", "footprint.net",
            "edgecastcdn.net", "bitgravity.com", "limelight.com", "cdn77.org",
            "cdnetworks.com", "cachefly.net", "hwcdn.net", "steamcontent.com",
            "alicdn.com", "qiniu.com", "wscdns.com", "chinanetcenter.com",
            // 腾讯云CDN
            "qcloudcdn.com", "tencentcdn.com", "tcdn.qq.com", "cdntip.com",
            "myqcloud.com", "qcloud.la", "coscdn.com", "file.myqcloud.com",
            "cos.ap-beijing.myqcloud.com", "cos.ap-guangzhou.myqcloud.com"
        )
        
        // 3. 常见视频流媒体端口
        val videoPorts = listOf(1935, 554, 8080, 8081, 8082, 443, 80)
        
        // 4. 常见视频流媒体协议
        val videoProtocols = listOf("rtmp", "rtsp", "hls", "dash", "http", "https")
        
        // 5. 检查常见视频文件扩展名
        val videoExtensions = listOf(".mp4", ".flv", ".m3u8", ".ts", ".mpd", ".mov", ".avi", ".mkv", ".webm")
        
        // 检查域名是否匹配视频服务
        for (videoHost in videoHosts) {
            if (host.contains(videoHost)) {
                return true
            }
        }
        
        // 检查域名是否匹配视频CDN
        for (cdnHost in videoCdnHosts) {
            if (host.contains(cdnHost)) {
                return true
            }
        }
        
        // 检查URL是否包含视频文件扩展名
        for (ext in videoExtensions) {
            if (host.contains(ext)) {
                return true
            }
        }
        
        // 检查端口和协议
        if (videoPorts.contains(port) && videoProtocols.contains(protocol.lowercase())) {
            // 不要仅仅因为端口和协议匹配就判断为视频流量
            // 这可能会误判很多正常HTTP/HTTPS流量
            return false
        }
        
        return false
    }
    
    /**
     * 创建设置的备份
     */
    private fun createBackup(context: Context, key: String, value: Any) {
        try {
            val backupDir = File(context.filesDir, "backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }
            
            val backupFile = File(backupDir, "${key}_$BACKUP_FILE_NAME")
            val fos = FileOutputStream(backupFile)
            
            when (value) {
                is Boolean -> fos.write(if (value) 1 else 0)
                is Set<*> -> {
                    val data = (value as Set<String>).joinToString(",").toByteArray()
                    fos.write(data)
                }
                else -> Log.e(TAG, "Unsupported backup type: ${value.javaClass.name}")
            }
            
            fos.close()
            Log.d(TAG, "Backup created for $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup for $key", e)
        }
    }
    
    /**
     * 从备份恢复设置
     */
    private fun restoreFromBackup(context: Context, key: String): Any? {
        try {
            val backupDir = File(context.filesDir, "backups")
            val backupFile = File(backupDir, "${key}_$BACKUP_FILE_NAME")
            
            if (!backupFile.exists()) {
                return null
            }
            
            val fis = FileInputStream(backupFile)
            
            return when (key) {
                KEY_VPN_ACTIVE -> {
                    val value = fis.read() == 1
                    fis.close()
                    value
                }
                KEY_BLOCKED_APPS -> {
                    val bytes = fis.readBytes()
                    fis.close()
                    val data = String(bytes)
                    if (data.isEmpty()) emptySet() else data.split(",").toSet()
                }
                else -> {
                    fis.close()
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup for $key", e)
            return null
        }
    }
} 