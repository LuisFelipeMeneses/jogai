package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.usuario.UsuarioInfo;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ExemplarFirestoreRepository
        extends FirestoreRepository<Exemplar, UUID>
        implements ExemplarRepository {

    private static final String JOGOS = "jogos";
    private static final String USUARIOS = "usuarios";

    public ExemplarFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public ExemplarFirestoreRepository(Firestore firestore) {
        super(Objects.requireNonNull(firestore).collection("exemplares"));
    }

    @Override
    protected Map<String, Object> entityToMap(Exemplar entity) {
        Map<String, Object> data = new HashMap<>();

        data.put("jogo", db.collection(JOGOS)
                .document(entity.getJogoInfo().id().toString()));

        data.put("proprietario", db.collection(USUARIOS)
                .document(entity.getProprietarioInfo().id().toString()));

        data.put("estado_conservacao", entity.getEstadoConservacao().name());
        data.put("observacao", entity.getObservacao());
        data.put("data_cadastro", toTimestamp(entity.getDataCadastro()));

        return data;
    }

    @Override
    protected Exemplar mapToEntity(String id, Map<String, Object> data) {
        DocumentReference jogoReference = requireReference(data, "jogo", id);
        DocumentReference proprietarioReference =
                requireReference(data, "proprietario", id);

        Timestamp dataCadastro = Objects.requireNonNull(
                (Timestamp) data.get("data_cadastro"),
                "Data de cadastro ausente no exemplar: " + id
        );

        return Exemplar.reconstitute(
                idToUUID(id),
                mapToJogoInfo(getDocument(jogoReference)),
                mapToUsuarioInfo(getDocument(proprietarioReference)),
                EstadoConservacao.valueOf(
                        Objects.requireNonNull(
                                (String) data.get("estado_conservacao"),
                                "Estado de conservação ausente no exemplar: " + id
                        )
                ),
                (String) data.get("observacao"),
                toInstant(dataCadastro)
        );
    }

    private DocumentReference requireReference(
            Map<String, Object> data,
            String field,
            String exemplarId
    ) {
        Object value = data.get(field);

        if (!(value instanceof DocumentReference reference)) {
            throw new IllegalStateException(
                    "Referência '" + field + "' inválida no exemplar: " + exemplarId
            );
        }

        return reference;
    }

    private DocumentSnapshot getDocument(DocumentReference reference) {
        try {
            DocumentSnapshot snapshot = reference.get().get();

            if (!snapshot.exists()) {
                throw new IllegalStateException(
                        "Documento não encontrado: " + reference.getPath()
                );
            }

            return snapshot;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Busca interrompida: " + reference.getPath(), e
            );

        } catch (ExecutionException e) {
            throw new RuntimeException(
                    "Erro ao buscar documento: " + reference.getPath(), e
            );
        }
    }

    private JogoInfo mapToJogoInfo(DocumentSnapshot snapshot) {
        @SuppressWarnings("unchecked")
        List<DocumentReference> references =
                (List<DocumentReference>) snapshot.get("categorias");

        if (references == null || references.isEmpty()) {
            throw new IllegalStateException(
                    "Jogo sem categorias: " + snapshot.getId()
            );
        }

        Set<CategoriaJogo> categorias = references.stream()
                .map(this::mapToCategoria)
                .collect(Collectors.toSet());

        return new JogoInfo(
                idToUUID(snapshot.getId()),
                snapshot.getString("nome"),
                snapshot.getString("editora"),
                categorias
        );
    }

    private CategoriaJogo mapToCategoria(DocumentReference reference) {
        DocumentSnapshot snapshot = getDocument(reference);

        return CategoriaJogo.reconstitute(
                snapshot.getId(),
                snapshot.getString("nome"),
                snapshot.getString("descricao")
        );
    }

    private UsuarioInfo mapToUsuarioInfo(DocumentSnapshot snapshot) {
        Timestamp nascimento = Objects.requireNonNull(
                snapshot.getTimestamp("nascimento"),
                "Data de nascimento ausente no usuário: " + snapshot.getId()
        );

        return new UsuarioInfo(
                idToUUID(snapshot.getId()),
                snapshot.getString("nome"),
                snapshot.getString("email"),
                snapshot.getString("telefone"),
                toLocalDate(nascimento)
        );
    }

    @Override
    public CompletableFuture<Page<Exemplar, UUID>> buscarExemplaresPorJogo(String nomeJogo, int pageSize, UUID afterId) {
        Objects.requireNonNull(nomeJogo);
        return queryPage(collection.whereEqualTo("jogo.nome", nomeJogo), pageSize, afterId);
    }

    @Override
    public CompletableFuture<Page<Exemplar, UUID>> buscarExemplaresPorUsuario(String nomeUsuario, int pageSize, UUID afterId) {
        Objects.requireNonNull(nomeUsuario);
        return queryPage(collection.whereEqualTo("usuario.nome", nomeUsuario), pageSize, afterId);
    }
}