package com.selteck.so2project.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerProfitCalculatorTest {
    @Test
    fun suppliedExampleProduces15GoldProfit() {
        val result = StickerProfitCalculator.evaluate(
            cheapestBaseSkinPrice = 100.0,
            purchasePrice = 200.0,
            stickerMarketPrices = listOf(50.0, 50.0, 50.0, 1000.0)
        )
        assertEquals(200.0, result.adjustedStickerBonus, 0.0001)
        assertEquals(215.0, result.estimatedResalePrice, 0.0001)
        assertEquals(15.0, result.estimatedProfit, 0.0001)
        assertTrue(result.shouldBuy)
    }
}
