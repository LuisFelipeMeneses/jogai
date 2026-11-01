package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepositoryV2;
import br.ufms.jogai.data.firebase.config.FirebaseConfig;
import br.ufms.jogai.data.model.UsuarioDocument;
import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.usuario.StatusUsuario;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioRepository;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import br.ufms.jogai.domain.util.Validar;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;

import java.util.Objects;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UsuarioFirestoreRepositoryV2 extends FirestoreRepositoryV2<Usuario, UUID, UsuarioDocument>
        implements UsuarioRepository {

    public UsuarioFirestoreRepositoryV2() {
        this(FirebaseConfig.getFirestore());
    }

    public UsuarioFirestoreRepositoryV2(Firestore firestore) {
        this(Objects.requireNonNull(firestore, "Firestore must not be null").collection("usuarios"));
    }

    public UsuarioFirestoreRepositoryV2(CollectionReference collection) {
        super(collection, UsuarioDocument.class, UsuarioDocument::new);
    }

    /** Cadastra todos os usuários atomicamente, incluindo reservas de email e telefone. */
    public CompletableFuture<Collection<Usuario>> cadastrarTodos(Collection<Usuario> usuarios) {
        Objects.requireNonNull(usuarios, "Users must not be null");
        return writeTransaction(writes -> usuarios.stream()
                .map(usuario -> writes.create(this, usuario))
                .toList());
    }

    @Override
    protected RuntimeException duplicateValueException(String campo) {
        return new UsuarioDuplicadoException(campo);
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
