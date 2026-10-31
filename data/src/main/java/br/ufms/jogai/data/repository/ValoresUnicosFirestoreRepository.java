package br.ufms.jogai.data.repository;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reservas de valores já normalizados. Nunca inicia nem confirma transações.
 */
public final class ValoresUnicosFirestoreRepository {

    private final CollectionReference collection;

    public ValoresUnicosFirestoreRepository(Firestore firestore) {
        collection = Objects.requireNonNull(firestore).collection("valores_unicos");
    }

    public Operacao naTransacao(Transaction transaction) {
        return new Operacao(Objects.requireNonNull(transaction));
    }

    public DocumentReference controleInicializacao(String colecao, String escopo, Set<String> campos) {
        return collection.getFirestore().collection("valores_unicos_controle")
                .document(chave(Stream.concat(Stream.of(colecao, escopo), campos.stream().sorted())));
    }

    private DocumentReference referencia(String entidade, String campo, String valor) {
        // Codifica cada parte para evitar ambiguidades entre entidade, campo e valor.
        return collection.document(chave(Stream.of(entidade, campo, valor)));
    }

    private static String chave(Stream<String> partes) {
        return partes.map(ValoresUnicosFirestoreRepository::codificar).collect(Collectors.joining("."));
    }

    private static String codificar(String valor) {
        if (Objects.requireNonNull(valor).isBlank()) {
            throw new IllegalArgumentException("A chave da reserva não pode ser vazia");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valor.getBytes(StandardCharsets.UTF_8));
    }

    private record Reserva(String entidade, String campo, String entidadeId) {
        Map<String, Object> dados() {
            return Map.of("entidade", entidade, "campo", campo, "entidadeId", entidadeId);
        }
    }

    /**
     * Uma instância por tentativa do callback da transação; leituras precedem aplicar().
     */
    public final class Operacao {

        private final Transaction transaction;
        private final Map<DocumentReference, DocumentSnapshot> lidas = new LinkedHashMap<>();
        // Uma entrada nula representa exclusão; ausência de entrada representa nenhuma alteração.
        private final Map<DocumentReference, Reserva> alteracoes = new LinkedHashMap<>();
        private boolean aplicada;

        private Operacao(Transaction transaction) {
            this.transaction = transaction;
        }

        /** Prepara a troca sem liberar nenhum valor até que todos os novos sejam válidos. */
        public void substituir(String entidade, String entidadeId,
                               Map<String, String> anteriores, Map<String, String> atuais)
                throws ExecutionException, InterruptedException {
            for (var campo : atuais.entrySet()) {
                reservar(entidade, campo.getKey(), campo.getValue(), entidadeId);
            }
            for (var campo : anteriores.entrySet()) {
                if (!Objects.equals(campo.getValue(), atuais.get(campo.getKey()))) {
                    liberar(entidade, campo.getKey(), campo.getValue(), entidadeId);
                }
            }
        }

        public void reservar(String entidade, String campo, String valor, String entidadeId)
                throws ExecutionException, InterruptedException {
            Objects.requireNonNull(entidadeId);
            DocumentReference referencia = referencia(entidade, campo, valor);
            DocumentSnapshot existente = ler(referencia);
            Reserva pendente = alteracoes.get(referencia);
            if ((existente.exists() && !entidadeId.equals(existente.getString("entidadeId")))
                    || (pendente != null && !entidadeId.equals(pendente.entidadeId()))) {
                throw new ValorUnicoDuplicadoException(campo);
            }
            alteracoes.put(referencia, new Reserva(entidade, campo, entidadeId));
        }

        public void liberar(String entidade, String campo, String valor, String entidadeId)
                throws ExecutionException, InterruptedException {
            Objects.requireNonNull(entidadeId);
            DocumentReference referencia = referencia(entidade, campo, valor);
            DocumentSnapshot existente = ler(referencia);
            Reserva pendente = alteracoes.get(referencia);
            // Nunca exclui a reserva de outra entidade, mesmo em uma base inconsistente.
            if (entidadeId.equals(existente.getString("entidadeId"))) {
                alteracoes.put(referencia, null);
            } else if (pendente != null && entidadeId.equals(pendente.entidadeId())) {
                alteracoes.remove(referencia);
            }
        }

        private DocumentSnapshot ler(DocumentReference referencia)
                throws ExecutionException, InterruptedException {
            verificarNaoAplicada();
            DocumentSnapshot documento = lidas.get(referencia);
            if (documento == null) {
                documento = transaction.get(referencia).get();
                lidas.put(referencia, documento);
            }
            return documento;
        }

        /**
         * Agenda as escritas na transação recebida; a confirmação cabe ao chamador.
         */
        public void aplicar() {
            verificarNaoAplicada();
            aplicada = true;
            alteracoes.forEach((referencia, reserva) -> {
                if (reserva == null) transaction.delete(referencia);
                else transaction.set(referencia, reserva.dados());
            });
        }

        private void verificarNaoAplicada() {
            if (aplicada) {
                throw new IllegalStateException("As reservas desta operação já foram aplicadas");
            }
        }
    }
}
