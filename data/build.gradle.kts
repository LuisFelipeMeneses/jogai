plugins {
    id("jogai.java-library-conventions")
}

dependencies {
    implementation(project(":domain"))

    implementation("com.google.cloud:google-cloud-firestore:3.48.0")
    implementation("com.google.firebase:firebase-admin:9.11.0")
    // Firestore currently resolves Protobuf 4.33.6, which triggers JDK 25's
    // Unsafe deprecation warning. Protobuf 4.36.2 avoids Unsafe for standard Java code.
    implementation(libs.protobuf.java)

    // The runnable data-layer example needs an SLF4J 2.x provider at runtime.
    runtimeOnly(libs.slf4j.simple)

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaExec>().configureEach {
    // gRPC Netty uses native libraries; opt in explicitly on JDK 25+.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
