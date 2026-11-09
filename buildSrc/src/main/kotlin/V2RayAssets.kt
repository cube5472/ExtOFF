/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

import com.google.gson.JsonParser.parseReader
import com.google.gson.stream.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.Project
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// NextClave: the GitHub release CDN intermittently drops connections from
// hosted runners. The stock task used the default 10 s OkHttp timeouts with
// zero retries on the checksum/listing requests, so one transient
// SocketTimeoutException aborted the whole CI build minutes after the native
// build had already succeeded (seen 2026-11-09 on geoip.dat.sha256sum).
// Every request now goes through fetchTextWithRetry / downloadFileWithRetry:
// 4 attempts, linear backoff, IOException and HTTP 5xx are retried while 4xx
// fails fast; the checksum-verify loop below stays as the second safety net.
// The client gets explicit 30 s connect / 60 s read-write timeouts.

private const val DOWNLOAD_ATTEMPTS = 4
private const val DOWNLOAD_BACKOFF_MS = 3000L

private fun OkHttpClient.fetchTextWithRetry(url: String): String {
    var lastError: Exception? = null
    for (attempt in 1..DOWNLOAD_ATTEMPTS) {
        try {
            newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) return response.body!!.string().trim()
                if (response.code < 500) error("HTTP ${response.code} for $url")
                System.err.println("Attempt $attempt/$DOWNLOAD_ATTEMPTS: HTTP ${response.code} for $url")
                lastError = IOException("HTTP ${response.code} for $url")
            }
        } catch (e: IOException) {
            lastError = e
            System.err.println("Attempt $attempt/$DOWNLOAD_ATTEMPTS failed for $url: $e")
        }
        if (attempt < DOWNLOAD_ATTEMPTS) Thread.sleep(DOWNLOAD_BACKOFF_MS * attempt)
    }
    throw IllegalStateException("Download failed after $DOWNLOAD_ATTEMPTS attempts: $url", lastError)
}

private fun OkHttpClient.downloadFileWithRetry(url: String, target: File) {
    var lastError: Exception? = null
    for (attempt in 1..DOWNLOAD_ATTEMPTS) {
        try {
            newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code < 500) error("HTTP ${response.code} for $url")
                    System.err.println("Attempt $attempt/$DOWNLOAD_ATTEMPTS: HTTP ${response.code} for $url")
                    throw IOException("HTTP ${response.code} for $url")
                }
                response.body!!.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                return
            }
        } catch (e: IOException) {
            lastError = e
            System.err.println("Attempt $attempt/$DOWNLOAD_ATTEMPTS failed for $url: $e")
        }
        if (attempt < DOWNLOAD_ATTEMPTS) Thread.sleep(DOWNLOAD_BACKOFF_MS * attempt)
    }
    throw IllegalStateException("Download failed after $DOWNLOAD_ATTEMPTS attempts: $url", lastError)
}

