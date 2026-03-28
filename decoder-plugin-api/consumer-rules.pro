# Consumer ProGuard rules for decoder-plugin-api.
# Keep the plugin API so decoder implementations are not stripped.
-keep public interface com.roncatech.libvcat.decoder.VcatDecoderPlugin { *; }
-keep public interface com.roncatech.libvcat.decoder.NonStdDecoderStsdParser { *; }
-keep public class com.roncatech.libvcat.decoder.VideoConfiguration { *; }
-keep public class com.roncatech.libvcat.decoder.VideoConfiguration$Builder { *; }
