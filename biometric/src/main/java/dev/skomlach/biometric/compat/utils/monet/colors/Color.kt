package  dev.skomlach.biometric.compat.utils.monet.colors

interface Color {
    // All colors should have a conversion path to linear sRGB
    fun toLinearSrgb(): LinearSrgb
}
