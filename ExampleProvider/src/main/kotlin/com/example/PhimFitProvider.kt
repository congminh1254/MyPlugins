package com.example

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.text.SimpleDateFormat
import java.util.*

@Suppress("DEPRECATION")
class PhimFitProvider : MainAPI() {
    override var mainUrl = "https://phimfit.com"
    override var name = "PhimFit"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    private var cachedRemotePrefix: String? = null
    private val loginMutex = Mutex()
    private var isLocallyLoggedIn = false

    private fun getPrefs(): android.content.SharedPreferences? {
        val ctx = ExamplePlugin.context ?: try {
            com.lagradost.cloudstream3.AcraApplication.context
        } catch (_: Throwable) {
            null
        }
        return ctx?.getSharedPreferences("PhimFitSettings", Context.MODE_PRIVATE)
    }

    suspend fun login(email: String, password: String): Boolean {
        System.err.println("PhimFit debug: Attempting login for $email...")
        try {
            val responseObj = app.post(
                "$mainUrl/auth/login",
                data = mapOf(
                    "email" to email,
                    "password" to password,
                    "currentUrl" to "/"
                ),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/auth/login",
                    "Origin" to mainUrl
                )
            )
            System.err.println("PhimFit debug: Login POST response code: ${responseObj.code}")
            
            // Check if login was successful by checking if we are still redirected to login
            val checkHtml = app.get(mainUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )).text
            
