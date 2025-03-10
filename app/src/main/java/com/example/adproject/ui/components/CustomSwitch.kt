package com.example.adproject.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.adproject.ui.theme.AliBlue
import com.example.adproject.ui.theme.Gray200
import com.example.adproject.ui.theme.TencentGreen

@Composable
fun CustomSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useGreenTheme: Boolean = false
) {
    val thumbColor = when {
        !enabled -> Gray200
        checked -> if (useGreenTheme) TencentGreen else AliBlue
        else -> Color.White
    }
    
    val trackColor = when {
        !enabled -> Gray200.copy(alpha = 0.6f)
        checked -> if (useGreenTheme) TencentGreen.copy(alpha = 0.5f) else AliBlue.copy(alpha = 0.5f)
        else -> Gray200
    }
    
    val thumbPosition by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        label = "thumbPosition"
    )
    
    Box(
        modifier = modifier
            .width(52.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onCheckedChange(!checked)
            }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = (20 * thumbPosition).dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .fillMaxSize()
        )
    }
} 