package com.macle.royaleassist.text

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfkd(s: String): String = (s as NSString).decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping
