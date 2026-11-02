# SnapGPT release shrinker rules.
# Room, Compose and kotlinx.serialization (tree API only) ship their own consumer rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.serialization.**
