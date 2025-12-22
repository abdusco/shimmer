package dev.abdus.apps.shimmer

import android.graphics.Color

data class DuotonePreset(
    val name: String,
    val lightColor: Int,
    val darkColor: Int
)

private fun hex(color: String) = Color.parseColor(color)

val DUOTONE_PRESETS = listOf(
    DuotonePreset("Steel Sand", hex("#FFC8A3"), hex("#071440")),
    DuotonePreset("Rose Cream", hex("#FFC8A3"), hex("#331B46")),

DuotonePreset("Vintage Port", hex("#FFD2A2"), hex("#6A051F")),
    DuotonePreset("Fading Ember", hex("#FFEDC2"), hex("#B10D0E")),
    DuotonePreset("Lapis Cradle", hex("#E4B6B9"), hex("#0B2A6B")),
    DuotonePreset("Shadow Flare", hex("#D3485D"), hex("#05106A")),
    DuotonePreset("Witching Hour", hex("#78FBD8"), hex("#571731")),
    DuotonePreset("Tropical Freeze", hex("#8EE3FF"), hex("#A91C93")),
    DuotonePreset("Cobra Skin", hex("#E8C670"), hex("#450035")),
    DuotonePreset("Volcanic Ash", hex("#FF6600"), hex("#181818")),
    DuotonePreset("Royal Velvet", hex("#FFD700"), hex("#4B0082")),
    DuotonePreset("Cosmic Dust", hex("#FFA07A"), hex("#191970")),
    DuotonePreset("Polar Ember", hex("#B0FFDF"), hex("#6C110C")),
    DuotonePreset("Citron Mirage", hex("#F6FF7C"), hex("#2A1E80")),
    DuotonePreset("Arctic Flame", hex("#DAFFFF"), hex("#6B0303")),
    DuotonePreset("Northern Ledger", hex("#FFD700"), hex("#003366")),
    DuotonePreset("Heritage Silk", hex("#F5F5DC"), hex("#800020")),
    DuotonePreset("Festival Bloom", hex("#FFFF00"), hex("#FF1493")),
    DuotonePreset("Meadow Lantern", hex("#F5E6D3"), hex("#87AE73")),
    DuotonePreset("Blush Mist", hex("#E8E8E8"), hex("#D4A5A5")),
    DuotonePreset("Twilight Reserve", hex("#F7E7CE"), hex("#6A5ACD")),
    DuotonePreset("Summit Flame", hex("#FF8C00"), hex("#4169E1")),

    DuotonePreset("Emerald Blaze", hex("#FF7800"), hex("#004948")),
    DuotonePreset("Mystic Iris", hex("#CFE020"), hex("#290A59")),
    DuotonePreset("Midnight Ember", hex("#EC6F32"), hex("#111C29")),
    DuotonePreset("Indigo Sunset", hex("#EB8F7C"), hex("#2F2967")),
    DuotonePreset("Slate Blossom", hex("#FBB0B0"), hex("#384E54")),
    DuotonePreset("Celestial Lemon", hex("#FFE615"), hex("#2A3A49")),
    DuotonePreset("Violet Honey", hex("#FBE881"), hex("#5F083F")),
    DuotonePreset("Olive Ember", hex("#FF9548"), hex("#3C4938")),
    DuotonePreset("Rosewater Chill", hex("#AEE7E7"), hex("#D9496E")),
    DuotonePreset("Amethyst Tide", hex("#2AF9D0"), hex("#6A38AF")),
    DuotonePreset("Royal Pulse", hex("#F10B52"), hex("#0D0554")),
    DuotonePreset("Plum Spark", hex("#FF3556"), hex("#52336E")),
    DuotonePreset("Azure Flame", hex("#F07D0F"), hex("#002874")),
    DuotonePreset("Crimson Dusk", hex("#FEDA79"), hex("#E81C17")),
    DuotonePreset("Sapphire Blush", hex("#FF507D"), hex("#042658")),
    DuotonePreset("Molten Rose", hex("#FFB0A1"), hex("#7C0F01")),
    DuotonePreset("Obsidian Silk", hex("#D2BB8F"), hex("#2A1C33")),
    DuotonePreset("Magenta Surf", hex("#4BE3CF"), hex("#A80680")),
    DuotonePreset("Navy Apricot", hex("#FFA97F"), hex("#212C62")),
    DuotonePreset("Violet Sand", hex("#D7C27B"), hex("#281E4D")),
    DuotonePreset("Electric Orchid", hex("#86F8D2"), hex("#580690")),
    DuotonePreset("Berry Sun", hex("#F9DC3A"), hex("#4D142D")),
    DuotonePreset("Mystic Tangerine", hex("#FF9A3A"), hex("#290A59")),
)