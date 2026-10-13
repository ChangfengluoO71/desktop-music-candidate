package com.qing.hachimi.desktop.libmpv

internal object MpvEventDecoder {
    fun endReason(nativeReason: Int): MpvEndReason = when (nativeReason) {
        MpvEndReason.EOF.nativeValue -> MpvEndReason.EOF
        MpvEndReason.STOP.nativeValue -> MpvEndReason.STOP
        MpvEndReason.QUIT.nativeValue -> MpvEndReason.QUIT
        MpvEndReason.ERROR.nativeValue -> MpvEndReason.ERROR
        MpvEndReason.REDIRECT.nativeValue -> MpvEndReason.REDIRECT
        else -> MpvEndReason.UNKNOWN
    }
}
