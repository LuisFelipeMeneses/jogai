package br.ufms.jogai.data.firebase.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Lazily initializes the application-owned Firestore client from a classpath resource. */
public final class FirebaseConfig {
    private static final String SERVICE_ACCOUNT_PATH = "serviceAccountKey.json";

    private FirebaseConfig() {}

    private static final class Holder {
        private static final Firestore FIRESTORE = initializeFirestore();
    }

    private static Firestore initializeFirestore() {
        try (InputStream serviceAccount = openResource(FirebaseConfig.class.getClassLoader(), SERVICE_ACCOUNT_PATH)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
            FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
            FirebaseApp app = FirebaseApp.initializeApp(options);
            return FirestoreClient.getFirestore(app);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to initialize Firestore from " + SERVICE_ACCOUNT_PATH, error);
        }
    }

    // Streams support both exploded class directories and resources packaged inside JAR files.
    static InputStream openResource(ClassLoader loader, String path) {
        InputStream resource = Objects.requireNonNull(loader).getResourceAsStream(Objects.requireNonNull(path));
        if (resource == null) throw new IllegalStateException("Classpath resource not found: " + path);
        return resource;
    }

    /** @return the lazily initialized shared client; the application owns its shutdown */
    public static Firestore getFirestore() {
        return Holder.FIRESTORE;
    }
}
