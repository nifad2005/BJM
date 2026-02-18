package com.example.bjm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    val name: String,
    val description: String,
    val updatedAt: String,
    val fileSize: String
)

class UpdateManager(private val context: Context) {
    private val jsonUrl = "https://raw.githubusercontent.com/nifad2005/BOT-App-Store/main/apps.json"
    private val appDownloadUrl = "https://bot-holdings-bangladesh.vercel.app/apps/bjm"

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val response = URL(jsonUrl).readText()
            Log.d("UpdateManager", "Response from server: $response")
            val jsonArray = JSONArray(response)
            
            for (i in 0 until jsonArray.length()) {
                val appJson = jsonArray.getJSONObject(i)
                if (appJson.getString("slug") == "bjm") {
                    val latestVersion = appJson.getString("version")
                    val currentVersion = getCurrentVersion()
                    
                    if (isNewer(latestVersion, currentVersion)) {
                        return@withContext AppUpdate(
                            version = latestVersion,
                            downloadUrl = appDownloadUrl,
                            name = appJson.getString("name"),
                            description = appJson.getString("description"),
                            updatedAt = appJson.getString("updated_at"),
                            fileSize = appJson.getString("file_size")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Update check failed", e)
        }
        null
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        
        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
