package com.example.itv.domain.equip

import androidx.annotation.DrawableRes

/**
 * 코디 장착 부위 슬롯
 */
enum class EquipSlot(val label: String) {
    HAT("모자"),
    GLASSES("안경"),
    NOSE_MOUTH("코와 입"),
    CLOTHES("옷"),
    SHOES("신발");

    companion object {
        fun fromLabel(label: String): EquipSlot =
            values().firstOrNull { it.label == label } ?: HAT
    }
}

/**
 * 코디 아이템 모델
 */
data class EquipItem(
    val id: String,
    val name: String,
    val slot: EquipSlot,
    val description: String,
    @DrawableRes val iconRes: Int,
    val requiredLevel: Int = 1,
    val pricePoints: Long = 0L
)

/**
 * 코디 아이템 카탈로그 (전체 코디 아이템 목록)
 * 팀원과의 디자인/기획 협의 후 실제 아이템을 추가할 수 있도록 현재는 비워둔 상태입니다.
 */
object EquipCatalog {
    val allItems: List<EquipItem> = emptyList()

    fun findById(id: String): EquipItem? = allItems.firstOrNull { it.id == id }

    fun getItemsForSlot(slot: EquipSlot): List<EquipItem> =
        allItems.filter { it.slot == slot }
}
