package br.ufms.jogai.domain.jogo;

import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.shared.Repository;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ExemplarRepository extends Repository<Exemplar, UUID> {
    CompletableFuture<Page<Exemplar, UUID>> buscarExemplaresPorJogo(String nomeJogo, int pageSize, UUID afterId);

    CompletableFuture<Page<Exemplar, UUID>> buscarExemplaresPorUsuario(String nomeUsuario, int pageSize, UUID afterId);
}