fun Project.downloadAssets(update: Boolean = false) {
    val assetsDir = File(projectDir, "src/main/assets")
    val downloader = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val geoipVersionFile = File(assetsDir, "exclave-core/geoip.version.txt")
    var geoipVersion: String
    var geoipDat: String
    var geoipDatSha256sum: String
    if (update) {
        val geoipReleaseJson = downloader.fetchTextWithRetry("https://api.github.com/repos/v2fly/geoip/releases/latest")
        val jsonObject = parseReader(JsonReader(StringReader(geoipReleaseJson)))?.asJsonObject
        jsonObject ?: error("unable to list geoip release")
        geoipVersion = jsonObject.get("tag_name")?.asString
            ?: error("unable to list geoip release")
        val assets = jsonObject.getAsJsonArray("assets")
        geoipDat = assets.find { it?.asJsonObject?.get("name")?.asString == "geoip.dat" }
            ?.asJsonObject?.get("browser_download_url")?.asString
            ?: error("unable to list geoip release")
        geoipDatSha256sum = assets.find { it?.asJsonObject?.get("name")?.asString == "geoip.dat.sha256sum" }
            ?.asJsonObject?.get("browser_download_url")?.asString
            ?: error("unable to list geoip release")
    } else {
        geoipVersion = geoipVersionFile.readText()
        geoipDat = "https://github.com/v2fly/geoip/releases/download/${geoipVersion}/geoip.dat"
        geoipDatSha256sum = "https://github.com/v2fly/geoip/releases/download/${geoipVersion}/geoip.dat.sha256sum"
    }

    val geoipFile = File(assetsDir, "exclave-core/geoip.dat")

    println("Downloading $geoipDatSha256sum ...")
    val geoipChecksum = downloader.fetchTextWithRetry(geoipDatSha256sum).substringBefore(" ")

    var count = 0
    while (true) {
        count++

        println("Downloading $geoipDat ...")
        downloader.downloadFileWithRetry(geoipDat, geoipFile)

        val messageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        geoipFile.inputStream().use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                messageDigest.update(buffer, 0, bytesRead)
            }
        }

        val fileSha256 = messageDigest.digest().toHexString()
        if (fileSha256 != geoipChecksum.lowercase()) {
            System.err.println(
                "Error verifying ${geoipFile.name}: \nLocal: $fileSha256\nRemote: ${geoipChecksum.lowercase()}"
            )
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geoipVersionFile.writeText(geoipVersion)
        }

        break
    }

    val geositeVersionFile = File(assetsDir, "exclave-core/geosite.version.txt")
    var geositeVersion: String
    var geositeDat: String
    var geositeDatSha256sum: String
    if (update) {
        val geositeReleaseJson = downloader.fetchTextWithRetry("https://api.github.com/repos/v2fly/domain-list-community/releases/latest")
        val jsonObject = parseReader(JsonReader(StringReader(geositeReleaseJson)))?.asJsonObject
        jsonObject ?: error("unable to list geosite release")
        geositeVersion = jsonObject.get("tag_name")?.asString
            ?: error("unable to list geosite release")
        val assets = jsonObject.getAsJsonArray("assets")
        geositeDat = assets.find { it?.asJsonObject?.get("name")?.asString == "dlc.dat" }
            ?.asJsonObject?.get("browser_download_url")?.asString
            ?: error("unable to list geosite release")
        geositeDatSha256sum = assets.find { it?.asJsonObject?.get("name")?.asString == "dlc.dat.sha256sum" }
            ?.asJsonObject?.get("browser_download_url")?.asString
            ?: error("unable to list geosite release")
    } else {
        geositeVersion = geositeVersionFile.readText()
        geositeDat = "https://github.com/v2fly/domain-list-community/releases/download/${geositeVersion}/dlc.dat"
        geositeDatSha256sum = "https://github.com/v2fly/domain-list-community/releases/download/${geositeVersion}/dlc.dat.sha256sum"
    }

    val geositeFile = File(assetsDir, "exclave-core/geosite.dat")

    println("Downloading $geositeDatSha256sum ...")
    val geositeChecksum = downloader.fetchTextWithRetry(geositeDatSha256sum).substringBefore(" ")

    count = 0
    while (true) {
        count++

        println("Downloading $geositeDat ...")
        downloader.downloadFileWithRetry(geositeDat, geositeFile)

        val messageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        geositeFile.inputStream().use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                messageDigest.update(buffer, 0, bytesRead)
            }
        }

        val fileSha256 = messageDigest.digest().toHexString()
        if (fileSha256 != geositeChecksum.lowercase()) {
            System.err.println(
                "Error verifying ${geositeFile.name}: \nLocal: $fileSha256\nRemote: ${geositeChecksum.lowercase()}"
            )
            if (count > 3) error("Exit")
            System.err.println("Retrying...")
            continue
        }

        if (update) {
            geositeVersionFile.writeText(geositeVersion)
        }

        break
    }

}
