package com.macle.royaleassist.text

import java.text.Normalizer

actual fun normalizeNfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

actual fun normalizeNfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
