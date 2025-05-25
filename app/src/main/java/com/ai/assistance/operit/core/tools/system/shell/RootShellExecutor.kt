package com.ai.assistance.operit.core.tools.system.shell

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 基于Root权限的Shell命令执行器 实现ROOT权限级别的命令执行 */
class RootShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val TAG = "RootShellExecutor"
        private var rootAvailable: Boolean? = null
    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ROOT

    override fun isAvailable(): Boolean {
        // 如果已经检查过，直接返回缓存结果
        if (rootAvailable != null) {
            return rootAvailable!!
        }

        // 检查常见的su二进制文件路径
        val suPaths =
                arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
                    "/sbin/.magisk/mirror/bin/su", "/sbin/.magisk/modules/magiskcore/bin/su",
                    "/data/adb/magisk/su", "/data/adb/magisk/bin/su", "/debug_ramdisk/su",)

        for (path in suPaths) {
            if (File(path).exists()) {
                try {
                    // 尝试执行一个简单的命令验证root权限
                    val process = Runtime.getRuntime().exec("su -c id")
                    val exitCode = process.waitFor()
                    val output = BufferedReader(InputStreamReader(process.inputStream)).readText()

                    rootAvailable = exitCode == 0 && output.contains("uid=0")
                    if (rootAvailable == true) {
                        Log.d(TAG, "Root access verified")
                        return true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking root access", e)
                }
            }
        }

        Log.d(TAG, "Root access not available")
        rootAvailable = false
        return false
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        val available = isAvailable()
        return if (available) {
            ShellExecutor.PermissionStatus.granted()
        } else {
            ShellExecutor.PermissionStatus.denied("Root access not available on this device")
        }
    }

    override fun initialize() {
        // 根权限执行器不需要特殊初始化
        // 但会检查root可用性
        isAvailable()
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // Root权限无法通过代码请求，只能提示用户
        val hasRoot = isAvailable()
        onResult(hasRoot)

        if (!hasRoot) {
            Log.d(TAG, "Cannot request root permission programmatically")
        }
    }

    /**
     * 检查并提取run-as包装中的实际命令
     * @param command 可能包含run-as的命令
     * @return 提取后的实际命令
     */
    private fun extractActualCommand(command: String): String {
        // 检查命令是否是run-as格式
        val runAsPattern = """run-as\s+(\S+)\s+sh\s+-c\s+['"](.+)['"]""".toRegex()
        val match = runAsPattern.find(command)

        return if (match != null) {
            // 提取内部命令
            val innerCommand = match.groupValues[2]
            Log.d(TAG, "Extracted inner command from run-as: $innerCommand")
            innerCommand
        } else {
            // 没有匹配到run-as格式，直接返回原命令
            command
        }
    }

    override suspend fun executeCommand(command: String): ShellExecutor.CommandResult =
            withContext(Dispatchers.IO) {
                val permStatus = hasPermission()
                if (!permStatus.granted) {
                    return@withContext ShellExecutor.CommandResult(false, "", permStatus.reason)
                }

                // 提取实际要执行的命令（如果是run-as包装的）
                val actualCommand = extractActualCommand(command)
                Log.d(TAG, "Executing root command: $actualCommand (original: $command)")

                try {
                    // 创建su进程
                    val process = Runtime.getRuntime().exec("su")

                    // 获取输入输出流
                    val stdin = OutputStreamWriter(process.outputStream)
                    val stdout = BufferedReader(InputStreamReader(process.inputStream))
                    val stderr = BufferedReader(InputStreamReader(process.errorStream))

                    // 写入命令并关闭输入流
                    stdin.write("$actualCommand\n")
                    stdin.write("echo 'EXITCODE:'\$?\n") // 获取退出码
                    stdin.write("exit\n")
                    stdin.flush()
                    stdin.close()

                    // 读取命令输出
                    val stdoutBuilder = StringBuilder()
                    val stderrBuilder = StringBuilder()

                    var line: String?
                    while (stdout.readLine().also { line = it } != null) {
                        if (line?.startsWith("EXITCODE:") == true) {
                            break
                        }
                        stdoutBuilder.append(line).append("\n")
                    }

                    // 获取退出码
                    val exitCodeStr = line?.substring("EXITCODE:".length)
                    val exitCode = exitCodeStr?.toIntOrNull() ?: -1

                    // 读取错误输出
                    while (stderr.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append("\n")
                    }

                    // 等待进程结束
                    process.waitFor()

                    // 关闭流
                    stdout.close()
                    stderr.close()

                    val stdoutResult = stdoutBuilder.toString().trim()
                    val stderrResult = stderrBuilder.toString().trim()

                    return@withContext ShellExecutor.CommandResult(
                            exitCode == 0,
                            stdoutResult,
                            stderrResult,
                            exitCode
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing root command", e)
                    return@withContext ShellExecutor.CommandResult(
                            false,
                            "",
                            "Error: ${e.message}",
                            -1
                    )
                }
            }
}
