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
    val uniformDuotoneOpacity: Int
)
