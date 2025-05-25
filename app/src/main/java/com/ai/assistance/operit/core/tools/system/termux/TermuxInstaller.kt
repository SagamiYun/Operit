package com.ai.assistance.operit.core.tools.system.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 用于管理内置Termux应用的安装 */
class TermuxInstaller {
    companion object {
        private const val TAG = "TermuxInstaller"
        private const val TERMUX_ARM_APK_FILENAME = "termux_debug_arm64.apk"
        private const val TERMUX_X86_APK_FILENAME = "termux_debug_x86_64.apk"
        private const val TERMUX_PACKAGE_NAME = "com.termux"

        // 状态变更监听器
        private val stateChangeListeners = mutableListOf<() -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        /** 添加状态变更监听器 */
        fun addStateChangeListener(listener: () -> Unit) {
            synchronized(stateChangeListeners) {
                if (!stateChangeListeners.contains(listener)) {
                    stateChangeListeners.add(listener)
                }
            }
        }

        /** 移除状态变更监听器 */
        fun removeStateChangeListener(listener: () -> Unit) {
            synchronized(stateChangeListeners) { stateChangeListeners.remove(listener) }
        }

        /** 通知状态变化 */
        private fun notifyStateChanged() {
            mainHandler.post {
                synchronized(stateChangeListeners) { stateChangeListeners.forEach { it.invoke() } }
            }
        }

        /** 重置安装状态缓存 */
        fun resetInstallCache() {
            // 此方法保留以向后兼容，但不再需要缓存
            notifyStateChanged()
        }

        /** 检查Termux是否已安装 */
        fun isTermuxInstalled(context: Context): Boolean {
            // 直接执行实际检查，不使用缓存
            val isInstalled =
                    try {
                        context.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
                        true
                    } catch (e: PackageManager.NameNotFoundException) {
                        false
                    }

            return isInstalled
        }

        /** 从assets目录复制Termux APK到应用私有目录 */
        private fun extractApkFromAssets(context: Context): File? {
            val appropriateTermuxApk = getAppropriateTermuxApk(getDeviceArchitecture(context))
            val apkFile = File(context.cacheDir, appropriateTermuxApk)

            try {
                context.assets.open(appropriateTermuxApk).use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                return apkFile
            } catch (e: IOException) {
                Log.e(TAG, "Failed to extract Termux APK from assets", e)
                return null
            }
        }

        private fun getAppropriateTermuxApk(architecture: String): String {
            return when (architecture) {
                "arm64-v8a" -> TERMUX_ARM_APK_FILENAME
                "x86" -> TERMUX_X86_APK_FILENAME
                // "armeabi-v7a" -> "termux-arm.apk"
                // "x86_64" -> "termux-x86_64.apk"
                else -> TERMUX_ARM_APK_FILENAME
            }
        }

        private fun getDeviceArchitecture(context: Context): String{
            // 首选方法：使用Android API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return Build.SUPPORTED_ABIS[0]
            }

            // 备选方法：使用系统命令
            return try {
                val processBuilder = ProcessBuilder("getprop", "ro.product.cpu.abi")
                val process = processBuilder.start()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val cpuAbi = reader.readLine() ?: "Unknown"
                process.waitFor()
                reader.close()
                cpuAbi
            } catch (e: Exception) {
                // 如果都失败了，尝试使用已弃用但仍可用的API
                Build.CPU_ABI
            }
        }

        /** 安装内置的Termux APK */
        fun installBundledTermux(context: Context): Boolean {
            // 检查是否已经安装了Termux
            if (isTermuxInstalled(context)) {
                return true // 已安装则直接返回成功
            }

            try {
                // 从assets目录提取APK
                val apkFile = extractApkFromAssets(context) ?: return false

                // 生成APK的URI，考虑文件提供者权限
                val apkUri =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    apkFile
                            )
                        } else {
                            Uri.fromFile(apkFile)
                        }

                // 创建安装意图
                val installIntent =
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }

                // 启动安装界面
                context.startActivity(installIntent)

                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install bundled Termux", e)
                return false
            }
        }

        /** 打开Termux应用 */
        fun openTermux(context: Context): Boolean {
            if (!isTermuxInstalled(context)) {
                return false
            }

            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Termux", e)
                false
            }
        }
    }

}