package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.CollectionReference;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exemplo compilado e exercitado pelos testes de integração. */
public final class CategoriaExemploRepository extends FirestoreRepository<CategoriaExemplo, UUID> {

    public CategoriaExemploRepository(Firestore firestore) {
        super(firestore.collection("categorias_exemplo"), "categoria_exemplo",
                Set.of("codigo"));
    }

    /** Configura uma coleção ou subcoleção, com código único dentro do caminho completo. */
    public CategoriaExemploRepository(CollectionReference collection) {
        super(collection, Set.of("codigo"));
    }

    @Override
    protected Map<String, Object> entityToMap(CategoriaExemplo categoria) {
        return Map.of(
                "nome", categoria.getNome(),
                "codigo", categoria.getCodigo()
        );
    }

    @Override
    protected CategoriaExemplo mapToEntity(String id, Map<String, Object> dados) {
        return new CategoriaExemplo(
                idToUUID(id),
                (String) dados.get("nome"),
                (String) dados.get("codigo")
        );
    }
}
