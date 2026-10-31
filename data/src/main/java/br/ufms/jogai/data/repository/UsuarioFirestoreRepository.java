package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepositoryComValoresUnicos;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.domain.usuario.StatusUsuario;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioRepository;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import br.ufms.jogai.domain.util.Validar;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.Timestamp;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UsuarioFirestoreRepository extends FirestoreRepositoryComValoresUnicos<Usuario, UUID> implements UsuarioRepository {

    public UsuarioFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public UsuarioFirestoreRepository(Firestore firestore) {
        super(Objects.requireNonNull(firestore).collection("usuarios"), "usuario", Map.of(
                "email", UsuarioFirestoreRepository::normalizarEmail,
                "telefone", Validar::telefone
        ));
    }

    @Override
    protected RuntimeException conflitoUnico(String campo) {
        return new UsuarioDuplicadoException(campo);
    }

    private static String normalizarEmail(String email) {
        return Validar.email(Objects.requireNonNull(email).strip()).toLowerCase(Locale.ROOT);
    }

    @Override
    protected Map<String, Object> entityToMap(Usuario entity) {
        return Map.of(
                "nome", entity.getNome(),
                "email", entity.getEmail(),
                "telefone", entity.getTelefone(),
                "nascimento", toTimestamp(entity.getDataNascimento()),
                "cadastradoEm", toTimestamp(entity.getDataCadastro()),
                "status", entity.getStatus().name()
        );
    }

    @Override
    protected Usuario mapToEntity(String id, Map<String, Object> dados) {
        return Usuario.reconstitute(
                idToUUID(id),
                (String) dados.get("nome"),
                (String) dados.get("email"),
                (String) dados.get("telefone"),
                toLocalDate((Timestamp) dados.get("nascimento")),
                toInstant((Timestamp) dados.get("cadastradoEm")),
                StatusUsuario.valueOf((String) dados.get("status"))
        );
    }

    @Override
    public CompletableFuture<Collection<Usuario>> listarPorNome(String nome) {
        Objects.requireNonNull(nome);

        return query(collection
                .orderBy("nome")
                .startAt(nome)
                .endAt(nome + "\uf8ff"));
    }

    @Override
    public CompletableFuture<Optional<Usuario>> buscarPorEmail(String email) {
        Objects.requireNonNull(email);

        return queryOne(collection.whereEqualTo("email", normalizarEmail(email)));
    }

    @Override
    public CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone) {
        Objects.requireNonNull(telefone);

        return queryOne(collection.whereEqualTo("telefone", Validar.telefone(telefone)));
    }

    @Override
    public CompletableFuture<Collection<Usuario>> listarPorStatus(StatusUsuario status) {
        Objects.requireNonNull(status);

        return query(collection.whereEqualTo("status", status.name()));
    }
}
