-repackageclasses ''
-allowaccessmodification

-keep class io.nekohasekai.sagernet.** { *;}
-keep class com.github.exclavenetwork.exclave.core.app.observatory.** { *; }

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

# NextClave: ServiceButton tints the coal-theme FAB border through package-private
# Material internals (Material 1.14 removed the FAB stroke API entirely). The
# members below are reached via reflection in ServiceButton.init, so R8 must
# keep their names even in the obfuscated legacy release build.
-keep class com.google.android.material.floatingactionbutton.FloatingActionButton {
    com.google.android.material.floatingactionbutton.FloatingActionButtonImpl impl;
}
-keep class com.google.android.material.floatingactionbutton.FloatingActionButtonImpl {
    com.google.android.material.floatingactionbutton.BorderDrawable borderDrawable;
}
-keep class com.google.android.material.floatingactionbutton.BorderDrawable {
    void setBorderTint(android.content.res.ColorStateList);
}

# coreLibraryDesugaring: j$ runtime classes are merged at the L8 stage; keep R8
# from aborting if a pre-desugared input (e.g. a rebuilt AAR) references one.
-dontwarn j$.util.DesugarCollections

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
