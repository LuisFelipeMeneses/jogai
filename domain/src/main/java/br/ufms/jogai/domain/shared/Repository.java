package br.ufms.jogai.domain.shared;

import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface Repository<E extends Entidade<ID>, ID extends Serializable> {

    CompletableFuture<E> save(E entity);

    CompletableFuture<Void> delete(ID id);

    CompletableFuture<Optional<E>> get(ID id);

    /** Retrieves one bounded page ordered by document ID; null starts at the beginning. */
    CompletableFuture<Page<E, ID>> getPage(int pageSize, ID afterId);

    /** Retrieves the first bounded page. */
    default CompletableFuture<Page<E, ID>> getPage(int pageSize) {
        return getPage(pageSize, null);
    }
}
