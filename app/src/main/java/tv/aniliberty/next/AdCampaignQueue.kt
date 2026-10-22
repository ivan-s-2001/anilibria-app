package tv.aniliberty.next

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded waterfall; errors before the first frame may advance to another provider. */
internal class AdCampaignQueue(
    private val campaigns: List<VastCampaign>,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    timeoutMs: Long = 30000,
) {
    private val deadline = now() + timeoutMs
    private var index = 0
    var started = false
        private set

    val remainingMs: Long
        get() = (deadline - now()).coerceAtLeast(0)

    fun next(): VastCampaign? {
        if (started || remainingMs == 0L) return null
        return campaigns.getOrNull(index++)
    }

    fun markStarted() {
        started = true
    }
}

internal suspend fun awaitAdCampaigns(request: Deferred<List<VastCampaign>>): List<VastCampaign> =
    withTimeoutOrNull(8000) { request.await() }.orEmpty()
