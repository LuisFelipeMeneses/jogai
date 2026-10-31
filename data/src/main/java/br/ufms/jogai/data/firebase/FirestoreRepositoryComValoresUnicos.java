package br.ufms.jogai.data.firebase;

import br.ufms.jogai.data.repository.ValorUnicoDuplicadoException;
import br.ufms.jogai.data.repository.ValoresUnicosFirestoreRepository;
import br.ufms.jogai.domain.shared.Entidade;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Transaction;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;

/** CRUD atômico com unicidade de campos String obrigatórios, sem dependência de uma entidade específica. */
public abstract class FirestoreRepositoryComValoresUnicos<E extends Entidade<ID>, ID extends Serializable>
        extends FirestoreRepository<E, ID> {

    private final String escopo;
    private final Map<String, UnaryOperator<String>> camposUnicos;
    private final ValoresUnicosFirestoreRepository valoresUnicos;
    private final DocumentReference inicializacao;

    protected FirestoreRepositoryComValoresUnicos(CollectionReference collection, String escopo,
                                                  Map<String, UnaryOperator<String>> camposUnicos) {
        super(collection);
        this.escopo = Objects.requireNonNull(escopo);
        if (escopo.isBlank() || camposUnicos.isEmpty()) {
            throw new IllegalArgumentException("Informe um escopo e pelo menos um campo único");
        }
        this.camposUnicos = Map.copyOf(camposUnicos);
        valoresUnicos = new ValoresUnicosFirestoreRepository(db);
        inicializacao = valoresUnicos.controleInicializacao(collection.getPath(), escopo, camposUnicos.keySet());
    }

    public final CompletableFuture<E> cadastrar(E entidade) {
        return persistir(entidade, true);
    }

    @Override
    public final CompletableFuture<E> save(E entidade) {
        return persistir(entidade, false);
    }

    private CompletableFuture<E> persistir(E entidade, boolean somenteNovo) {
        Objects.requireNonNull(entidade);
        String id = idToStr(entidade.getId());
        Map<String, Object> dados = new LinkedHashMap<>(entityToMap(entidade));
        Map<String, String> atuais = extrairValoresUnicos(dados);
        // O mesmo valor normalizado é usado tanto no documento quanto na reserva.
        dados.putAll(atuais);
        Map<String, Object> dadosCapturados = Collections.unmodifiableMap(dados);
        E copia = mapToEntity(id, dadosCapturados);
        DocumentReference referencia = collection.document(id);

        return toCompletableFuture(db.runTransaction(transaction -> {
            var anterior = transaction.get(referencia).get();
            if (somenteNovo && anterior.exists()) throw conflitoUnico("id");
            var reservas = valoresUnicos.naTransacao(transaction);
            boolean inicializar;
            try {
                inicializar = prepararInicializacao(transaction, reservas);
                reservas.substituir(escopo, id,
                        anterior.exists() ? extrairValoresUnicos(anterior.getData()) : Map.of(), atuais);
            } catch (ValorUnicoDuplicadoException conflito) {
                throw conflitoUnico(conflito.getCampo());
            }
            // A operação de reservas não confirma nada: todas as escritas têm o mesmo commit.
            reservas.aplicar();
            if (inicializar) transaction.set(inicializacao, Map.of("colecao", collection.getPath(), "escopo", escopo));
            transaction.set(referencia, dadosCapturados);
            return copia;
        }));
    }

    @Override
    public final CompletableFuture<Void> delete(ID id) {
        String entidadeId = idToStr(id);
        DocumentReference referencia = collection.document(entidadeId);
        return toCompletableFuture(db.<Void>runTransaction(transaction -> {
            var documento = transaction.get(referencia).get();
            if (!documento.exists()) return null;
            var reservas = valoresUnicos.naTransacao(transaction);
            reservas.substituir(escopo, entidadeId, extrairValoresUnicos(documento.getData()), Map.of());
            reservas.aplicar();
            transaction.delete(referencia);
            return null;
        }));
    }

    private boolean prepararInicializacao(Transaction transaction, ValoresUnicosFirestoreRepository.Operacao reservas)
            throws ExecutionException, InterruptedException {
        if (transaction.get(inicializacao).get().exists()) return false;
        // Compatibilidade com bases iniciais e marcadores anteriores: nunca há escritas parciais.
        for (var documento : transaction.get(collection).get().getDocuments()) {
            for (var campo : extrairValoresUnicos(documento.getData()).entrySet()) {
                reservas.reservar(escopo, campo.getKey(), campo.getValue(), documento.getId());
            }
        }
        return true;
    }

    private Map<String, String> extrairValoresUnicos(Map<String, Object> dados) {
        Map<String, String> valores = new LinkedHashMap<>();
        camposUnicos.forEach((campo, normalizar) -> {
            if (!(dados.get(campo) instanceof String valor)) {
                throw new IllegalArgumentException("O campo único " + campo + " deve ser uma String obrigatória");
            }
            String normalizado = Objects.requireNonNull(normalizar.apply(valor));
            if (normalizado.isBlank()) throw new IllegalArgumentException("O campo único " + campo + " não pode ser vazio");
            valores.put(campo, normalizado);
        });
        return Map.copyOf(valores);
    }

    /** Pode ser sobrescrito para traduzir conflitos em uma exceção específica do domínio. */
    protected RuntimeException conflitoUnico(String campo) {
        return new ValorUnicoDuplicadoException(campo);
    }

    @Override
    protected final E documentToEntity(DocumentSnapshot documento) {
        return mapToEntity(documento.getId(), documento.getData());
    }

    /** Reconstrói uma entidade independente, tanto para leitura quanto para o resultado da gravação. */
    protected abstract E mapToEntity(String id, Map<String, Object> dados);
}
