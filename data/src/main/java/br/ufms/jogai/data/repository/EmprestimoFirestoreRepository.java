
package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;

import br.ufms.jogai.domain.emprestimo.Emprestimo;
import br.ufms.jogai.domain.emprestimo.EmprestimoRepository;
import br.ufms.jogai.domain.emprestimo.ItemEmprestimo;

import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.usuario.UsuarioInfo;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class EmprestimoFirestoreRepository
        extends FirestoreRepository<Emprestimo, UUID>
        implements EmprestimoRepository {

    private static final String USUARIOS = "usuarios";
    private static final String EXEMPLARES = "exemplares";

    public EmprestimoFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public EmprestimoFirestoreRepository(Firestore firestore) {
        super(Objects.requireNonNull(firestore)
                .collection("emprestimos"));
    }

    @Override
    protected Map<String, Object> entityToMap(Emprestimo entity) {
        Objects.requireNonNull(entity, "Empréstimo não pode ser nulo.");

        Objects.requireNonNull(
                entity.getProprietario(),
                "Proprietário do empréstimo não pode ser nulo."
        );

        Objects.requireNonNull(
                entity.getTomador(),
                "Tomador do empréstimo não pode ser nulo."
        );

        Objects.requireNonNull(
                entity.getDataEmprestimo(),
                "Data do empréstimo não pode ser nula."
        );

        Objects.requireNonNull(
                entity.getDataDevolucao(),
                "Data prevista de devolução não pode ser nula."
        );

        Objects.requireNonNull(
                entity.getItens(),
                "A coleção de itens do empréstimo não pode ser nula."
        );

        Map<String, Object> data = new HashMap<>();

        data.put(
                "proprietario",
                db.collection(USUARIOS)
                        .document(entity.getProprietario().id().toString())
        );

        data.put(
                "tomador",
                db.collection(USUARIOS)
                        .document(entity.getTomador().id().toString())
        );

        data.put(
                "dataEmprestimo",
                toTimestamp(entity.getDataEmprestimo())
        );

        data.put(
                "dataDevolucao",
                toTimestamp(entity.getDataDevolucao())
        );

        List<Map<String, Object>> itens = entity.getItens()
                .stream()
                .map(this::itemToMap)
                .toList();

        data.put("itens", itens);

        return data;
    }

    private Map<String, Object> itemToMap(ItemEmprestimo item) {
        Objects.requireNonNull(
                item,
                "Um item do empréstimo não pode ser nulo."
        );

        Objects.requireNonNull(
                item.getExemplar(),
                "O exemplar de um item do empréstimo não pode ser nulo."
        );

        Map<String, Object> data = new HashMap<>();

        data.put("id", item.getId().toString());

        data.put(
                "exemplar",
                db.collection(EXEMPLARES)
                        .document(item.getExemplar().getId().toString())
        );

        data.put(
                "dataEfetivaDevolucao",
                item.getDataEfetivaDevolucao() == null
                        ? null
                        : toTimestamp(item.getDataEfetivaDevolucao())
        );

        return data;
    }

    @Override
    protected Emprestimo mapToEntity(
            String id,
            Map<String, Object> data
    ) {
        DocumentReference proprietarioReference =
                requireReference(data, "proprietario", id);

        DocumentReference tomadorReference =
                requireReference(data, "tomador", id);

        Timestamp dataEmprestimoTimestamp =
                requireTimestamp(data, "dataEmprestimo", id);

        Timestamp dataDevolucaoTimestamp =
                requireTimestamp(data, "dataDevolucao", id);

        LocalDateTime dataEmprestimo =
                toLocalDateTime(dataEmprestimoTimestamp);

        LocalDate dataDevolucao =
                toLocalDate(dataDevolucaoTimestamp);

        UsuarioInfo proprietario =
                mapToUsuarioInfo(getDocument(proprietarioReference));

        UsuarioInfo tomador =
                mapToUsuarioInfo(getDocument(tomadorReference));

        List<ItemEmprestimo> itens =
                mapToItens(data.get("itens"), id);

        return new Emprestimo(
                idToUUID(id),
                proprietario,
                tomador,
                dataEmprestimo,
                dataDevolucao,
                itens
        );
    }

    private List<ItemEmprestimo> mapToItens(
            Object value,
            String emprestimoId
    ) {
        if (value == null) {
            return new ArrayList<>();
        }

        if (!(value instanceof List<?> items)) {
            throw new IllegalStateException(
                    "Lista de itens inválida no empréstimo: " + emprestimoId
            );
        }

        List<ItemEmprestimo> itens = new ArrayList<>();

        for (Object item : items) {
            if (!(item instanceof Map<?, ?> itemData)) {
                throw new IllegalStateException(
                        "Item inválido no empréstimo: " + emprestimoId
                );
            }

            itens.add(mapToItemEmprestimo(itemData, emprestimoId));
        }

        return itens;
    }

    private ItemEmprestimo mapToItemEmprestimo(
            Map<?, ?> data,
            String emprestimoId
    ) {
        Object idValue = data.get("id");

        if (!(idValue instanceof String itemId)) {
            throw new IllegalStateException(
                    "ID do item ausente no empréstimo: " + emprestimoId
            );
        }

        Object exemplarValue = data.get("exemplar");

        if (!(exemplarValue instanceof DocumentReference exemplarReference)) {
            throw new IllegalStateException(
                    "Referência do exemplar inválida no empréstimo: "
                            + emprestimoId
            );
        }

        Exemplar exemplar = getExemplar(exemplarReference);

        Object devolucaoValue = data.get("dataEfetivaDevolucao");

        Timestamp dataEfetivaDevolucao = null;

        if (devolucaoValue != null) {
            if (!(devolucaoValue instanceof Timestamp timestamp)) {
                throw new IllegalStateException(
                        "Data efetiva de devolução inválida no item: " + itemId
                );
            }

            dataEfetivaDevolucao = timestamp;
        }

        return new ItemEmprestimo(
                UUID.fromString(itemId),
                exemplar,
                dataEfetivaDevolucao == null
                        ? null
                        : toInstant(dataEfetivaDevolucao)
        );
    }

    private Exemplar getExemplar(DocumentReference reference) {
        DocumentSnapshot snapshot = getDocument(reference);

        return mapToExemplar(snapshot);
    }

    private Exemplar mapToExemplar(DocumentSnapshot snapshot) {
        String id = snapshot.getId();

        Map<String, Object> data =
                Objects.requireNonNull(snapshot.getData());

        DocumentReference jogoReference =
                requireReference(data, "jogo", id);

        DocumentReference proprietarioReference =
                requireReference(data, "proprietario", id);

        Timestamp dataCadastro = Objects.requireNonNull(
                snapshot.getTimestamp("data_cadastro"),
                "Data de cadastro ausente no exemplar: " + id
        );

        String estadoConservacao = Objects.requireNonNull(
                snapshot.getString("estado_conservacao"),
                "Estado de conservação ausente no exemplar: " + id
        );

        return Exemplar.reconstitute(
                idToUUID(id),
                mapToJogoInfo(getDocument(jogoReference)),
                mapToUsuarioInfo(getDocument(proprietarioReference)),
                EstadoConservacao.valueOf(estadoConservacao),
                snapshot.getString("observacao"),
                toInstant(dataCadastro)
        );
    }

    private JogoInfo mapToJogoInfo(DocumentSnapshot snapshot) {
        String id = snapshot.getId();

        Object categoriasValue = snapshot.get("categorias");

        if (!(categoriasValue instanceof List<?> references)
                || references.isEmpty()) {
            throw new IllegalStateException(
                    "Jogo sem categorias: " + id
            );
        }

        Set<CategoriaJogo> categorias = references.stream()
                .map(value -> {
                    if (!(value instanceof DocumentReference reference)) {
                        throw new IllegalStateException(
                                "Referência de categoria inválida no jogo: "
                                        + id
                        );
                    }

                    return mapToCategoria(reference);
                })
                .collect(Collectors.toSet());

        return new JogoInfo(
                idToUUID(id),
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
                "Nascimento ausente no usuário: " + snapshot.getId()
        );

        return new UsuarioInfo(
                idToUUID(snapshot.getId()),
                snapshot.getString("nome"),
                snapshot.getString("email"),
                snapshot.getString("telefone"),
                toLocalDate(nascimento)
        );
    }

    private DocumentReference requireReference(
            Map<String, Object> data,
            String field,
            String documentId
    ) {
        Object value = data.get(field);

        if (!(value instanceof DocumentReference reference)) {
            throw new IllegalStateException(
                    "Referência '" + field + "' inválida no documento: "
                            + documentId
            );
        }

        return reference;
    }

    private Timestamp requireTimestamp(
            Map<String, Object> data,
            String field,
            String documentId
    ) {
        Object value = data.get(field);

        if (!(value instanceof Timestamp timestamp)) {
            throw new IllegalStateException(
                    "Data '" + field + "' ausente ou inválida no documento: "
                            + documentId
            );
        }

        return timestamp;
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
                    "Busca interrompida: " + reference.getPath(),
                    e
            );

        } catch (ExecutionException e) {
            throw new RuntimeException(
                    "Erro ao buscar documento: " + reference.getPath(),
                    e
            );
        }
    }

    private Timestamp toTimestamp(LocalDateTime dateTime) {
        Objects.requireNonNull(
                dateTime,
                "Data e hora não podem ser nulas."
        );

        return toTimestamp(dateTime.toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        Objects.requireNonNull(timestamp);

        return toInstant(timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    @Override
    public CompletableFuture<Page<Emprestimo, UUID>> listarPorProprietário(
            String proprietario,
            int pageSize,
            UUID afterId
    ) {
        Objects.requireNonNull(proprietario);

        DocumentReference reference = db.collection(USUARIOS)
                .document(proprietario);

        return queryPage(
                collection.whereEqualTo("proprietario", reference),
                pageSize,
                afterId
        );
    }

    @Override
    public CompletableFuture<Page<Emprestimo, UUID>> listarPorTomador(
            String tomador,
            int pageSize,
            UUID afterId
    ) {
        Objects.requireNonNull(tomador);

        DocumentReference reference = db.collection(USUARIOS)
                .document(tomador);

        return queryPage(
                collection.whereEqualTo("tomador", reference),
                pageSize,
                afterId
        );
    }
}
