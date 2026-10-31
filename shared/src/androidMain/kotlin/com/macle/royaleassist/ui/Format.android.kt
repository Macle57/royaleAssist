package com.macle.royaleassist.ui

actual fun formatGrouped(n: Long): String = "%,d".format(n)

actual fun formatDecimal(x: Double, digits: Int): String = "%.${digits}f".format(x)