            val isSuccess = checkHtml.contains("canWatch:true")
            System.err.println("PhimFit debug: Login verification isSuccess=$isSuccess")
            if (isSuccess) {
                // Save to SharedPreferences
                val prefs = getPrefs()
                if (prefs != null) {
                    prefs.edit().apply {
                        putString("email", email)
                        putString("password", password)
                        apply()
                    }
                    System.err.println("PhimFit debug: Saved credentials to SharedPreferences")
                } else {
                    System.err.println("PhimFit debug: Failed to save credentials because SharedPreferences is null")
                }
                isLocallyLoggedIn = true
                return true
            }
        } catch (e: Exception) {
            System.err.println("PhimFit login error: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    suspend fun ensureLoggedIn(): Boolean {
        if (isLocallyLoggedIn) {
            System.err.println("PhimFit debug: Already locally logged in")
            return true
        }
        
        return loginMutex.withLock {
            if (isLocallyLoggedIn) return@withLock true
            
            // Check if we are already logged in (cookies might still be valid in NiceHttp's cookie jar)
            try {
                val checkHtml = app.get(mainUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )).text
                if (checkHtml.contains("canWatch:true")) {
                    System.err.println("PhimFit debug: Cookie is still valid. Active session found.")
                    isLocallyLoggedIn = true
                    return@withLock true
                } else {
                    System.err.println("PhimFit debug: Cookie is invalid or expired. No active session.")
                }
            } catch (e: Exception) {
                System.err.println("PhimFit debug: Exception checking active session: ${e.message}")
            }

            // Not logged in. Try to log in using saved credentials
            val prefs = getPrefs()
            if (prefs == null) {
                System.err.println("PhimFit debug: SharedPreferences is null! ExamplePlugin.context is: ${ExamplePlugin.context}")
            }
            val email = prefs?.getString("email", null)
            val password = prefs?.getString("password", null)
            
            System.err.println("PhimFit debug: Saved credentials - email=${email != null}, password=${password != null}")

            if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
                val success = login(email, password)
                System.err.println("PhimFit debug: Auto-login success=$success")
                if (success) {
                    isLocallyLoggedIn = true
                    return@withLock true
                }
            }
            false
        }
    }

    private suspend fun getRemotePrefix(): String {
        cachedRemotePrefix?.let { return it }
        val prefix = try {
            val html = app.get(mainUrl).text
            val appJsRegex = """/_app/immutable/entry/app\.[a-zA-Z0-9_-]+\.js""".toRegex()
            val appJsPath = appJsRegex.find(html)?.value ?: throw Exception("Failed to find app.js path")
            val appJsUrl = "$mainUrl$appJsPath"
            
            val appJsContent = app.get(appJsUrl).text
            val nodeIdRegex = """"/watch/\[fid\]"\s*:\s*\[\s*-(\d+)""".toRegex()
            val nodeIdMatch = nodeIdRegex.find(appJsContent)?.groupValues?.get(1) ?: throw Exception("Failed to find watch route node mapping")
            val nodeId = nodeIdMatch.toInt() - 1
            
            val nodeJsPattern = """nodes/$nodeId\.[a-zA-Z0-9_-]+\.js""".toRegex()
            val nodeJsPath = nodeJsPattern.find(appJsContent)?.value ?: throw Exception("Failed to find node JS filename")
            val nodeJsUrl = "$mainUrl/_app/immutable/$nodeJsPath"
            
            val nodeJsContent = app.get(nodeJsUrl).text
            val prefixRegex = """([a-zA-Z0-9_-]+)/getVideoSrc""".toRegex()
            val prefixMatch = prefixRegex.find(nodeJsContent)?.groupValues?.get(1) 
                ?: """([a-zA-Z0-9_-]+)/getEpisodes""".toRegex().find(nodeJsContent)?.groupValues?.get(1)
                ?: throw Exception("Failed to extract remote prefix")
                
            prefixMatch
        } catch (e: Exception) {
            System.err.println("PhimFit debug: Failed to extract remote prefix dynamically: ${e.message}")
            "3w1j30"
        }
        cachedRemotePrefix = prefix
        return prefix
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.encodeToString(
            input.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    // Fetch the list of all titles from the hourly cached API endpoint
    private suspend fun getAllTitles(): List<PhimFitTitle> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateStr = sdf.format(calendar.time)

        // Try local hour first, then current UTC hour
        val localCalendar = Calendar.getInstance()
        val localHour = localCalendar.get(Calendar.HOUR_OF_DAY)
        val utcHour = calendar.get(Calendar.HOUR_OF_DAY)

        val hoursToTry = listOf(localHour, utcHour, (utcHour - 1 + 24) % 24)
        for (hour in hoursToTry) {
            try {
                val url = "$mainUrl/api/titles/$dateStr-$hour.js"
                System.err.println("PhimFit debug: Fetching URL $url")
                val responseObj = app.get(
                    url, 
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                )
                val response = responseObj.text
                System.err.println("PhimFit debug: Response code: ${responseObj.code}, length: ${response.length}")
                
                if (responseObj.code != 200) {
                    System.err.println("PhimFit debug: failed response: ${response.take(300)}")
                    continue
                }
                
                // Response is a JSON array of arrays: [[fid, nameEn, nameVi, poster, translation, imdbId], ...]
                val titlesArray = parseJson<List<List<String?>>>(response)
                System.err.println("PhimFit debug: Successfully parsed ${titlesArray.size} titles")
                return titlesArray.mapNotNull { item ->
                    if (item.size >= 3) {
                        PhimFitTitle(
                            fid = item[0] ?: return@mapNotNull null,
                            nameEn = item[1] ?: "",
                            nameVi = item[2] ?: "",
                            tmdbPoster = item.getOrNull(3) ?: "",
                            translation = item.getOrNull(4) ?: "",
                            imdbId = item.getOrNull(5)
                        )
                    } else null
                }
            } catch (e: Exception) {
                System.err.println("PhimFit debug exception: ${e.message}")
                e.printStackTrace()
                // Keep trying next hour
            }
        }
        return emptyList()
    }

    private suspend fun fetchSectionTitles(url: String, isTop: Boolean = false, key: String = "titles"): List<SearchResponse> {
        try {
            val responseText = app.get(
                url, 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text
            val response = parseJson<SvelteKitResponse>(responseText)
            val nodeData = response.nodes?.lastOrNull()?.data ?: return emptyList()
            
            val resolved = resolveDevalue(nodeData, if (isTop) 1 else 0) as? Map<*, *> ?: return emptyList()
            val list = resolved[key] as? List<*> ?: return emptyList()
            
            return list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val fid = map["fid"] as? String ?: return@mapNotNull null
                val nameEn = map["nameEn"] as? String ?: ""
                val nameVi = map["nameVi"] as? String ?: ""
                val tmdbPoster = map["tmdbPoster"] as? String ?: ""
                
                newMovieSearchResponse(
                    name = if (nameVi.isNotEmpty()) nameVi else nameEn,
                    url = "$mainUrl/title/detail~$fid",
                    type = TvType.Movie
                ) {
                    this.posterUrl = if (tmdbPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w342$tmdbPoster" else null
                }
            }
        } catch (e: Exception) {
            System.err.println("PhimFit fetchSectionTitles error: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ensureLoggedIn()
        val lists = mutableListOf<HomePageList>()
        
        // 1. Mới cập nhật
        val allTitles = getAllTitles()
        if (allTitles.isNotEmpty()) {
            val homeItems = allTitles.take(40).map { title ->
                newMovieSearchResponse(
                    name = if (title.nameVi.isNotEmpty()) title.nameVi else title.nameEn,
                    url = "$mainUrl/title/detail~${title.fid}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = if (title.tmdbPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w342${title.tmdbPoster}" else null
                }
            }
            lists.add(HomePageList("Mới cập nhật", homeItems))
        }

        // 2. Phim thịnh hành trong ngày
        val trendingToday = fetchSectionTitles("$mainUrl/top/__data.json", isTop = true, key = "day")
        if (trendingToday.isNotEmpty()) {
            lists.add(HomePageList("Phim thịnh hành hôm nay", trendingToday))
        }

        // 3. Phim lẻ mới
        val newMovies = fetchSectionTitles("$mainUrl/browse/__data.json?type=movie", isTop = false, key = "titles")
        if (newMovies.isNotEmpty()) {
            lists.add(HomePageList("Phim lẻ mới nhất", newMovies))
        }

        // 4. Phim bộ mới
        val newShows = fetchSectionTitles("$mainUrl/browse/__data.json?type=show", isTop = false, key = "titles")
        if (newShows.isNotEmpty()) {
            lists.add(HomePageList("Phim bộ mới nhất", newShows))
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureLoggedIn()
        val allTitles = getAllTitles()
        if (allTitles.isEmpty()) return emptyList()

        val cleanQuery = query.lowercase().trim()
        return allTitles.filter { 
            it.nameEn.lowercase().contains(cleanQuery) || 
            it.nameVi.lowercase().contains(cleanQuery) ||
            it.imdbId?.lowercase() == cleanQuery
        }.map { title ->
            newMovieSearchResponse(
                name = if (title.nameVi.isNotEmpty()) title.nameVi else title.nameEn,
                url = "$mainUrl/title/detail~${title.fid}",
                type = TvType.Movie
            ) {
                this.posterUrl = if (title.tmdbPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w342${title.tmdbPoster}" else null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureLoggedIn()
        val fid = url.substringAfter("~")
        var dataUrl = "$mainUrl/title/detail~$fid/__data.json"
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        
        var responseText = app.get(dataUrl, headers = headers).text
        var response = parseJson<SvelteKitResponse>(responseText)
        
        if (response.type == "redirect" && response.location != null) {
            val redirectLoc = response.location!!
            dataUrl = "$mainUrl${redirectLoc}/__data.json"
            responseText = app.get(dataUrl, headers = headers).text
            response = parseJson<SvelteKitResponse>(responseText)
        }
        
        // SvelteKit returns node data at the end of nodes array
        val nodeData = response.nodes?.lastOrNull()?.data ?: return null
        val resolved = resolveDevalue(nodeData, 0) as? Map<*, *> ?: return null
        
        val titleMap = resolved["title"] as? Map<*, *> ?: return null
        val titleFid = titleMap["fid"] as? String ?: fid
        val nameEn = titleMap["nameEn"] as? String ?: ""
        val nameVi = titleMap["nameVi"] as? String ?: ""
        val intro = titleMap["intro"] as? String ?: ""
        val tmdbPoster = titleMap["tmdbPoster"] as? String ?: ""
        val type = titleMap["type"] as? String ?: "movie"
        val year = titleMap["publishDate"] as? String ?: ""
        
        val parsedYear = if (year.length >= 4) year.substring(0, 4).toIntOrNull() else null
        val cleanPoster = if (tmdbPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w342$tmdbPoster" else null
        val cleanName = if (nameVi.isNotEmpty()) nameVi else nameEn

        if (type == "show") {
            // It is a TV Series. Resolve seasons and episodes
            val seasons = resolved["seasons"] as? List<*> ?: emptyList<Any>()
            val episodesList = mutableListOf<Episode>()
            
            for (seasonObj in seasons) {
                val seasonMap = seasonObj as? Map<*, *> ?: continue
                val seasonFid = seasonMap["fid"] as? String ?: continue
                val seasonNum = (seasonMap["number"] as? Number)?.toInt() ?: 1
                
                // Fetch episodes of the season using remote call
                val payload = """["$seasonFid"]"""
                val base64Payload = base64UrlEncode(payload)
                val prefix = getRemotePrefix()
                val episodesUrl = "$mainUrl/_app/remote/$prefix/getEpisodes?payload=$base64Payload"
                
                val epHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "x-sveltekit-pathname" to "/title/detail~$fid",
                    "x-sveltekit-search" to ""
                )
                
                try {
                    val epResponse = app.get(episodesUrl, headers = epHeaders).text
                    val epResult = parseJson<SvelteKitRemoteResult>(epResponse)
                    
                    val epResultStr = epResult.result ?: continue
                    val epData = parseJson<List<Any?>>(epResultStr)
                    val resolvedResult = resolveDevalue(epData, 0) as? Map<*, *> ?: continue
                    val resolvedEpisodes = resolvedResult["episodes"] as? List<*> ?: continue
                    
                    for (epObj in resolvedEpisodes) {
                        val epMap = epObj as? Map<*, *> ?: continue
                        val epFid = epMap["fid"] as? String ?: continue
                        val epNum = (epMap["number"] as? Number)?.toInt() ?: 1
                        val epName = epMap["name"] as? String ?: "Tập $epNum"
                        
                        episodesList.add(
                            newEpisode(epFid) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                } catch (e: Exception) {
                    System.err.println("PhimFit debug: Failed to fetch episodes for season $seasonFid: ${e.message}")
                }
            }

            return newTvSeriesLoadResponse(
                name = cleanName,
                url = url,
                type = TvType.TvSeries,
                episodes = episodesList
            ) {
                this.posterUrl = cleanPoster
                this.year = parsedYear
                this.plot = intro
                this.showStatus = ShowStatus.Ongoing
            }
        } else {
            // It is a Movie
            return newMovieLoadResponse(
                name = cleanName,
                url = url,
                type = TvType.Movie,
                dataUrl = "$mainUrl/watch/$titleFid"
            ) {
                this.posterUrl = cleanPoster
                this.year = parsedYear
                this.plot = intro
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLoggedIn()
        // data is either a full URL (movie: https://phimfit.com/watch/vj64, episode: https://phimfit.com/mvuj) or a bare FID
        val cleanData = if (data.startsWith("http")) {
            data.trimEnd('/').substringAfterLast("/").substringBefore("~").substringBefore("?")
        } else {
            data
        }
        System.err.println("PhimFit debug loadLinks: data=$data cleanData=$cleanData")

        val payload = """[{"fid":1,"server":2},"$cleanData","1"]"""
        val base64Payload = base64UrlEncode(payload)
        val prefix = getRemotePrefix()
        val streamUrl = "$mainUrl/_app/remote/$prefix/getVideoSrc?payload=$base64Payload"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/120.0.0.0",
            "x-sveltekit-pathname" to "/watch/$cleanData",
            "x-sveltekit-search" to ""
        )

        try {
            val responseText = app.get(streamUrl, headers = headers).text
            val response = parseJson<SvelteKitRemoteResult>(responseText)

            val resultStr = response.result ?: return false
            val resultData = parseJson<List<Any?>>(resultStr)
            val resolvedResult = resolveDevalue(resultData, 0) as? Map<*, *> ?: return false
            val streamSrc = resolvedResult["src"] as? String ?: return false

            if (streamSrc.contains(".m3u8")) {
                // Fetch the m3u8 content and decode obfuscated CDN filenames
                val m3u8Content = app.get(streamSrc).text
                val decodedM3u8 = decodeM3u8Content(m3u8Content, streamSrc)

                val path = "/play_${cleanData}.m3u8"
                LocalHttpServer.register(path, decodedM3u8.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
                val localUrl = LocalHttpServer.url(path)

                System.err.println("PhimFit debug: Registered decoded m3u8, localUrl=$localUrl")
                System.err.println("PhimFit debug: First 500 chars of decoded m3u8: ${decodedM3u8.take(500)}")

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = localUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamSrc,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            // Fetch and load subtitles
            val subPayload = """["$cleanData"]"""
            val subBase64 = base64UrlEncode(subPayload)
            val subUrl = "$mainUrl/_app/remote/1odrich/getSubtitles?payload=$subBase64"
            
            try {
                val subResponseObj = app.get(subUrl, headers = headers)
                val subResponseText = subResponseObj.text
                if (subResponseObj.code == 200) {
                    val subResponse = parseJson<SvelteKitRemoteResult>(subResponseText)
                    val subResultStr = subResponse.result
                    if (subResultStr != null) {
                        val subResultData = parseJson<List<Any?>>(subResultStr)
                        val resolvedSubs = resolveDevalue(subResultData, 0) as? List<*>
                        if (resolvedSubs != null) {
                            for (subObj in resolvedSubs) {
                                val subMap = subObj as? Map<*, *> ?: continue
                                val subsceneId = subMap["subsceneId"]?.toString() ?: continue
                                val subLang = subMap["language"]?.toString() ?: "en"
                                val subFile = subMap["fileName"]?.toString() ?: continue
                                val rand = subMap["rand"]?.toString()
                                
                                val fileUrl = if (rand != null && rand.isNotEmpty() && rand != "null") {
                                    "$mainUrl/api/subtitle/${subsceneId}~${rand}/${subFile}"
                                } else {
                                    "$mainUrl/api/subtitle/${subsceneId}/${subFile}"
                                }
                                
                                try {
                                    val encryptedBase64 = app.get(fileUrl, headers = headers).text
                                    val decryptedText = decryptSubtitle(encryptedBase64, cleanData)
                                    if (decryptedText.isNotEmpty()) {
                                        val isAss = subFile.endsWith(".ass", ignoreCase = true)
                                        val ext = if (isAss) ".ass" else ".srt"
                                        val mime = if (isAss) "text/x-ssa" else "application/x-subrip"
                                        
                                        val subPath = "/sub_${subsceneId}_${subLang}${ext}"
                                        LocalHttpServer.register(subPath, decryptedText.toByteArray(Charsets.UTF_8), mime)
                                        val localSubUrl = LocalHttpServer.url(subPath)
                                        
                                        val langLabel = langMap[subLang] ?: subLang.uppercase()
                                        System.err.println("PhimFit debug: Registered subtitle language=$langLabel localSubUrl=$localSubUrl mime=$mime")
                                        subtitleCallback(
                                            SubtitleFile(
                                                lang = langLabel,
                                                url = localSubUrl
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    System.err.println("PhimFit debug: Failed to load subtitle $subsceneId: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("PhimFit debug: Failed to fetch subtitles list: ${e.message}")
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // --- M3U8 decoding helpers ---

    private fun proxyCdnUrl(url: String): String {
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val encodedReferer = java.net.URLEncoder.encode("https://phimfit.com/", "UTF-8")
        return LocalHttpServer.url("/proxy?url=$encodedUrl&referer=$encodedReferer")
    }

    private fun decodeM3u8Content(content: String, baseUrl: String): String {
        return content.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) {
                line
            } else if (line.startsWith("#")) {
                // Decode URI="..." values
                line.replace(Regex("""URI="([^"]+)"""")) { match ->
                    val raw = match.groupValues[1]
                    val abs = resolveAbsoluteUri(raw, baseUrl)
                    val decoded = decodeM3u8Url(abs)
                    "URI=\"${proxyCdnUrl(decoded)}\""
                }
            } else {
                // Segment URL line — decode it
                val abs = resolveAbsoluteUri(line.trim(), baseUrl)
                val decoded = decodeM3u8Url(abs)
                proxyCdnUrl(decoded)
            }
        }
    }

    private fun resolveAbsoluteUri(uri: String, baseUrl: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            java.net.URL(java.net.URL(baseUrl), uri).toString()
        } catch (_: Exception) {
            if (uri.startsWith('/')) {
                val proto = baseUrl.substringBefore("://")
                val host = baseUrl.substringAfter("://").substringBefore("/")
                "$proto://$host$uri"
            } else {
                "${baseUrl.substringBeforeLast('/').trimEnd('/')}/$uri"
            }
        }
    }

    private fun decodeM3u8Url(url: String): String {
        val queryIndex = url.indexOf('?')
        val fragmentIndex = url.indexOf('#')
        val cutIndex = listOf(queryIndex, fragmentIndex).filter { it >= 0 }.minOrNull() ?: url.length
        val basePart = url.substring(0, cutIndex)
        val suffix = url.substring(cutIndex)

        val lastSlashIndex = basePart.lastIndexOf('/')
        if (lastSlashIndex < 0) return decodeCdnFilename(basePart) + suffix

        val prefix = basePart.substring(0, lastSlashIndex + 1)
        val filename = basePart.substring(lastSlashIndex + 1)
        return prefix + decodeCdnFilename(filename) + suffix
    }

    private fun decodeCdnFilename(input: String): String {
        if (input.isBlank()) return input

        val dotIndex = input.lastIndexOf('.')
        val extension = if (dotIndex >= 0) input.substring(dotIndex + 1) else ""
        val nameWithoutExtension = if (dotIndex >= 0) input.substring(0, dotIndex) else input

        return try {
            val rotated = nameWithoutExtension.map { ch ->
                when (ch) {
                    in 'a'..'z' -> 'a' + ((ch - 'a' + 6) % 26)
                    in 'A'..'Z' -> 'A' + ((ch - 'A' + 6) % 26)
                    else -> ch
                }
            }.joinToString("")

            val decodedBytes = Base64.decode(rotated, Base64.DEFAULT)
            val decodedName = String(decodedBytes, Charsets.UTF_8)

            if (extension.isNotEmpty() && decodedName.isNotBlank()) {
                "$decodedName.$extension"
            } else {
                decodedName
            }
        } catch (_: Exception) {
            input
        }
    }

    // --- SvelteKit devalue resolver ---

    private fun resolveDevalue(data: List<Any?>, index: Int): Any? {
        if (index < 0 || index >= data.size) return null
        val element = data[index]
        if (element is Map<*, *>) {
            val resolved = mutableMapOf<String, Any?>()
            for ((key, value) in element) {
                if (value is Number && value !is Float && value !is Double) {
                    resolved[key as String] = resolveDevalue(data, value.toInt())
                } else if (value is Double && value == Math.floor(value) && !value.isInfinite()) {
                    resolved[key as String] = resolveDevalue(data, value.toInt())
                } else {
                    resolved[key as String] = value
                }
            }
            return resolved
        } else if (element is List<*>) {
            return element.map {
                if (it is Number && it !is Float && it !is Double) {
                    resolveDevalue(data, it.toInt())
                } else if (it is Double && it == Math.floor(it) && !it.isInfinite()) {
                    resolveDevalue(data, it.toInt())
                } else {
                    it
                }
            }
        }
        return element
    }

    private val langMap = mapOf(
        "vi" to "Vietnamese",
        "en" to "English",
        "fr" to "French",
        "es" to "Spanish",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese"
    )

    private fun rot19(input: String): String {
        return input.map { ch ->
            when (ch) {
                in 'a'..'z' -> 'a' + ((ch - 'a' + 19) % 26)
                in 'A'..'Z' -> 'A' + ((ch - 'A' + 19) % 26)
                else -> ch
            }
        }.joinToString("")
    }

    private fun decryptSubtitle(encryptedBase64: String, titleFid: String): String {
        return try {
            val pathString = "/watch/" + rot19(titleFid)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(pathString.toByteArray(Charsets.UTF_8))
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")

            val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val iv = encryptedBytes.copyOfRange(0, 12)
            val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("PhimFit decryptSubtitle error: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    // --- Data classes ---

    data class PhimFitTitle(
        val fid: String,
        val nameEn: String,
        val nameVi: String,
        val tmdbPoster: String,
        val translation: String,
        val imdbId: String?
    )

    data class SvelteKitResponse(
        val type: String?,
        val location: String?,
        val nodes: List<SvelteKitNode>?
    )

    data class SvelteKitNode(
        val type: String?,
        val data: List<Any?>?
    )

    data class SvelteKitRemoteResult(
        val type: String?,
        val result: String?
    )
}
