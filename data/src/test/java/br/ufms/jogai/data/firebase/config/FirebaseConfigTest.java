package br.ufms.jogai.data.firebase.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Uses synthetic resources, never real service account credentials or a live Firebase app. */
class FirebaseConfigTest {
    @TempDir Path directory;

    @Test
    void readsResourcePackagedInAJar() throws Exception {
        Path jar = directory.resolve("resources.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("synthetic-account.json"));
            output.write("synthetic-credentials".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
             var input = FirebaseConfig.openResource(loader, "synthetic-account.json")) {
            assertEquals("synthetic-credentials", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readsAnExplodedClasspathResource() throws Exception {
        Files.writeString(directory.resolve("synthetic-account.json"), "synthetic");
        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null);
             var input = FirebaseConfig.openResource(loader, "synthetic-account.json")) {
            assertEquals("synthetic", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void missingResourceHasAnActionableErrorWithoutInitializingFirebase() throws Exception {
        try (var loader = new URLClassLoader(new URL[0], null)) {
            var error = assertThrows(IllegalStateException.class, () -> FirebaseConfig.openResource(loader, "missing.json"));
            assertTrue(error.getMessage().contains("missing.json"));
        }
    }
}
