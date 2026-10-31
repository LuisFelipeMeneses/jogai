package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.domain.usuario.StatusUsuario;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioRepository;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import br.ufms.jogai.domain.util.Validar;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.Timestamp;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UsuarioFirestoreRepository extends FirestoreRepository<Usuario, UUID> implements UsuarioRepository {

    public UsuarioFirestoreRepository() {
        this(FirebaseConfig.getFirestore());
    }

    public UsuarioFirestoreRepository(Firestore firestore) {
        super(Objects.requireNonNull(firestore).collection("usuarios"), Set.of("email", "telefone"));
    }

    /**
     * Cadastra todos os usuários atomicamente, incluindo suas reservas de email e telefone.
     */
    public CompletableFuture<Collection<Usuario>> cadastrarTodos(Collection<Usuario> usuarios) {
        Objects.requireNonNull(usuarios);
        return writeTransaction(writes -> usuarios.stream().map(usuario -> writes.create(this, usuario)).toList());
    }

    @Override
    protected RuntimeException duplicateValueException(String campo) {
        return new UsuarioDuplicadoException(campo);
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
    public CompletableFuture<Page<Usuario, UUID>> listarPorNome(String nome, int pageSize, UUID afterId) {
        Objects.requireNonNull(nome);

        return queryPage(collection
                .whereGreaterThanOrEqualTo("nome", nome)
                .whereLessThanOrEqualTo("nome", nome + "\uf8ff")
                .orderBy("nome"), pageSize, afterId);
    }

    @Override
    public CompletableFuture<Optional<Usuario>> buscarPorEmail(String email) {
        Objects.requireNonNull(email);

        return queryOne(collection.whereEqualTo("email", Usuario.normalizarEmail(email)));
    }

    @Override
    public CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone) {
        Objects.requireNonNull(telefone);

        return queryOne(collection.whereEqualTo("telefone", Validar.telefone(telefone)));
    }

    @Override
    public CompletableFuture<Page<Usuario, UUID>> listarPorStatus(StatusUsuario status, int pageSize, UUID afterId) {
        Objects.requireNonNull(status);

        return queryPage(collection.whereEqualTo("status", status.name()), pageSize, afterId);
    }
}
