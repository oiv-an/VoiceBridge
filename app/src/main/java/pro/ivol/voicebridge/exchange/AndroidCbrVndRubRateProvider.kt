package pro.ivol.voicebridge.exchange

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Fetches only the fixed, public CBR daily-rate XML. No recognized, translated,
 * or user-entered text is included in the URL, headers, cache, or request body.
 */
class AndroidCbrVndRubRateProvider(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VndRubRateProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val refreshMutex = Mutex()

    override fun cachedRate(): VndRubRate {
        val currentPeriod = MorningRateCachePolicy.periodKey(System.currentTimeMillis())
        val cached = readStoredCache()
        return when {
            cached == null -> BundledCbrVndRate.rate
            CbrVndRateCacheCodec.isFreshForPeriod(cached, currentPeriod) ->
                cached.toPublicRate(VndRubRateSource.DAILY_CACHE, isStale = false)
            else -> cached.toPublicRate(VndRubRateSource.LAST_KNOWN_CACHE, isStale = true)
        }
    }

    override suspend fun latestRate(): Result<VndRubRate> = withContext(ioDispatcher) {
        try {
            Result.success(refreshMutex.withLock { resolveRate() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private suspend fun resolveRate(): VndRubRate {
        coroutineContext.ensureActive()
        val currentPeriod = MorningRateCachePolicy.periodKey(System.currentTimeMillis())
        val cached = readStoredCache()

        if (cached != null && CbrVndRateCacheCodec.isFreshForPeriod(cached, currentPeriod)) {
            return cached.toPublicRate(VndRubRateSource.DAILY_CACHE, isStale = false)
        }

        try {
            val parsed = fetchCurrentRate()
            // The response is a public quote fetched from a fixed URL. Keep a
            // verified response even if a superseded partial transcript cancels
            // its UI job, so the final transcript can reuse it immediately.
            // Use the completion time so a request crossing the 06:00 boundary
            // belongs to the new morning cache period.
            val fetchedAt = System.currentTimeMillis()
            val entry = CbrVndRateCacheEntry(
                parsedRate = parsed,
                fetchedAtEpochMillis = fetchedAt,
                fetchDay = MorningRateCachePolicy.periodKey(fetchedAt),
            )
            // A cache-write failure must not discard a valid official response.
            runCatching {
                preferences.edit()
                    .putString(CACHE_KEY, CbrVndRateCacheCodec.encode(entry))
                    .commit()
            }
            return entry.toPublicRate(VndRubRateSource.CBR_NETWORK, isStale = false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A verified saved or bundled quote is returned below. The UI marks
            // it as stale while a later conversion can retry the refresh.
        }

        if (cached != null) {
            return cached.toPublicRate(VndRubRateSource.LAST_KNOWN_CACHE, isStale = true)
        }
        return BundledCbrVndRate.rate
    }

    private fun readStoredCache(): CbrVndRateCacheEntry? = runCatching {
        CbrVndRateCacheCodec.decode(preferences.getString(CACHE_KEY, null))
    }.getOrNull()

    private fun fetchCurrentRate(): ParsedCbrVndRate {
        val connection = (URL(CBR_DAILY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = false
            setRequestProperty("Accept", "application/xml, text/xml")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("CBR returned HTTP $status.")
            }
            val declaredLength = connection.contentLength
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw IOException("The CBR response is unexpectedly large.")
            }
            val bytes = connection.inputStream.use(::readBounded)
            // XML_daily.asp is published as Windows-1251. Parsing from a Reader
            // also prevents an untrusted XML encoding declaration changing it.
            val xml = bytes.toString(WINDOWS_1251)
            CbrVndXmlParser.parse(xml)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw IOException("The CBR response is unexpectedly large.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun CbrVndRateCacheEntry.toPublicRate(
        source: VndRubRateSource,
        isStale: Boolean,
    ) = VndRubRate(
        nominalVnd = parsedRate.nominalVnd,
        valueRub = parsedRate.valueRub,
        nominalUsd = parsedRate.nominalUsd,
        usdValueRub = parsedRate.usdValueRub,
        nominalEur = parsedRate.nominalEur,
        eurValueRub = parsedRate.eurValueRub,
        nominalUah = parsedRate.nominalUah,
        uahValueRub = parsedRate.uahValueRub,
        cbrEffectiveDate = parsedRate.effectiveDate,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        source = source,
        isStale = isStale,
    )

    private companion object {
        const val CBR_DAILY_URL = "https://www.cbr.ru/scripts/XML_daily.asp"
        const val USER_AGENT =
            "VoiceBridge-Android/0.8.0 (morning-cached VND currency reference rates)"
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val PREFERENCES_NAME = "cbr_vnd_rub_rate"
        const val CACHE_KEY = "latest_verified_rates_v3"
        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }
}
