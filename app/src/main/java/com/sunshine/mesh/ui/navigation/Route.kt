package com.sunshine.mesh.ui.navigation

import com.sunshine.mesh.core.ids.PeerId

sealed class Route(val path: String) {
    data object Chats : Route("chats")
    data object Network : Route("network")
    data object Profile : Route("profile")
    data object Help : Route("help")
    data object Update : Route("update")

    data object Chat : Route("chat/{peerId}") {
        const val ARG_PEER_ID = "peerId"

        fun of(peerId: PeerId): String = "chat/${peerId.value}"
    }
}
