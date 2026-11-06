package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.domain.jogo.CategoriaJogo;
import br.ufms.jogai.domain.jogo.Jogo;
import br.ufms.jogai.domain.jogo.JogoRepository;
import br.ufms.jogai.domain.shared.Page;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class JogoFirestoreRepository
        extends FirestoreRepository<Jogo, UUID>
        implements JogoRepository {

    private static final String CATEGORIAS_COLLECTION = "categorias_jogo";

    public JogoFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public JogoFirestoreRepository(Firestore firestore) {
        super(
                Objects.requireNonNull(firestore).collection("jogos"),
                Set.of("nome")
        );
    }

    @Override
    protected Map<String, Object> entityToMap(Jogo entity) {

        Map<String, Object> data = new HashMap<>();

        data.put("nome", entity.getNome());
        data.put("editora", entity.getEditora());
        data.put("ano_lancamento", entity.getAnoLancamento().getValue());

        data.put("min_jogadores", entity.getMinimoJogadores());
        data.put("max_jogadores", entity.getMaximoJogadores());

        data.put("idade_minima", entity.getIdadeMinimaRecomendada());
        data.put("idade_maxima", entity.getIdadeMaximaRecomendada());

        data.put("duracao_minima", entity.getTempoMinimoEstimado().toMinutes());
        data.put("duracao_maxima", entity.getTempoMaximoEstimado().toMinutes());

        data.put("categorias", entity.getCategorias()
                .stream()
                .map(categoria -> db.collection(CATEGORIAS_COLLECTION)
                        .document(categoria.getId()))
                .toList());

        data.put("descricao", entity.getDescricao());
        data.put("ativo", entity.isAtivo());

        return data;
    }

    @Override
    protected Jogo mapToEntity(String id, Map<String, Object> data) {

        Object raw = data.get("categorias");

        if (!(raw instanceof List<?> references) || references.isEmpty()) {
            throw new IllegalStateException(
                    "O jogo '" + id + "' não possui categorias válidas no Firestore."
            );
        }

        Set<CategoriaJogo> categorias = new HashSet<>();

        for (Object item : references) {
            if (!(item instanceof DocumentReference reference)) {
                throw new IllegalStateException(
                        "Categoria inválida no jogo '" + id + "'. " +
                                "Esperado DocumentReference, recebido: " +
                                (item == null ? "null" : item.getClass().getSimpleName())
                );
            }

            CategoriaJogo categoria = documentReferenceToCategoria(reference);

            if (categoria == null) {
                throw new IllegalStateException(
                        "Não foi possível reconstruir uma categoria do jogo '" + id + "'."
                );
            }

            categorias.add(categoria);
        }

        return Jogo.reconstitute(idToUUID(id), (Boolean) data.get("ativo"))
                .nomeEditoraLancamento(
                        (String) data.get("nome"),
                        (String) data.get("editora"),
                        toInteger(data.get("ano_lancamento"))
                )
                .numeroJogadores(
                        toInteger(data.get("min_jogadores")),
                        toInteger(data.get("max_jogadores"))
                )
                .faixaEtaria(
                        toInteger(data.get("idade_minima")),
                        toInteger(data.get("idade_maxima"))
                )
                .duracaoPartida(
                        toInteger(data.get("duracao_minima")),
                        toInteger(data.get("duracao_maxima"))
                )
                .categorias(categorias.toArray(new CategoriaJogo[0]))
                .descricao((String) data.get("descricao"))
                .build();
    }

    private CategoriaJogo documentReferenceToCategoria(DocumentReference reference) {

        try {
            DocumentSnapshot snapshot = reference.get().get();

            if (!snapshot.exists()) {
                throw new IllegalStateException(
                        "Categoria não encontrada: " + reference.getId()
                );
            }

            return CategoriaJogo.reconstitute(
                    snapshot.getId(),
                    snapshot.getString("nome"),
                    snapshot.getString("descricao")
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Erro ao buscar categoria: " + reference.getId(), e);

        } catch (ExecutionException e) {
            throw new RuntimeException("Erro ao buscar categoria: " + reference.getId(), e);
        }
    }

    private Integer toInteger(Object value) {
        return value instanceof Number number
                ? number.intValue()
                : null;
    }

    @Override
    public CompletableFuture<Optional<Jogo>> buscarJogoPorNome(String nomeJogo) {
        Objects.requireNonNull(nomeJogo);
        return queryOne(collection.whereEqualTo("nome", nomeJogo));
    }

    @Override
    public CompletableFuture<Page<Jogo, UUID>> listarJogosPorCategoria(
            String nomeCategoria,
            int pageSize,
            UUID afterId
    ) {
        Objects.requireNonNull(nomeCategoria);

        DocumentReference categoriaRef = db.collection(CATEGORIAS_COLLECTION)
                .document(nomeCategoria);

        return queryPage(
                collection.whereArrayContains("categorias_jogo", categoriaRef),
                pageSize,
                afterId
        );
    }
}