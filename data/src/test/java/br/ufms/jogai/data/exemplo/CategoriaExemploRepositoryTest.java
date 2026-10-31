package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.data.repository.DuplicateUniqueValueException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class CategoriaExemploRepositoryTest {

    private Firestore db;
    private CategoriaExemploRepository repository;

    @BeforeEach
    void configurar() {
        db = FirestoreOptions.newBuilder()
                .setProjectId("demo-jogai-" + UUID.randomUUID().toString().substring(0, 8))
                .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
                .build().getService();
        repository = new CategoriaExemploRepository(db);
    }

    @AfterEach
    void fechar() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void cadastroEAtualizacaoRejeitamCodigoDuplicadoPreservandoDados() throws Exception {
        CategoriaExemplo a = categoria("EST");
        CategoriaExemplo b = categoria("FAM");
        aguardar(repository.create(a));
        aguardar(repository.create(b));
        conflito("codigo", repository.create(categoria(" est ")));
        conflito("id", repository.create(a));
        b.setCodigo("EST");
        conflito("codigo", repository.save(b));
        assertEquals("FAM", aguardar(repository.get(b.getId())).orElseThrow().getCodigo());
        assertEquals(2, db.collection("valores_unicos").get().get().size());
        conflito("codigo", repository.create(categoria("FAM")));
    }

    @Test
    void alteraCodigoLivreMantemOProprioValorELiberaAnterior() throws Exception {
        CategoriaExemplo a = categoria("EST");
        aguardar(repository.create(a));
        a.setCodigo("COOP");
        aguardar(repository.save(a));
        aguardar(repository.save(a));
        assertEquals("COOP", aguardar(repository.get(a.getId())).orElseThrow().getCodigo());
        aguardar(repository.create(categoria("EST")));
        conflito("codigo", repository.create(categoria("coop")));
        assertEquals(2, db.collection("valores_unicos").get().get().size());
    }

    @Test
    void exclusaoPermiteReutilizarCodigo() throws Exception {
        CategoriaExemplo a = categoria("EST");
        aguardar(repository.create(a));
        aguardar(repository.delete(a.getId()));
        aguardar(repository.delete(a.getId()));
        assertEquals(0, db.collection("valores_unicos").get().get().size());
        aguardar(repository.create(categoria("EST")));
    }

    @Test
    void atualizacoesConcorrentesNaoDuplicamCodigoNemDeixamReservasOrfas() throws Exception {
        CategoriaExemplo a = categoria("EST");
        CategoriaExemplo b = categoria("FAM");
        aguardar(repository.create(a));
        aguardar(repository.create(b));
        a.setCodigo("COOP");
        b.setCodigo("COOP");
        var primeira = repository.save(a).handle((entidade, erro) -> erro);
        var segunda = repository.save(b).handle((entidade, erro) -> erro);
        Throwable erroA = aguardar(primeira);
        Throwable erroB = aguardar(segunda);
        assertNotEquals(erroA == null, erroB == null);
        assertInstanceOf(DuplicateUniqueValueException.class, causa(erroA != null ? erroA : erroB));
        var categorias = aguardar(repository.getAll());
        assertEquals(1, categorias.stream().filter(c -> c.getCodigo().equals("COOP")).count());
        assertEquals(2, db.collection("valores_unicos").get().get().size());
        for (var categoria : categorias) conflito("codigo", repository.create(categoria(categoria.getCodigo())));
    }

    @Test
    void inicializacaoProtegeDocumentosPreexistentesTambemNaNovaEntidade() throws Exception {
        db.collection("categorias_exemplo").document(UUID.randomUUID().toString())
                .set(Map.of("nome", "Estratégia", "codigo", "est")).get();
        conflito("codigo", repository.create(categoria("EST")));
        assertEquals(0, db.collection("valores_unicos").get().get().size());
        aguardar(repository.create(categoria("FAM")));
        assertEquals(2, db.collection("valores_unicos").get().get().size());
    }

    private static CategoriaExemplo categoria(String codigo) {
        return new CategoriaExemplo(UUID.randomUUID(), "Jogos de tabuleiro", codigo);
    }

    private static <T> T aguardar(CompletableFuture<T> resultado) throws Exception {
        return resultado.get(45, TimeUnit.SECONDS);
    }

    private static void conflito(String campo, CompletableFuture<?> resultado) {
        ExecutionException erro = assertThrows(ExecutionException.class, () -> aguardar(resultado));
        assertEquals(campo, assertInstanceOf(DuplicateUniqueValueException.class, causa(erro)).getField());
    }

    private static Throwable causa(Throwable erro) {
        while (erro.getCause() != null) erro = erro.getCause();
        return erro;
    }
}
