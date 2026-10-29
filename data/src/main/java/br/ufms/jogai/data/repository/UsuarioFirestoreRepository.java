package br.ufms.jogai.data.repository;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import br.ufms.jogai.domain.usuario.StatusUsuario;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioRepository;
import com.google.cloud.firestore.DocumentSnapshot;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UsuarioFirestoreRepository extends FirestoreRepository<Usuario, UUID> implements UsuarioRepository {

    public UsuarioFirestoreRepository() {
        super(db.collection("usuarios"));
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
    protected Usuario documentToEntity(DocumentSnapshot document) {
        return Usuario.reconstitute(
                idtoUUID(document.getId()),
                document.getString("nome"),
                document.getString("email"),
                document.getString("telefone"),
                toLocalDate(document.getTimestamp("nascimento")),
                toInstant(document.getTimestamp("cadastradoEm")),
                StatusUsuario.valueOf(document.getString("status"))
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

        return queryOne(collection.whereEqualTo("email", email));
    }

    @Override
    public CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone) {
        Objects.requireNonNull(telefone);

        return queryOne(collection.whereEqualTo("telefone", telefone));
    }

    @Override
    public CompletableFuture<Collection<Usuario>> listarPorStatus(StatusUsuario status) {
        Objects.requireNonNull(status);

        return query(collection.whereEqualTo("status", status.name()));
    }
}
