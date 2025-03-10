package com.example.adproject.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
        val prefs = getPrefs(context)
        val isActive = prefs.getBoolean(KEY_VPN_ACTIVE, false)
        
        // 如果SharedPreferences中显示VPN处于活动状态，验证心跳是否正常
        if (isActive) {
            val lastHeartbeat = prefs.getLong(KEY_VPN_HEARTBEAT, 0)
            val currentTime = System.currentTimeMillis()
            
            // 如果心跳超时（超过2分钟没有更新），认为VPN已经停止
            if (currentTime - lastHeartbeat > 2 * 60 * 1000) {
                Log.d(TAG, "VPN heartbeat timeout, considering VPN inactive")
                setVpnActive(context, false)
                return false
            }
        } else {
            // 如果SharedPreferences中显示VPN不活动，尝试从备份恢复
            val backupActive = restoreFromBackup(context, KEY_VPN_ACTIVE) as? Boolean
            if (backupActive == true) {
                // 检查备份的时间是否在合理范围内（例如30分钟内）
                val lastActiveTime = prefs.getLong(KEY_VPN_LAST_ACTIVE_TIME, 0)
                if (System.currentTimeMillis() - lastActiveTime < 30 * 60 * 1000) {
                    Log.d(TAG, "Restored VPN active state from backup")
                    setVpnActive(context, true)
                    return true
                }
            }
        }
        
        return isActive
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