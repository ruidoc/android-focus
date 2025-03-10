package com.example.adproject.model

import android.graphics.drawable.Drawable

/**
 * 应用信息数据类
 * @param packageName 应用包名
 * @param appName 应用名称
 * @param icon 应用图标
 * @param isBlocked 是否被防火墙拦截
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var isBlocked: Boolean = false
) 