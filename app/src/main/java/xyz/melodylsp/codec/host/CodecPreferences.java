package xyz.melodylsp.codec.host;

/**
 * Bag of host-side Preference instances we own inside a single Codec_Block. Stored as
 * {@link Object} because the host APK ships a R8-minified androidx.preference where the
 * concrete class names are stripped; we never touch them through compile-time symbols.
 */
public final class CodecPreferences {

    public final Object category;
    public final Object codecDisplay;
    public final Object qualityOption;
    public final Object sampleRateOption;
    public final Object rememberToggle;

    public CodecPreferences(
            Object category,
            Object codecDisplay,
            Object qualityOption,
            Object sampleRateOption,
            Object rememberToggle) {
        this.category = category;
        this.codecDisplay = codecDisplay;
        this.qualityOption = qualityOption;
        this.sampleRateOption = sampleRateOption;
        this.rememberToggle = rememberToggle;
    }
}
