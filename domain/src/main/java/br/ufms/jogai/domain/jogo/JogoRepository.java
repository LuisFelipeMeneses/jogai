package br.ufms.jogai.domain.jogo;

import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.shared.Repository;
import br.ufms.jogai.domain.usuario.Usuario;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface JogoRepository extends Repository<Jogo, UUID> {
    CompletableFuture<Optional<Jogo>> buscarJogoPorNome(String nomeJogo);
    CompletableFuture<Page<Jogo, UUID>> listarJogosPorCategoria(String nomeCategoria, int pageSize, UUID afterId);
}
