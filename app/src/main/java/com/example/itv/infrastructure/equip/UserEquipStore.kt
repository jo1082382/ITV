package com.example.itv.infrastructure.equip

import android.content.Context
import android.content.SharedPreferences
import com.example.itv.domain.equip.EquipCatalog
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UserEquipStore {
    private const val PREF_NAME = "itv_user_equip_store_v1"

    private fun slotKey(slot: EquipSlot) = "key_slot_${slot.name.lowercase()}"

    private val _equipState = MutableStateFlow<Map<EquipSlot, EquipItem?>>(emptyMap())
    val equipState: StateFlow<Map<EquipSlot, EquipItem?>> = _equipState.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getEquipped(context: Context): Map<EquipSlot, EquipItem?> {
        val p = prefs(context)
        val map = mutableMapOf<EquipSlot, EquipItem?>()
        for (slot in EquipSlot.values()) {
            val itemId = p.getString(slotKey(slot), null)
            map[slot] = itemId?.let { EquipCatalog.findById(it) }
        }
        _equipState.value = map
        return map
    }

    fun getItemForSlot(context: Context, slot: EquipSlot): EquipItem? {
        val p = prefs(context)
        val itemId = p.getString(slotKey(slot), null) ?: return null
        return EquipCatalog.findById(itemId)
    }

    fun equip(context: Context, item: EquipItem) {
        val p = prefs(context)
        p.edit().putString(slotKey(item.slot), item.id).apply()
        getEquipped(context)
    }

    fun unequip(context: Context, slot: EquipSlot) {
        val p = prefs(context)
        p.edit().remove(slotKey(slot)).apply()
        getEquipped(context)
    }

    fun toggleEquip(context: Context, item: EquipItem) {
        val current = getItemForSlot(context, item.slot)
        if (current?.id == item.id) {
            unequip(context, item.slot)
        } else {
            equip(context, item)
        }
    }

    fun isEquipped(context: Context, itemId: String): Boolean {
        val state = _equipState.value.takeIf { it.isNotEmpty() } ?: getEquipped(context)
        return state.values.any { it?.id == itemId }
    }

    fun restoreEquipped(
        context: Context,
        slotToItemId: Map<String, String>,
        mergeWithLocal: Boolean
    ) {
        val p = prefs(context)
        val editor = p.edit()
        if (!mergeWithLocal) {
            editor.clear()
        }
        for (slot in EquipSlot.values()) {
            val remoteItemId = slotToItemId[slot.name]
            if (remoteItemId != null && EquipCatalog.findById(remoteItemId) != null) {
                if (!mergeWithLocal || p.getString(slotKey(slot), null) == null) {
                    editor.putString(slotKey(slot), remoteItemId)
                }
            }
        }
        editor.commit()
        getEquipped(context)
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
        _equipState.value = emptyMap()
    }
}
