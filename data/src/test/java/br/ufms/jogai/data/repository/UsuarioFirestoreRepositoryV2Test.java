package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.model.UsuarioDocument;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class UsuarioFirestoreRepositoryV2Test {

    private Firestore db;
    private UsuarioFirestoreRepositoryV2 repository;

    @BeforeEach
    void setUp() {
        db = FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-v2-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
                .build().getService();
        repository = new UsuarioFirestoreRepositoryV2(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void serializesRecordAndReadsItBackThroughFirestoreMapper() throws Exception {
        Usuario usuario = usuario("v2@example.com", "67996122801");
        Usuario created = await(repository.create(usuario));

        var snapshot = db.collection("usuarios").document(usuario.getId().toString()).get().get();
        assertEquals(usuario.getNome(), snapshot.getString("nome"));
        assertEquals(usuario.getEmail(), snapshot.getString("email"));

        UsuarioDocument document = snapshot.toObject(UsuarioDocument.class);
        assertNotNull(document);
        assertEquals(usuario.getId().toString(), document.id());
        assertEquals(usuario.getId(), created.getId());
        assertEquals(usuario.getDataNascimento(), document.toEntity().getDataNascimento());
        assertEquals(usuario.getDataCadastro(), document.toEntity().getDataCadastro());

        Usuario loaded = await(repository.get(usuario.getId())).orElseThrow();
        assertEquals(usuario.getEmail(), loaded.getEmail());
    }

    @Test
    void preservesUniqueEmailConstraint() throws Exception {
        await(repository.create(usuario("same@example.com", "67996122802")));
        UsuarioDuplicadoException error = assertInstanceOf(
                UsuarioDuplicadoException.class,
                usuarioException(assertThrows(Exception.class,
                        () -> await(repository.create(usuario("same@example.com", "67996122803")))))
        );
        assertEquals("email", error.getCampo());
    }

    @Test
    void sharesStringReservationsWithTheExistingRepository() throws Exception {
        UsuarioFirestoreRepository existingRepository = new UsuarioFirestoreRepository(db);
        await(existingRepository.create(usuario("shared@example.com", "67996122804")));

        UsuarioDuplicadoException error = assertInstanceOf(
                UsuarioDuplicadoException.class,
                usuarioException(assertThrows(Exception.class,
                        () -> await(repository.create(usuario("shared@example.com", "67996122805")))))
        );
        assertEquals("email", error.getCampo());
    }

    private static Usuario usuario(String email, String telefone) {
        return Usuario.create("Pessoa V2", email, telefone, LocalDate.of(1990, 1, 1));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(45, TimeUnit.SECONDS);
    }

    private static Throwable usuarioException(Throwable error) {
        while (error.getCause() != null && !(error instanceof UsuarioDuplicadoException)) {
            error = error.getCause();
        }
        return error;
    }
}
