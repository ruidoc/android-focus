package com.example.adproject.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.adproject.model.AppInfo
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.util.AppUtils
import com.example.adproject.util.FirewallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallViewModel : ViewModel() {
    
    // UI状态
    private val _uiState = MutableStateFlow(FirewallUiState())
    val uiState: StateFlow<FirewallUiState> = _uiState.asStateFlow()
    
    // 加载应用列表
    fun loadAppList(context: Context, showSystemApps: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val apps = withContext(Dispatchers.IO) {
                val apps = AppUtils.getInstalledApps(context, showSystemApps)
                // 设置应用是否被拦截
                val blockedApps = FirewallManager.getBlockedApps(context)
                apps.forEach { app ->
                    app.isBlocked = blockedApps.contains(app.packageName)
                }
                apps
            }
            
            // 获取视频屏蔽状态
            val isBlockVideo = FirewallManager.isBlockVideo(context)
            
            _uiState.update { 
                it.copy(
                    appList = apps,
                    isLoading = false,
                    blockedApps = apps.filter { app -> app.isBlocked },
                    isBlockVideo = isBlockVideo
                ) 
            }
        }
    }
    
    // 更新应用拦截状态
    fun updateAppBlockStatus(context: Context, appInfo: AppInfo, isBlocked: Boolean) {
        viewModelScope.launch {
            // 更新应用状态
            appInfo.isBlocked = isBlocked
            
            // 更新存储
            if (isBlocked) {
                FirewallManager.addBlockedApp(context, appInfo.packageName)
            } else {
                FirewallManager.removeBlockedApp(context, appInfo.packageName)
            }
            
            // 更新UI状态
            val currentAppList = _uiState.value.appList.toMutableList()
            val index = currentAppList.indexOfFirst { it.packageName == appInfo.packageName }
            if (index != -1) {
                currentAppList[index] = appInfo
            }
            
            _uiState.update { 
                it.copy(
                    appList = currentAppList,
                    blockedApps = currentAppList.filter { app -> app.isBlocked }
                ) 
            }
            
            // 如果VPN已启动，重启VPN服务以应用新的拦截规则
            if (_uiState.value.isVpnActive) {
                restartVpnService(context)
            }
        }
    }
    
    // 更新视频屏蔽状态
    fun updateVideoBlockStatus(context: Context, blockVideo: Boolean) {
        viewModelScope.launch {
            // 更新存储
            FirewallManager.setBlockVideo(context, blockVideo)
            
            // 更新UI状态
            _uiState.update { it.copy(isBlockVideo = blockVideo) }
            
            // 如果VPN已启动，重启VPN服务以应用新的拦截规则
            if (_uiState.value.isVpnActive) {
                restartVpnService(context)
            } else if (blockVideo) {
                // 如果VPN未启动但启用了视频屏蔽，则启动VPN
                FirewallVpnService.start(context)
                _uiState.update { it.copy(isVpnActive = true) }
            }
        }
    }
    
    // 切换VPN状态
    fun toggleVpnStatus(context: Context, active: Boolean) {
        viewModelScope.launch {
            if (active) {
                FirewallVpnService.start(context)
            } else {
                FirewallVpnService.stop(context)
            }
            
            _uiState.update { it.copy(isVpnActive = active) }
        }
    }
    
    // 更新VPN状态（不启动或停止服务，只更新UI状态）
    fun updateVpnStatus(context: Context, active: Boolean) {
        _uiState.update { it.copy(isVpnActive = active) }
    }
    
    // 重启VPN服务
    private fun restartVpnService(context: Context) {
        viewModelScope.launch {
            FirewallVpnService.stop(context)
            FirewallVpnService.start(context)
        }
    }
    
    // 检查VPN状态
    fun checkVpnStatus(context: Context) {
        val isActive = FirewallManager.isVpnActive(context)
        _uiState.update { it.copy(isVpnActive = isActive) }
    }
    
    // 检查视频屏蔽状态
    fun checkVideoBlockStatus(context: Context) {
        val isBlockVideo = FirewallManager.isBlockVideo(context)
        _uiState.update { it.copy(isBlockVideo = isBlockVideo) }
    }
    
    // 切换显示系统应用
    fun toggleShowSystemApps(context: Context) {
        val currentValue = _uiState.value.showSystemApps
        _uiState.update { it.copy(showSystemApps = !currentValue) }
        loadAppList(context, !currentValue)
    }
}

// UI状态数据类
data class FirewallUiState(
    val isLoading: Boolean = true,
    val appList: List<AppInfo> = emptyList(),
    val blockedApps: List<AppInfo> = emptyList(),
    val isVpnActive: Boolean = false,
    val showSystemApps: Boolean = false,
    val isBlockVideo: Boolean = false
) 