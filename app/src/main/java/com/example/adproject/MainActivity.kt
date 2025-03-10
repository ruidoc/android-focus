package com.example.adproject

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adproject.model.AppInfo
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.service.FloatingWindowService
import com.example.adproject.ui.components.AppItem
import com.example.adproject.ui.components.CustomSwitch
import com.example.adproject.ui.theme.ADProjectTheme
import com.example.adproject.ui.theme.AliBlue
import com.example.adproject.ui.theme.Background
import com.example.adproject.ui.theme.Gray200
import com.example.adproject.ui.theme.Gray50
import com.example.adproject.ui.theme.Gray500
import com.example.adproject.ui.theme.Success
import com.example.adproject.ui.theme.TencentGreen
import com.example.adproject.util.AppUtils
import com.example.adproject.util.FirewallManager
import com.example.adproject.util.VpnStatusTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.adproject.navigation.AppNavigation
import androidx.lifecycle.ViewModelProvider
import com.example.adproject.viewmodel.FirewallViewModel

class MainActivity : ComponentActivity(), VpnStatusTracker.VpnStatusListener {
    private val TAG = "MainActivity"
    private lateinit var viewModel: FirewallViewModel
    private val handler = Handler(Looper.getMainLooper())
    private val VPN_CHECK_INTERVAL = 30000L // 30秒检查一次VPN状态
    private var vpnDuration by mutableStateOf("00:00:00")
    var isFloatingWindowEnabled by mutableStateOf(false)
        private set

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN权限被拒绝，无法启用防火墙", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 悬浮窗权限申请结果
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        if (Settings.canDrawOverlays(this)) {
            startFloatingWindow()
            isFloatingWindowEnabled = true
        } else {
            Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            isFloatingWindowEnabled = false
        }
    }
    
    // 定期检查VPN状态的Runnable
    private val vpnCheckRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Checking VPN status")
            checkVpnServiceStatus()
            // 检查VPN是否需要重启
            FirewallVpnService.checkAndRestartIfNeeded(applicationContext)
            // 继续定期检查
            handler.postDelayed(this, VPN_CHECK_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化ViewModel
        viewModel = ViewModelProvider(this)[FirewallViewModel::class.java]
        
        // 检查VPN服务状态
        checkVpnServiceStatus()
        
        // 开始定期检查VPN状态
        startVpnStatusCheck()
        
        // 注册VPN状态监听器
        VpnStatusTracker.addListener(this)
        
        // 如果VPN已启动且有悬浮窗权限，自动启动悬浮窗
        if (FirewallManager.isVpnActive(this) && checkOverlayPermission()) {
            try {
                Log.d(TAG, "onCreate: 尝试启动悬浮窗")
                startFloatingWindow()
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: 启动悬浮窗失败", e)
                Toast.makeText(this, "启动悬浮窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        enableEdgeToEdge()
        setContent {
            ADProjectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(vpnLauncher = vpnLauncher, vpnDuration = vpnDuration)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次应用回到前台时检查VPN状态
        checkVpnServiceStatus()
        // 确保定期检查正在运行
        startVpnStatusCheck()
        // 更新VPN持续时间
        updateVpnDuration()
        
        // 如果VPN已启动但悬浮窗未显示，且有悬浮窗权限，则启动悬浮窗
        if (FirewallManager.isVpnActive(this) && !isFloatingWindowEnabled && checkOverlayPermission()) {
            startFloatingWindow()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 应用进入后台时，不停止VPN状态检查，确保VPN服务持续运行
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 停止定期检查
        stopVpnStatusCheck()
        // 移除VPN状态监听器
        VpnStatusTracker.removeListener(this)
    }
    
    override fun onVpnStatusUpdated() {
        // 当VPN状态更新时，更新UI
        updateVpnDuration()
    }
    
    private fun updateVpnDuration() {
        vpnDuration = VpnStatusTracker.getFormattedDuration(this)
    }
    
    private fun startVpnStatusCheck() {
        // 先停止之前的检查，避免重复
        stopVpnStatusCheck()
        // 开始新的检查
        handler.post(vpnCheckRunnable)
        Log.d(TAG, "Started VPN status check")
    }
    
    private fun stopVpnStatusCheck() {
        handler.removeCallbacks(vpnCheckRunnable)
        Log.d(TAG, "Stopped VPN status check")
    }
    
    private fun checkVpnServiceStatus() {
        // 检查VPN服务是否在运行
        val isVpnRunning = FirewallVpnService.isRunning(this)
        viewModel.updateVpnStatus(this, isVpnRunning)
        Log.d(TAG, "VPN status check: running = $isVpnRunning")
        
        // 更新心跳
        if (isVpnRunning) {
            FirewallManager.updateHeartbeat(this)
        }
    }

    private fun startVpnService() {
        FirewallVpnService.start(this)
        viewModel.updateVpnStatus(this, true)
        
        // 开始跟踪VPN状态
        VpnStatusTracker.startTracking(this)
        
        // 如果有悬浮窗权限，自动启动悬浮窗
        if (checkOverlayPermission() && !isFloatingWindowEnabled) {
            startFloatingWindow()
        }
    }

    private fun stopVpnService() {
        FirewallVpnService.stop(this)
        viewModel.updateVpnStatus(this, false)
        
        // 停止跟踪VPN状态
        VpnStatusTracker.stopTracking(this)
    }
    
    // 检查悬浮窗权限
    fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true // 低于Android 6.0的设备默认有权限
        }
    }
    
    // 请求悬浮窗权限
    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }
    
    // 启动悬浮窗
    fun startFloatingWindow() {
        if (checkOverlayPermission()) {
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isFloatingWindowEnabled = true
        } else {
            requestOverlayPermission()
        }
    }
    
    // 停止悬浮窗
    fun stopFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        stopService(intent)
        isFloatingWindowEnabled = false
    }
    
    // 切换悬浮窗状态
    fun toggleFloatingWindow() {
        try {
            if (isFloatingWindowEnabled) {
                stopFloatingWindow()
            } else {
                if (checkOverlayPermission()) {
                    startFloatingWindow()
                } else {
                    requestOverlayPermission()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling floating window", e)
            Toast.makeText(this, "启动悬浮窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallApp(vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>, vpnDuration: String = "00:00:00") {
    val context = LocalContext.current
    var appList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isVpnActive by remember { mutableStateOf(FirewallManager.isVpnActive(context)) }
    var showSystemApps by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val mainActivity = context as? MainActivity
    var isFloatingWindowEnabled by remember { mutableStateOf(mainActivity?.isFloatingWindowEnabled ?: false) }

    // 加载应用列表
    LaunchedEffect(showSystemApps, refreshTrigger) {
        isLoading = true
        val apps = withContext(Dispatchers.IO) {
            val apps = AppUtils.getInstalledApps(context, showSystemApps)
            // 设置应用是否被拦截
            val blockedApps = FirewallManager.getBlockedApps(context)
            apps.forEach { app ->
                app.isBlocked = blockedApps.contains(app.packageName)
            }
            apps
        }
        appList = apps
        isLoading = false
    }

    // 监听VPN状态变化
    LaunchedEffect(Unit) {
        isVpnActive = FirewallManager.isVpnActive(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "应用防火墙",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    // 悬浮窗按钮
                    IconButton(onClick = {
                        mainActivity?.toggleFloatingWindow()
                        isFloatingWindowEnabled = !(mainActivity?.isFloatingWindowEnabled ?: false)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = if (isFloatingWindowEnabled) "关闭悬浮窗" else "开启悬浮窗"
                        )
                    }
                    
                    IconButton(onClick = {
                        // 触发刷新
                        refreshTrigger += 1
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = {
                        // 这里可以添加搜索功能
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isLoading && appList.any { it.isBlocked }) {
                FloatingActionButton(
                    onClick = {
                        if (isVpnActive) {
                            FirewallVpnService.stop(context)
                            isVpnActive = false
                            // 停止跟踪VPN状态
                            VpnStatusTracker.stopTracking(context)
                        } else {
                            val vpnIntent = FirewallVpnService.prepareVpn(context)
                            if (vpnIntent != null) {
                                vpnLauncher.launch(vpnIntent)
                            } else {
                                FirewallVpnService.start(context)
                                isVpnActive = true
                                // 开始跟踪VPN状态
                                VpnStatusTracker.startTracking(context)
                            }
                        }
                    },
                    containerColor = if (isVpnActive) Success else AliBlue
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = if (isVpnActive) "停止防火墙" else "启动防火墙",
                        tint = Color.White
                    )
                }
            }
        },
        containerColor = Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 状态卡片
            StatusCard(
                isVpnActive = isVpnActive,
                blockedCount = appList.count { it.isBlocked },
                vpnDuration = vpnDuration,
                onVpnToggle = { checked ->
                    if (checked) {
                        val vpnIntent = FirewallVpnService.prepareVpn(context)
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            FirewallVpnService.start(context)
                            // 开始跟踪VPN状态
                            VpnStatusTracker.startTracking(context)
                        }
                    } else {
                        FirewallVpnService.stop(context)
                        // 停止跟踪VPN状态
                        VpnStatusTracker.stopTracking(context)
                    }
                    isVpnActive = checked
                },
                onSystemAppsToggle = { checked ->
                    showSystemApps = checked
                },
                showSystemApps = showSystemApps
            )
            
            // 应用列表标题
            Text(
                text = "选择要拦截的应用",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // 应用列表
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AliBlue)
                }
            } else if (appList.isEmpty()) {
                // 空状态
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp) // 为FAB留出空间
                ) {
                    items(appList) { app ->
                        AppItem(
                            appInfo = app,
                            onAppSelected = { appInfo, isBlocked ->
                                // 更新应用拦截状态
                                appInfo.isBlocked = isBlocked
                                
                                // 创建一个新的列表，触发重组
                                appList = appList.toMutableList()
                                
                                if (isBlocked) {
                                    FirewallManager.addBlockedApp(context, appInfo.packageName)
                                } else {
                                    FirewallManager.removeBlockedApp(context, appInfo.packageName)
                                }
                                
                                // 如果VPN已启动，需要重启VPN服务以应用新的拦截规则
                                if (isVpnActive) {
                                    coroutineScope.launch {
                                        FirewallVpnService.stop(context)
                                        FirewallVpnService.start(context)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    isVpnActive: Boolean,
    blockedCount: Int,
    vpnDuration: String,
    onVpnToggle: (Boolean) -> Unit,
    onSystemAppsToggle: (Boolean) -> Unit,
    showSystemApps: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 状态信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isVpnActive) "防火墙已启用" else "防火墙已禁用",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已选择 $blockedCount 个应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500
                    )
                    if (isVpnActive) {
                        Text(
                            text = "运行时间: $vpnDuration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gray500
                        )
                    }
                }
                
                CustomSwitch(
                    checked = isVpnActive,
                    onCheckedChange = onVpnToggle,
                    useGreenTheme = true
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 系统应用开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "显示系统应用",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                CustomSwitch(
                    checked = showSystemApps,
                    onCheckedChange = onSystemAppsToggle
                )
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Gray200
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "没有找到应用",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "尝试刷新或启用显示系统应用选项",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray500,
                textAlign = TextAlign.Center
            )
        }
    }
}