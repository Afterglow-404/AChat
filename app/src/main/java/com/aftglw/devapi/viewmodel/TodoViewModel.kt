package com.aftglw.devapi.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

data class TodoItem(val id: String, val text: String, val done: Boolean = false)
data class TaskChain(val id: String, val title: String, val steps: List<TodoItem>)

class TodoViewModel(app: Application) : AndroidViewModel(app) {
    private val _items = MutableLiveData<List<TodoItem>>(load())
    val items: LiveData<List<TodoItem>> = _items

    fun refresh() { _items.value = load() }

    private fun load(): List<TodoItem> {
        val prefs = getApplication<Application>().getSharedPreferences("todo_tasks", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("tasks", "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TodoItem(
                id = obj.optString("id", ""),
                text = obj.optString("text", ""),
                done = obj.optBoolean("done", false)
            )
        }.sortedByDescending { it.done }
    }

    private fun save(items: List<TodoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().apply {
            put("id", it.id); put("text", it.text); put("done", it.done)
        })}
        getApplication<Application>().getSharedPreferences("todo_tasks", android.content.Context.MODE_PRIVATE)
            .edit().putString("tasks", arr.toString()).apply()
    }

    fun add(text: String) {
        val list = load().toMutableList()
        val id = System.currentTimeMillis().toString()
        list.add(0, TodoItem(id, text))
        save(list); refresh()
    }

    fun toggle(id: String) {
        val prefs = getApplication<Application>().getSharedPreferences("todo_tasks", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("tasks", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == id) {
                obj.put("done", !obj.optBoolean("done", false))
            }
        }
        prefs.edit().putString("tasks", arr.toString()).apply()
        refresh()
    }

    fun delete(id: String) {
        val list = load().toMutableList()
        list.removeAll { it.id == id }
        save(list); refresh()
    }

    fun clearDone() {
        val list = load().toMutableList()
        list.removeAll { it.done }
        save(list); refresh()
    }


    fun loadChains(): List<TaskChain> {
        val json = getApplication<Application>().getSharedPreferences("todo_tasks", android.content.Context.MODE_PRIVATE)
            .getString("task_chains", "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).map { j ->
                val s = stepsArr.getJSONObject(j)
                TodoItem(j.toString(), s.optString("text", ""), s.optBoolean("done", false))
            }
            TaskChain(obj.optString("id", ""), obj.optString("title", ""), steps)
        }
    }

    fun addChain(title: String, stepTexts: List<String>) {
        val chains = loadChains().toMutableList()
        val id = System.currentTimeMillis().toString()
        val steps = stepTexts.mapIndexed { i, text -> TodoItem(i.toString(), text) }
        chains.add(TaskChain(id, title, steps))
        saveChains(chains)
    }

    fun toggleChainStep(chainId: String, stepIdx: Int) {
        val chains = loadChains().toMutableList()
        val idx = chains.indexOfFirst { it.id == chainId }
        if (idx == -1) return
        val chain = chains[idx]
        val steps = chain.steps.toMutableList()
        val step = steps[stepIdx]
        val prevDone = stepIdx == 0 || steps[stepIdx - 1].done
        if (!prevDone) return
        steps[stepIdx] = step.copy(done = !step.done)
        chains[idx] = chain.copy(steps = steps)
        saveChains(chains)
    }

    fun deleteChain(id: String) {
        val chains = loadChains().toMutableList()
        chains.removeAll { it.id == id }
        saveChains(chains)
    }

    private fun saveChains(chains: List<TaskChain>) {
        val arr = JSONArray()
        chains.forEach { chain ->
            arr.put(JSONObject().apply {
                put("id", chain.id); put("title", chain.title)
                put("steps", JSONArray().apply {
                    chain.steps.forEach { s ->
                        put(JSONObject().apply {
                            put("text", s.text); put("done", s.done)
                        })
                    }
                })
            })
        }
        getApplication<Application>().getSharedPreferences("todo_tasks", android.content.Context.MODE_PRIVATE)
            .edit().putString("task_chains", arr.toString()).apply()
    }
}
