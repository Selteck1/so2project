package com.selteck.so2project.logic

data class ProfitResult(
    val recognizedStickerValue: Double,
    val adjustedStickerBonus: Double,
    val estimatedResalePrice: Double,
    val purchasePrice: Double,
    val estimatedProfit: Double,
    val shouldBuy: Boolean
)

object StickerProfitCalculator {
    /**
     * Follows the numeric example: only stickers >= 500 G count;
     * 20% of their catalog price is credited; apply a 20% resale
     * reduction, subtract 25 G costs, then compare profit with the threshold.
     * "Subtract 85%" conflicts with 1000 -> 200 in the example; 20% credit is used.
     */
    fun evaluate(
        cheapestBaseSkinPrice: Double,
        purchasePrice: Double,
        stickerMarketPrices: List<Double>,
        minimumStickerPrice: Double = 500.0,
        stickerCreditRate: Double = 0.20,
        resaleDiscountRate: Double = 0.20,
        fixedCost: Double = 25.0,
        minimumProfit: Double = 10.0
    ): ProfitResult {
        require(cheapestBaseSkinPrice >= 0.0 && purchasePrice >= 0.0)
        require(minimumStickerPrice >= 0.0)
        require(stickerCreditRate in 0.0..1.0 && resaleDiscountRate in 0.0..1.0)
        require(fixedCost >= 0.0)
        val qualifying = stickerMarketPrices.filter { it >= minimumStickerPrice && it >= 0.0 }
        val recognizedValue = qualifying.sum()
        val bonus = recognizedValue * stickerCreditRate
        val resale = (cheapestBaseSkinPrice + bonus) * (1.0 - resaleDiscountRate) - fixedCost
        val profit = resale - purchasePrice
        return ProfitResult(recognizedValue, bonus, resale, purchasePrice, profit, profit >= minimumProfit)
    }
}
