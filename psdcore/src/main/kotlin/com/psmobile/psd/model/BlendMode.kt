package com.psmobile.psd.model

/** Photoshop blend modes as stored in layer records (four-character keys). */
enum class BlendMode(val key: String, val label: String) {
    PASS_THROUGH("pass", "Pass Through"),
    NORMAL("norm", "Normal"),
    DISSOLVE("diss", "Dissolve"),
    DARKEN("dark", "Darken"),
    MULTIPLY("mul ", "Multiply"),
    COLOR_BURN("idiv", "Color Burn"),
    LINEAR_BURN("lbrn", "Linear Burn"),
    DARKER_COLOR("dkCl", "Darker Color"),
    LIGHTEN("lite", "Lighten"),
    SCREEN("scrn", "Screen"),
    COLOR_DODGE("div ", "Color Dodge"),
    LINEAR_DODGE("lddg", "Linear Dodge"),
    LIGHTER_COLOR("lgCl", "Lighter Color"),
    OVERLAY("over", "Overlay"),
    SOFT_LIGHT("sLit", "Soft Light"),
    HARD_LIGHT("hLit", "Hard Light"),
    VIVID_LIGHT("vLit", "Vivid Light"),
    LINEAR_LIGHT("lLit", "Linear Light"),
    PIN_LIGHT("pLit", "Pin Light"),
    HARD_MIX("hMix", "Hard Mix"),
    DIFFERENCE("diff", "Difference"),
    EXCLUSION("smud", "Exclusion"),
    SUBTRACT("fsub", "Subtract"),
    DIVIDE("fdiv", "Divide"),
    HUE("hue ", "Hue"),
    SATURATION("sat ", "Saturation"),
    COLOR("colr", "Color"),
    LUMINOSITY("lum ", "Luminosity");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun from(key: String): BlendMode = byKey[key] ?: NORMAL
    }
}
