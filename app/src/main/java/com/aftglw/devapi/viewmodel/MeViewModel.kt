package com.aftglw.devapi.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.aftglw.devapi.model.MeItem
import com.aftglw.devapi.R

class MeViewModel(app: Application) : AndroidViewModel(app) {
    private val _items = MutableLiveData<List<Any>>(buildItems())
    val items: LiveData<List<Any>> = _items

    fun refresh() { _items.value = buildItems() }

    private fun buildItems(): List<Any> {
        val prefs = getApplication<Application>().getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        val name = prefs.getString("profile_name", "Android Dev") ?: "Android Dev"
        val wechatId = prefs.getString("profile_wechat_id", "个人签名: Hello Wisp") ?: "个人签名: Hello Wisp"
        val color = prefs.getString("profile_color", "#07C160") ?: "#07C160"
        val avatar = prefs.getString("profile_avatar_uri", "") ?: ""
        return listOf(
            "$name|$wechatId|$color|$avatar",
            Unit,
            MeItem("pay", "支付", R.drawable.ic_wallet),
            Unit,
            MeItem("favorites", "收藏", R.drawable.ic_favorites),
            MeItem("album", "相册", R.drawable.ic_album),
            MeItem("cards", "卡包", R.drawable.ic_cards),
            MeItem("emoji", "表情", R.drawable.ic_emoji),
            Unit,
            MeItem("settings", "设置", R.drawable.ic_settings)
        )
    }
}
