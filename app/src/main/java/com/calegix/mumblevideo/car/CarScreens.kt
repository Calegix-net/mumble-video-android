package com.calegix.mumblevideo.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.calegix.mumblevideo.R
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The car's one screen for the session: saved servers while disconnected, then the channel you are
 * in with who is talking, mute and deafen, and a way to another channel.
 */
class CarHomeScreen(
    carContext: CarContext,
    private val session: SessionController,
    servers: ServerRepository,
) : Screen(carContext) {
    private var home: CarHome = CarHome.Servers(emptyList())
    private var toldProblem: String? = null

    init {
        follow(carHomeFlow(session.core, servers.servers, session.currentServer)) { home = it }
    }

    override fun onGetTemplate(): Template = when (val state = home) {
        is CarHome.Servers -> serversTemplate(state)

        is CarHome.Connecting -> ListTemplate.Builder().setTitle(state.label).setLoading(true).build()

        is CarHome.OnPhone -> MessageTemplate.Builder(state.message)
            .setTitle(state.label)
            .setHeaderAction(Action.APP_ICON)
            .addAction(action("Disconnect") { session.disconnect() })
            .build()

        is CarHome.Connected -> connectedTemplate(state)
    }

    private fun serversTemplate(state: CarHome.Servers): Template {
        val list = ItemList.Builder().setNoItemsMessage("Add a server in the app on your phone")
        state.servers.forEach { server ->
            list.addItem(
                Row.Builder()
                    .setTitle(server.label.ifBlank { server.host })
                    .addText(serverDetail(server))
                    .setOnClickListener { launchAction { session.connect(server) } }
                    .build(),
            )
        }
        // Said once per refusal, not on every redraw.
        if (state.problem != null && state.problem != toldProblem) {
            CarToast.makeText(carContext, state.problem, CarToast.LENGTH_LONG).show()
        }
        toldProblem = state.problem
        return ListTemplate.Builder()
            .setTitle("Mumble Video")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }

    private fun connectedTemplate(state: CarHome.Connected): Template {
        val core = session.core
        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle("Change channel")
                .addText("You're in ${state.channel}")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CarChannelsScreen(carContext, session)) }
                .build(),
        )
        state.people.forEach { person ->
            val row = Row.Builder().setTitle(if (person.self) "${person.name} (you)" else person.name)
            person.status?.let(row::addText)
            row.setImage(personIcon(person), Row.IMAGE_TYPE_ICON)
            list.addItem(row.build())
        }
        list.addItem(
            Row.Builder()
                .setTitle("Disconnect")
                .setOnClickListener { launchAction { session.disconnect() } }
                .build(),
        )
        return ListTemplate.Builder()
            .setTitle("${state.channel} · ${state.label}")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .setActionStrip(
                // A list's strip takes one titled action; mute is the one wanted mid-drive, so it
                // gets the icon (struck through while muted) and deafen the words.
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(icon(if (state.selfMuted) R.drawable.ic_car_mic_off else R.drawable.ic_car_mic))
                            .setOnClickListener { launchAction { core.setSelfMuted(!state.selfMuted) } }
                            .build(),
                    )
                    .addAction(
                        action(if (state.selfDeafened) "Undeafen" else "Deafen") {
                            core.setSelfDeafened(!state.selfDeafened)
                        },
                    )
                    .build(),
            )
            .build()
    }

    private fun personIcon(person: CarPerson): CarIcon {
        val (drawable, tint) = when {
            person.talking -> R.drawable.ic_car_mic to CarColor.GREEN
            person.deafened || person.muted -> R.drawable.ic_car_mic_off to CarColor.RED
            else -> R.drawable.ic_car_mic to CarColor.DEFAULT
        }
        return CarIcon.Builder(IconCompat.createWithResource(carContext, drawable)).setTint(tint).build()
    }

    private fun icon(drawable: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, drawable)).build()

    private fun action(title: String, block: suspend () -> Unit) =
        Action.Builder().setTitle(title).setOnClickListener { launchAction(block) }.build()
}

/** Where to go: every channel, where you are and the busy ones first. A tap moves you and goes back. */
class CarChannelsScreen(carContext: CarContext, private val session: SessionController) : Screen(carContext) {
    private var channels: List<CarChannel> = emptyList()

    init {
        follow(carChannelsFlow(session.core)) { channels = it }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("No channels")
        channels.forEach { channel ->
            val row = Row.Builder().setTitle(channel.name)
            val detail = listOfNotNull(
                channel.path,
                when {
                    channel.here -> "You're here"
                    !channel.canEnter -> "Locked"
                    channel.users == 1 -> "1 person"
                    channel.users > 1 -> "${channel.users} people"
                    else -> null
                },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) row.addText(detail)
            if (!channel.here && channel.canEnter) {
                row.setOnClickListener {
                    launchAction {
                        session.core.joinChannel(channel.id)
                        screenManager.pop()
                    }
                }
            }
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setTitle("Channels")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * Keeps a screen's model current: each new value is stored and the template redrawn, at most about
 * once a second. Hosts limit how often a template may change, and talking flickers far faster.
 */
private fun <T> Screen.follow(source: Flow<T>, store: (T) -> Unit) {
    lifecycleScope.launch {
        source.conflate().collect {
            store(it)
            invalidate()
            delay(REDRAW_INTERVAL_MS)
        }
    }
}

/** Runs a session call from a click; failures become a car toast rather than a crash. */
private fun Screen.launchAction(block: suspend () -> Unit) {
    lifecycleScope.launch {
        try {
            block()
        } catch (e: MumbleCoreException) {
            CarToast.makeText(carContext, e.message ?: "That didn't work", CarToast.LENGTH_LONG).show()
        }
    }
}

private const val REDRAW_INTERVAL_MS = 1_000L

/** "skyn3t.lol · as nugg": the host when the label hides it, and the name you join as. */
internal fun serverDetail(server: SavedServer): String = listOfNotNull(
    server.host.takeIf { !it.equals(server.label, ignoreCase = true) },
    server.username.ifBlank { null }?.let { "as $it" },
).joinToString(" · ").ifEmpty { server.host }

private fun carHomeFlow(
    core: MumbleCore,
    servers: Flow<List<SavedServer>>,
    current: Flow<SavedServer?>,
): Flow<CarHome> =
    combine(
        combine(core.connectionState, core.serverState, core.talkStates, ::Triple),
        core.voiceState,
        servers,
        current,
    ) { (connection, server, talk), voice, saved, now -> CarModel.home(connection, server, talk, voice, saved, now) }
        .distinctUntilChanged()

private fun carChannelsFlow(core: MumbleCore): Flow<List<CarChannel>> =
    core.serverState.map(CarModel::channels).distinctUntilChanged()
