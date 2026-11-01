package br.ufms.jogai.data.model;

import br.ufms.jogai.domain.shared.Entidade;

/** Converts a domain entity to the POJO persisted by Firestore. */
@FunctionalInterface
public interface EntityDocumentMapper<E extends Entidade<?>, D extends EntityDocument<E>> {
    D toDocument(E entity);
}
