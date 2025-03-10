package com.example.adproject.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.adproject.model.AppInfo

/**
 * 应用工具类
 */
object AppUtils {

    /**
     * 获取已安装的应用列表
     * @param context 上下文
     * @param includeSystemApps 是否包含系统应用
     * @return 应用列表
     */
    fun getInstalledApps(context: Context, includeSystemApps: Boolean = false): List<AppInfo> {
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val appInfoList = mutableListOf<AppInfo>()

        for (appInfo in installedApps) {
            // 过滤系统应用
            if (!includeSystemApps && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue
            }

            // 过滤自身应用
            if (appInfo.packageName == context.packageName) {
                continue
            }

            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val icon = packageManager.getApplicationIcon(appInfo)

            appInfoList.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    icon = icon
                )
            )
        }

        // 按应用名称排序
        return appInfoList.sortedBy { it.appName }
    }
} 