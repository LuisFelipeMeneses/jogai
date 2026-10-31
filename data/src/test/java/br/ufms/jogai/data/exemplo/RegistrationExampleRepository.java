package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.data.repository.UsuarioFirestoreRepository;
import br.ufms.jogai.domain.usuario.Usuario;
import com.google.cloud.firestore.Firestore;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/** Example of a child repository creating documents in two collections in one transaction. */
public final class RegistrationExampleRepository extends UsuarioFirestoreRepository {
    /** Captured entities are published only when both documents and all reservations commit. */
    public record Registration(Usuario user, CategoriaExemplo category) {}

    public RegistrationExampleRepository(Firestore firestore) {
        super(firestore);
    }

    public CompletableFuture<Registration> register(Usuario user, CategoriaExemploRepository categories,
                                                    CategoriaExemplo category) {
        return writeTransaction(writes -> new Registration(
                writes.create(this, user), writes.create(categories, category)));
    }

    /** Creates or fully replaces both documents, preserving the same unique-value checks. */
    public CompletableFuture<Registration> saveRegistration(Usuario user, CategoriaExemploRepository categories,
                                                            CategoriaExemplo category) {
        return writeTransaction(writes -> new Registration(
                writes.save(this, user), writes.save(categories, category)));
    }

    /** Deletes the two known documents and their reservations in one commit. */
    public CompletableFuture<Void> removeRegistration(UUID userId, CategoriaExemploRepository categories,
                                                      UUID categoryId) {
        return writeTransaction(writes -> {
            writes.delete(this, userId);
            writes.delete(categories, categoryId);
            return null;
        });
    }
}
