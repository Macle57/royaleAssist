package com.macle.royaleassist.ui

/** [n] with the device locale's thousands separators (was `"%,d".format(n)`). */
expect fun formatGrouped(n: Long): String

fun formatGrouped(n: Int): String = formatGrouped(n.toLong())

/** [x] with exactly [digits] decimals in the device locale (was `"%.<digits>f".format(x)`). */
expect fun formatDecimal(x: Double, digits: Int): String
