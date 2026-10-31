package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.data.firebase.FirestoreRepositoryComValoresUnicos;
import com.google.cloud.firestore.Firestore;

import java.util.Map;
import java.util.UUID;

/** Exemplo compilado e exercitado pelos testes de integração. */
public final class CategoriaExemploRepository extends FirestoreRepositoryComValoresUnicos<CategoriaExemplo, UUID> {

    public CategoriaExemploRepository(Firestore firestore) {
        super(firestore.collection("categorias_exemplo"), "categoria_exemplo",
                Map.of("codigo", CategoriaExemplo::normalizarCodigo));
    }

    @Override
    protected Map<String, Object> entityToMap(CategoriaExemplo categoria) {
        return Map.of("nome", categoria.getNome(), "codigo", categoria.getCodigo());
    }

    @Override
    protected CategoriaExemplo mapToEntity(String id, Map<String, Object> dados) {
        return new CategoriaExemplo(idToUUID(id), (String) dados.get("nome"), (String) dados.get("codigo"));
    }
}
