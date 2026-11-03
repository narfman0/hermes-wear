package dev.narfman0.hermeswear

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A tile cannot hold a conversation (no scrolling, no text input), so this one shows
 * the last reply and opens the app. "Ask" opens it straight into voice input.
 */
class HermesTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val ctx = this@HermesTileService
            val layout = tileLayout(ctx, requestParams, Prefs(ctx))
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    companion object {
        private const val RESOURCES_VERSION = "1"

        /** Ask the system to re-render the tile, e.g. after a reply arrives. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(HermesTileService::class.java)
        }

        internal fun tileLayout(
            context: Context,
            request: RequestBuilders.TileRequest,
            prefs: Prefs,
        ): LayoutElementBuilders.LayoutElement {
            val body = when {
                !prefs.configured -> "Open Hermes to set up the server."
                prefs.lastReply.isNotBlank() -> TileText.preview(prefs.lastReply)
                else -> "Tap Ask to talk to Hermes."
            }
            val openApp = launch(context, voice = false)
            val ask = launch(context, voice = prefs.configured)
            return materialScope(context, request.deviceConfiguration) {
                primaryLayout(
                    titleSlot = { text("Hermes".layoutString) },
                    mainSlot = {
                        text(
                            body.layoutString,
                            maxLines = 4,
                            typography = Typography.BODY_MEDIUM,
                            modifier = androidx.wear.protolayout.modifiers.LayoutModifier.clickable(
                                openApp, "open",
                            ),
                        )
                    },
                    bottomSlot = {
                        textEdgeButton(
                            onClick = androidx.wear.protolayout.modifiers.clickable(ask, "ask"),
                            labelContent = { text((if (prefs.configured) "Ask" else "Set up").layoutString) },
                        )
                    },
                )
            }
        }

        private fun launch(context: Context, voice: Boolean): ActionBuilders.LaunchAction =
            ActionBuilders.launchAction(
                ComponentName(context, MainActivity::class.java),
                if (voice) mapOf(MainActivity.EXTRA_VOICE to ActionBuilders.booleanExtra(true)) else emptyMap(),
            )
    }
}

/** Text helpers for the tile, kept free of Android types so they can be unit tested. */
object TileText {
    /** First few hundred characters, markdown stripped, on one line. */
    fun preview(reply: String, max: Int = 160): String {
        val flat = reply
            .replace(Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL), " [code] ")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_`#>]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }
}
