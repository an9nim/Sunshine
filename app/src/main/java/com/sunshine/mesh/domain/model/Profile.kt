package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.ids.PeerId

data class Profile(
    val peerId: PeerId,
    val nickname: String,
    val createdAt: Long,
)
