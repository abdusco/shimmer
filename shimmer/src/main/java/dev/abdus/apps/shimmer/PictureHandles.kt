package dev.abdus.apps.shimmer

data class PictureHandles(
    val program: Int,
    val attribPosition: Int,
    val attribTexCoords: Int,
    val uniformMvpMatrix: Int,
    val uniformTexture: Int,
    val uniformAlpha: Int,
    val uniformDuotoneLight: Int,
    val uniformDuotoneDark: Int,
    val uniformDuotoneOpacity: Int,
    val uniformDuotoneBlendMode: Int,
    val uniformDimAmount: Int,
    val uniformGrainAmount: Int,
    val uniformGrainCount: Int,
    val uniformTouchPointCount: Int,
    val uniformTouchPoints: Int,
    val uniformTouchIntensities: Int,
    val uniformScreenSize: Int,
)
