# kotlinx.serialization ships its own rules; keep our @Serializable models' companions.
-keepclassmembers @kotlinx.serialization.Serializable class dev.mangavault.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Credential Manager / Google ID
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
