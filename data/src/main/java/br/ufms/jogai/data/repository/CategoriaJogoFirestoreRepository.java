package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.domain.jogo.CategoriaJogo;
import br.ufms.jogai.domain.jogo.CategoriaJogoRepository;
import br.ufms.jogai.domain.shared.Page;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class CategoriaJogoFirestoreRepository
        extends FirestoreRepository<CategoriaJogo, String>
        implements CategoriaJogoRepository {

    public CategoriaJogoFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public CategoriaJogoFirestoreRepository(Firestore firestore) {
        super(Objects.requireNonNull(firestore).collection("categorias_jogo"), Set.of("nome"));
    }

    @Override
    protected Map<String, Object> entityToMap(CategoriaJogo entity) {
        return Map.of(
                "nome", entity.getNome(),
                "descricao", Objects.requireNonNullElse(entity.getDescricao(), "")
        );
    }

    @Override
    protected CategoriaJogo mapToEntity(String id, Map<String, Object> data) {
        return CategoriaJogo.reconstitute(
                id,
                (String) data.get("nome"),
                (String) data.get("descricao")
        );
    }

    @Override
    public CompletableFuture<Optional<CategoriaJogo>> buscarCategoriaPorNome(
            String nomeCategoria
    ) {
        Objects.requireNonNull(nomeCategoria);

        return queryOne(collection.whereEqualTo("nome", nomeCategoria));
    }
}