package com.example.itv.infrastructure.observation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAppTrackerFinancialTest {

    @Test
    fun identifiesKoreanBankingAndFinancialPackages() {
        // KB국민은행 / KB스타뱅킹
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbstar.kbbank"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbstar.starpush"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbstar.liivm"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbcard.cxh.app"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbsec.mts.mgmts"))

        // 케이뱅크 / K뱅크
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kbankwith.smartbank"))

        // 신한은행, 우리은행, 하나은행, 기업은행, 농협
        assertTrue(GeneralAppTracker.isFinancialPackage("com.shinhan.sbanking"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.wooribank.smart.npib"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.hanabank.ebk.channel.android.hananbank"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.ibk.smartbanking"))
        assertTrue(GeneralAppTracker.isFinancialPackage("nh.smart.banking"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.nonghyup.nhsmartbanking"))

        // 카카오뱅크, 카카오페이, 토스
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kakaobank.channel"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.kakaopay.app"))
        assertTrue(GeneralAppTracker.isFinancialPackage("viva.republica"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.viva.republica"))

        // 앱 이름 기반 탐지
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "KB스타뱅킹"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "국민은행"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "K스타뱅크"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "신한SOL뱅크"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "우리WON뱅킹"))
        assertTrue(GeneralAppTracker.isFinancialPackage("com.some.custom.pkg", "토스증권"))

        // 일반 앱은 false
        assertFalse(GeneralAppTracker.isFinancialPackage("com.google.android.youtube"))
        assertFalse(GeneralAppTracker.isFinancialPackage("com.instagram.android"))
        assertFalse(GeneralAppTracker.isFinancialPackage("com.android.chrome"))
    }

    @Test
    fun financialPackagesAlwaysDeferredOrBlocked() {
        assertTrue(GeneralAppTracker.isInterventionDeferredOrBlocked("com.kbstar.kbbank"))
        assertTrue(GeneralAppTracker.isInterventionDeferredOrBlocked("com.kbankwith.smartbank"))
        assertTrue(GeneralAppTracker.isInterventionDeferredOrBlocked("com.shinhan.sbanking"))
        assertTrue(GeneralAppTracker.isInterventionDeferredOrBlocked("viva.republica"))
    }
}
