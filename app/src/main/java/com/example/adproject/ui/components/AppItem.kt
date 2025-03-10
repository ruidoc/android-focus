package com.example.adproject.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.adproject.model.AppInfo
import com.example.adproject.ui.theme.AliBlue
import com.example.adproject.ui.theme.Gray100
import com.example.adproject.ui.theme.Gray200
import com.example.adproject.ui.theme.Gray300
import com.example.adproject.ui.theme.Gray50
import com.example.adproject.ui.theme.TextSecondary

@Composable
fun AppItem(
    appInfo: AppInfo,
    onAppSelected: (AppInfo, Boolean) -> Unit
) {
    var isChecked by remember { mutableStateOf(appInfo.isBlocked) }
    val backgroundColor by animateColorAsState(
        targetValue = if (isChecked) Gray50 else Color.White,
        label = "backgroundColor"
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Gray100),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = appInfo.icon.toBitmap().asImageBitmap(),
                    contentDescription = appInfo.appName,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // 应用信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = appInfo.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = appInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // 复选框
            Checkbox(
                checked = isChecked,
                onCheckedChange = { checked ->
                    isChecked = checked
                    onAppSelected(appInfo, checked)
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = AliBlue
                )
            )
        }
        
        Divider(
            color = Gray200,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 80.dp)
        )
    }
} 