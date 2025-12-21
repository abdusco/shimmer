package dev.abdus.apps.shimmer.gl

data class ShaderHandles(
    val program: Int,
    val attribPosition: Int,
    val attribTexCoords: Int,
    val uniformMvpMatrix: Int,
    val uniformTexture0: Int,
    val uniformTexture1: Int,
    val uniformBlurMix: Int,
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
    val uniformAspectRatio: Int,
    val uniformTime: Int,
)
