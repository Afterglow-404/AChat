package com.aftglw.devapi.model

class ChatItem(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    var unreadCount: Int,
    val avatarColor: Int,
    val pinned: Boolean = false,
    val persona: String = "",
    val avatarUri: String = ""
)
