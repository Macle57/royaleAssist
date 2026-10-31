package com.macle.royaleassist.ui

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp

actual fun formatGrouped(n: Long): String {
    val f = NSNumberFormatter()
    f.numberStyle = NSNumberFormatterDecimalStyle
    f.usesGroupingSeparator = true
    return f.stringFromNumber(NSNumber(longLong = n)) ?: n.toString()
}

actual fun formatDecimal(x: Double, digits: Int): String {
    val f = NSNumberFormatter()
    f.numberStyle = NSNumberFormatterDecimalStyle
    f.usesGroupingSeparator = false
    f.minimumFractionDigits = digits.toULong()
    f.maximumFractionDigits = digits.toULong()
    f.roundingMode = NSNumberFormatterRoundHalfUp
    return f.stringFromNumber(NSNumber(double = x)) ?: x.toString()
}
