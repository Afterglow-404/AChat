package com.aftglw.devapi.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.aftglw.devapi.model.DiscoverItem
import com.aftglw.devapi.R

class DiscoverViewModel : ViewModel() {
    private val _items = MutableLiveData<List<DiscoverItem>>(sampleItems())
    val items: LiveData<List<DiscoverItem>> = _items

    private fun sampleItems() = listOf(
        DiscoverItem("1", "朋友圈", R.drawable.ic_moments),
        DiscoverItem("2", "喵神谕", R.drawable.ic_cat_fortune),
        DiscoverItem("3", "喵神の试炼", R.drawable.ic_shake),
        DiscoverItem("4", "Too-Do", R.drawable.ic_discover),
        DiscoverItem("8", "人设工坊", R.drawable.ic_settings)
    )
}
