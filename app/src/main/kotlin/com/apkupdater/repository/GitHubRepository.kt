package com.apkupdater.repository

import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import android.util.Log
import com.apkupdater.BuildConfig
import com.apkupdater.data.github.GitHubApps
import com.apkupdater.data.github.GitHubRelease
import com.apkupdater.data.github.GitHubReleaseAsset
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.GitHubService
import com.apkupdater.util.cleanVersion
import com.apkupdater.util.combine
import com.apkupdater.util.filterVersionTag
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.Scanner


class GitHubRepository(
    private val service: GitHubService,
    private val prefs: Prefs,
) {

    fun updates(apps: List<AppInstalled>) = flow {
        val checks = mutableListOf(selfCheck())

        GitHubApps.forEachIndexed { i, app ->
            if (i != 0) {
                apps.find { it.packageName == app.packageName }?.let {
                    checks.add(checkApp(apps, app.user, app.repo, app.packageName, it.version, app.extra))
                }
            }
        }

        checks.combine { all ->
            emit(all.flatMap { it })
        }.collect()
    }.catch {
        emit(emptyList())
        Log.e("GitHubRepository", "Error fetching releases.", it)
    }

    fun search(text: String) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()

        GitHubApps.forEach { app ->
            if (app.repo.contains(other = text, ignoreCase = true) 
                || app.user.contains(other = text, ignoreCase = true) 
                || app.packageName.contains(other = text, ignoreCase = true)) {
                checks.add(checkApp(null, app.user, app.repo, app.packageName, "?", null))
            }
        }

        if (checks.isEmpty()) {
            emit(Result.success(emptyList()))
        } else {
            checks.combine { all ->
                val r = all.flatMap { it }
                emit(Result.success(r))
            }.collect()
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("GitHubRepository", "Error searching.", it)
    }

    private fun selfCheck() = flow {
        val releases = service.getReleases("jaidenteli80", "apkupdater_revival").filter { filterPreRelease(it) }
        if (releases.isNotEmpty()) {
            val latestRelease = releases[0]
            val versions = getVersions(latestRelease.name.ifEmpty { latestRelease.tag_name })
            val apkAsset = findApkAssetArch(latestRelease.assets, null)
            val cleanTagName = cleanVersion(latestRelease.tag_name)

            val isNewer = try {
                Version(cleanTagName) > Version(BuildConfig.VERSION_NAME) || (versions.second > BuildConfig.VERSION_CODE.toLong())
            } catch (_: Exception) {
                false
            }

            if (isNewer && apkAsset.browser_download_url.isNotEmpty()) {
                emit(
                    listOf(
                        AppUpdate(
                            name = "APKUpdater Revival",
                            packageName = BuildConfig.APPLICATION_ID,
                            version = cleanTagName,
                            oldVersion = BuildConfig.VERSION_NAME,
                            versionCode = if (versions.second > 0) versions.second else 200L,
                            oldVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            source = GitHubSource,
                            link = Link.Url(apkAsset.browser_download_url, apkAsset.size),
                            whatsNew = latestRelease.body,
                        )
                    )
                )
            } else {
                emit(listOf())
            }
        } else {
            emit(listOf())
        }
    }.catch {
        emit(emptyList())
        Log.e("GitHubRepository", "Error checking self-update.", it)
    }

    private fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?
    ) = flow {
        val r = service.getReleases(user, repo)
        val releases = if (packageName == "com.apkupdater.ci") {
            r.filter { it.name.contains("CI-Release-3.x")}
        } else {
            r.asSequence()
                .filter { filterPreRelease(it) }
                .filter { findApkAsset(it.assets).isNotEmpty() }
                .toList()
        }

        if (releases.isNotEmpty() && (Version(filterVersionTag(releases[0].tag_name)) > Version(currentVersion))) {
            val app = apps?.getApp(packageName)
            emit(
                listOf(
                    AppUpdate(
                        name = repo,
                        packageName = packageName,
                        version = cleanVersion(releases[0].tag_name),
                        oldVersion = app?.version ?: "?",
                        versionCode = 0L,
                        oldVersionCode = app?.versionCode ?: 0L,
                        source = GitHubSource,
                        link = findApkAssetArch(releases[0].assets, extra).let { Link.Url(it.browser_download_url, it.size) },
                        whatsNew = releases[0].body,
                        iconUri = if (apps == null) releases[0].author.avatar_url.toUri() else Uri.EMPTY
                    )
                )
            )
        } else {
            emit(emptyList())
        }
    }.catch {
        emit(emptyList())
        Log.e("GitHubRepository", "Error fetching releases for $packageName.", it)
    }

    private fun getVersions(name: String) = runCatching {
        val scanner = Scanner(name)
        val version = scanner.next()
        val versionCode = scanner.next().trim('(', ')').toLong()
        Pair(version, versionCode)
    }.getOrDefault(Pair(name, 0L))

    private fun filterPreRelease(release: GitHubRelease): Boolean {
        val name = (release.name + " " + release.tag_name).lowercase()
        return when {
            prefs.ignorePreRelease.get() && (release.prerelease || name.contains("alpha") || name.contains("beta") || name.contains("rc") || name.contains("dev") || name.contains("preview")) -> false
            prefs.ignoreAlpha.get() && name.contains("alpha") -> false
            prefs.ignoreBeta.get() && name.contains("beta") -> false
            else -> true
        }
    }

    private fun findApkAsset(assets: List<GitHubReleaseAsset>) = assets
        .filter { it.browser_download_url.endsWith(suffix = ".apk", ignoreCase = true) }
        .maxByOrNull { it.size }
        ?.browser_download_url
        .orEmpty()

    private fun findApkAssetArch(
        assets: List<GitHubReleaseAsset>,
        extra: Regex?
    ): GitHubReleaseAsset {
        val apks = assets.asSequence()
            .filter { it.browser_download_url.endsWith(suffix = ".apk", ignoreCase = true) }
            .filter { filterExtra(it, extra) }
            .toList()

        when {
            apks.isEmpty() -> return GitHubReleaseAsset(0L, "")
            apks.size == 1 -> return apks.first()
            else -> {
                // Try to match exact arch
                Build.SUPPORTED_ABIS.forEach { arch ->
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains(arch, true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm64
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match x64
                if (Build.SUPPORTED_ABIS.contains("x86_64")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("x64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm
                if (Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm", true)) {
                            return apk
                        }
                    }
                }
                // If no match, return biggest apk in the hope it's universal
                return apks.maxByOrNull { it.size } ?: GitHubReleaseAsset(0L, "")
            }
        }
    }

    private fun filterExtra(asset: GitHubReleaseAsset, extra: Regex?) = when(extra) {
        null -> true
        else -> asset.browser_download_url.matches(extra)
    }

}
