# ---------------------------------------------------------------------------
# R8 em modo release. O app de 2017 tinha `minifyEnabled false` — ou seja,
# nenhum encolhimento, nenhuma ofuscação e todo o código de debug no APK.
# ---------------------------------------------------------------------------

# HiveMQ carrega provedores via reflexão (Netty, RxJava). Sem estas regras, o
# release compila e quebra em runtime — o pior tipo de falha.
-keepnames class io.netty.** { *; }
-keep class io.netty.channel.socket.nio.** { *; }
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**

# kotlinx.serialization: os serializadores são gerados e resolvidos por nome.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.raulsousa.pulso.** {
    *** Companion;
}
-keepclasseswithmembers class com.raulsousa.pulso.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Modelos de domínio serializados para o DataStore.
-keep class com.raulsousa.pulso.domain.** { *; }
-keep class com.raulsousa.pulso.mqtt.BrokerProfile { *; }

# Mantém as linhas nos stack traces (e mapeia o resto).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
