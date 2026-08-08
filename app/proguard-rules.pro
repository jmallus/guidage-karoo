# Les modèles karoo-ext sont sérialisés par kotlinx.serialization côté bibliothèque.
-keep class io.hammerhead.karooext.models.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
