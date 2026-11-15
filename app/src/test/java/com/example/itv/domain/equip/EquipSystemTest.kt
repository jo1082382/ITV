package com.example.itv.domain.equip

import com.example.itv.R
import org.junit.Assert.*
import org.junit.Test

class EquipSystemTest {

    @Test
    fun catalogIsEmptyByDefaultUntilTeamFinalizesItems() {
        assertTrue(EquipCatalog.allItems.isEmpty())
        assertNull(EquipCatalog.findById("hat_cap"))
        assertTrue(EquipCatalog.getItemsForSlot(EquipSlot.HAT).isEmpty())
        assertTrue(EquipCatalog.getItemsForSlot(EquipSlot.GLASSES).isEmpty())
        assertTrue(EquipCatalog.getItemsForSlot(EquipSlot.NOSE_MOUTH).isEmpty())
        assertTrue(EquipCatalog.getItemsForSlot(EquipSlot.CLOTHES).isEmpty())
        assertTrue(EquipCatalog.getItemsForSlot(EquipSlot.SHOES).isEmpty())
    }

    @Test
    fun slotFromLabelWorksCorrectly() {
        assertEquals(EquipSlot.HAT, EquipSlot.fromLabel("모자"))
        assertEquals(EquipSlot.GLASSES, EquipSlot.fromLabel("안경"))
        assertEquals(EquipSlot.NOSE_MOUTH, EquipSlot.fromLabel("코와 입"))
        assertEquals(EquipSlot.CLOTHES, EquipSlot.fromLabel("옷"))
        assertEquals(EquipSlot.SHOES, EquipSlot.fromLabel("신발"))
    }

    @Test
    fun gallopAnimationFramesAllHaveValidResourceIds() {
        val frames = intArrayOf(
            R.drawable.itv_gallop_front_00,
            R.drawable.itv_gallop_front_01,
            R.drawable.itv_gallop_front_02,
            R.drawable.itv_gallop_front_03,
            R.drawable.itv_gallop_front_04,
            R.drawable.itv_gallop_front_05,
            R.drawable.itv_gallop_front_06
        )
        assertEquals(7, frames.size)
        assertTrue(frames.all { it != 0 })
    }
}
