package com.example.adproject.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adproject.MainActivity
import com.example.adproject.model.AppInfo
import com.example.adproject.service.FirewallVpnService
import com.example.adproject.ui.theme.AliBlue
import com.example.adproject.ui.theme.Background
import com.example.adproject.ui.theme.Gray100
import com.example.adproject.ui.theme.Gray200
import com.example.adproject.ui.theme.Gray500
import com.example.adproject.ui.theme.Success
import com.example.adproject.ui.theme.TencentGreen
import com.example.adproject.util.FirewallManager
import com.example.adproject.viewmodel.FirewallViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAppList: () -> Unit,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    vpnDuration: String = "00:00:00",
    viewModel: FirewallViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val mainActivity = context as? MainActivity
    var isFloatingWindowEnabled by remember { mutableStateOf(mainActivity?.isFloatingWindowEnabled ?: false) }
    
    // 加载数据
    LaunchedEffect(Unit) {
        viewModel.loadAppList(context, uiState.showSystemApps)
        viewModel.checkVpnStatus(context)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "防火墙",
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
                    
                    // 应用列表按钮
                    IconButton(onClick = onNavigateToAppList) {
                        Icon(Icons.Default.List, contentDescription = "应用列表")
                    }
                }
            )
        },
        containerColor = Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态卡片
            StatusCard(
                isVpnActive = uiState.isVpnActive,
                blockedCount = uiState.blockedApps.size,
                vpnDuration = vpnDuration
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 大型开关按钮
            VpnPowerButton(
                isActive = uiState.isVpnActive,
                onClick = {
                    if (!uiState.isVpnActive) {
                        // 启动VPN
                        val vpnIntent = FirewallVpnService.prepareVpn(context)
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            viewModel.toggleVpnStatus(context, true)
                        }
                    } else {
                        // 停止VPN
                        viewModel.toggleVpnStatus(context, false)
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 已选择的应用
            Text(
                text = "已选择拦截的应用",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            if (uiState.isLoading) {
                CircularProgressIndicator(color = AliBlue)
            } else if (uiState.blockedApps.isEmpty()) {
                EmptyBlockedApps(onAddClick = onNavigateToAppList)
            } else {
                BlockedAppsList(
                    apps = uiState.blockedApps,
                    onAddClick = onNavigateToAppList
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 管理应用按钮
            Button(
                onClick = onNavigateToAppList,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AliBlue
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("管理应用列表", modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
fun StatusCard(
    isVpnActive: Boolean,
    blockedCount: Int,
    vpnDuration: String = "00:00:00"
) {
    val context = LocalContext.current
    var isBlockVideo by remember { mutableStateOf(FirewallManager.isBlockVideo(context)) }
    val viewModel: FirewallViewModel = viewModel()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isVpnActive) "防火墙已启用" else "防火墙已禁用",
                style = MaterialTheme.typography.titleMedium,
                color = if (isVpnActive) Success else Color.Red,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "已选择 $blockedCount 个应用进行拦截",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray500
            )
            
            if (isVpnActive) {
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "运行时间: $vpnDuration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray500
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 屏蔽视频开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "屏蔽所有视频流量",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "拦截所有应用中的视频播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray500
                    )
                }
                
                com.example.adproject.ui.components.CustomSwitch(
                    checked = isBlockVideo,
                    onCheckedChange = { checked ->
                        isBlockVideo = checked
                        viewModel.updateVideoBlockStatus(context, checked)
                    },
                    useGreenTheme = true
                )
            }
        }
    }
}

@Composable
fun VpnPowerButton(
    isActive: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        label = "buttonScale"
    )
    
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (isActive) Success.copy(alpha = 0.1f) else AliBlue.copy(alpha = 0.1f))
            .border(
                width = 2.dp,
                color = if (isActive) Success else AliBlue,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = if (isActive) "停止防火墙" else "启动防火墙",
                tint = if (isActive) Success else AliBlue,
                modifier = Modifier.size(48.dp)
            )
            
            Text(
                text = if (isActive) "停止" else "启动",
                color = if (isActive) Success else AliBlue,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BlockedAppsList(
    apps: List<AppInfo>,
    onAddClick: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(apps) { app ->
            AppIconItem(app)
        }
        
        item {
            AddAppButton(onClick = onAddClick)
        }
    }
}

@Composable
fun AppIconItem(app: AppInfo) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Gray100),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun AddAppButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Gray200.copy(alpha = 0.5f))
                .border(1.dp, Gray200, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加应用",
                tint = Gray500,
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = "添加应用",
            style = MaterialTheme.typography.bodySmall,
            color = Gray500,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EmptyBlockedApps(onAddClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = "暂无拦截应用",
            style = MaterialTheme.typography.bodyLarge,
            color = Gray500
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = AliBlue
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = "添加")
            Spacer(modifier = Modifier.size(8.dp))
            Text("添加应用")
        }
    }
} 