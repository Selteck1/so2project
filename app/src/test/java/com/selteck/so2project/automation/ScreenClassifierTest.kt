package com.selteck.so2project.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenClassifierTest {
    @Test
    fun recognizesTopUpPageAsLowBalance() {
        val result = ScreenClassifier.classify(
            "Недостаточно 127475G. Пожалуйста, пополните баланс"
        )
        assertEquals(ScreenKind.LOW_BALANCE, result.kind)
    }

    @Test
    fun lowBalanceTakesPriorityOverPurchaseDialog() {
        val result = ScreenClassifier.classify(
            "Покупка. Подтвердите покупку данного предмета за 127475.00 G. Недостаточно средств"
        )
        assertEquals(ScreenKind.LOW_BALANCE, result.kind)
        assertEquals(listOf(127475.00), result.prices)
    }

    @Test
    fun parsesGoldPricesWithCommaAndDotSeparators() {
        val result = ScreenClassifier.classify("Сделка 255,12 G и другая 256.03 G")
        assertEquals(listOf(255.12, 256.03), result.prices)
        assertTrue(result.kind == ScreenKind.UNKNOWN)
    }
}
