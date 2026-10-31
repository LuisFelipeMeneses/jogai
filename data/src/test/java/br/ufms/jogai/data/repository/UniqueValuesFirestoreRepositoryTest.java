package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.DuplicateUniqueValueException;
import br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static br.ufms.jogai.data.firebase.UniqueValuesFirestoreRepository.COLLECTION_PATH;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class UniqueValuesFirestoreRepositoryTest {

    private Firestore db;
    private UniqueValuesFirestoreRepository valores;

    @BeforeEach
    void configurar() {
        db = FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
                .build().getService();
        valores = new UniqueValuesFirestoreRepository(db);
    }

    @AfterEach
    void fechar() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void valoresIguaisEmCamposOuEntidadesDiferentesNaoColidem() throws Exception {
        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "igual", "1");
            reservas.reserve("usuario", "telefone", "igual", "2");
            reservas.reserve("empresa", "email", "igual", "3");
            reservas.reserve("a_b", "c", "igual", "4");
            reservas.reserve("a", "b_c", "igual", "5");
            reservas.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);
        assertEquals(5, db.collection(COLLECTION_PATH).get().get().size());
    }

    @Test
    void detectaConflitoEntreReservasAindaNaoGravadas() throws Exception {
        var resultado = db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.reserve("usuario", "email", "a@example.com", "2");
            reservas.apply();
            return null;
        });
        ExecutionException erro = assertThrows(ExecutionException.class,
                () -> resultado.get(45, TimeUnit.SECONDS));
        Throwable causa = erro;
        while (causa.getCause() != null) causa = causa.getCause();
        assertInstanceOf(DuplicateUniqueValueException.class, causa);
        assertEquals(0, db.collection(COLLECTION_PATH).get().get().size());
    }

    @Test
    void naoLiberaReservaDeOutroProprietario() throws Exception {
        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);
        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.release("usuario", "email", "a@example.com", "2");
            reservas.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);
        var reservas = db.collection(COLLECTION_PATH).get().get().getDocuments();
        assertEquals(1, reservas.size());
        assertEquals("1", reservas.getFirst().getString("entidadeId"));
    }

    @Test
    void ultimaAlteracaoDaReservaPrevaleceNaMesmaOperacao() throws Exception {
        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);

        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.release("usuario", "email", "a@example.com", "1");
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.reserve("usuario", "telefone", "123", "1");
            reservas.release("usuario", "telefone", "123", "1");
            reservas.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);

        var reservas = db.collection(COLLECTION_PATH).get().get().getDocuments();
        assertEquals(1, reservas.size());
        assertEquals("email", reservas.getFirst().getString("campo"));
        assertEquals("1", reservas.getFirst().getString("entidadeId"));
    }

    @Test
    void transferenciaRequerLiberacaoDoProprietarioEIgnoraLiberacaoAntigaRepetida() throws Exception {
        db.runTransaction(transaction -> {
            var operation = valores.inTransaction(transaction);
            operation.reserve("usuario", "email", "a@example.com", "1");
            operation.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);
        var unauthorized = db.runTransaction(transaction -> {
            var operation = valores.inTransaction(transaction);
            operation.release("usuario", "email", "a@example.com", "other");
            operation.reserve("usuario", "email", "a@example.com", "2");
            operation.apply();
            return null;
        });
        assertThrows(ExecutionException.class, () -> unauthorized.get(45, TimeUnit.SECONDS));
        db.runTransaction(transaction -> {
            var operation = valores.inTransaction(transaction);
            operation.release("usuario", "email", "a@example.com", "1");
            operation.reserve("usuario", "email", "a@example.com", "2");
            // This stale release must not erase the pending new owner's claim.
            operation.release("usuario", "email", "a@example.com", "1");
            assertThrows(DuplicateUniqueValueException.class,
                    () -> operation.reserve("usuario", "email", "a@example.com", "3"));
            operation.apply();
            return null;
        }).get(45, TimeUnit.SECONDS);
        var reservations = db.collection(COLLECTION_PATH).get().get().getDocuments();
        assertEquals(1, reservations.size());
        assertEquals("2", reservations.getFirst().getString("entidadeId"));
    }

    @Test
    void bloqueiaLeiturasOuNovaAplicacaoDepoisDeAgendarEscritas() throws Exception {
        db.runTransaction(transaction -> {
            var reservas = valores.inTransaction(transaction);
            reservas.reserve("usuario", "email", "a@example.com", "1");
            reservas.apply();
            assertThrows(IllegalStateException.class,
                    () -> reservas.reserve("usuario", "telefone", "123", "1"));
            assertThrows(IllegalStateException.class, reservas::apply);
            return null;
        }).get(45, TimeUnit.SECONDS);
        assertEquals(1, db.collection(COLLECTION_PATH).get().get().size());
    }
}
