package br.ufms.jogai.domain.shared;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A bounded result and an exclusive cursor for the next page of the same query. */
public record Page<E, ID>(List<E> items, Optional<ID> nextCursor) {
    public Page {
        items = List.copyOf(items);
        nextCursor = Objects.requireNonNull(nextCursor);
    }
}
