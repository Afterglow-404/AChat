package com.example.wechatclone.model

class Message @JvmOverloads constructor(
    val content: String,
    val time: String = "",
    val type: Int = TYPE_TIMESTAMP,
    val avatarColor: Int = 0
) {
    companion object {
        const val TYPE_INCOMING = 0
        const val TYPE_OUTGOING = 1
        const val TYPE_TIMESTAMP = 2
    }
}
