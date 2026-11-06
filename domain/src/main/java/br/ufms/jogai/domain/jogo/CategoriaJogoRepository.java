package br.ufms.jogai.domain.jogo;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface CategoriaJogoRepository {
    CompletableFuture<Optional<CategoriaJogo>> buscarCategoriaPorNome(String nomeCategoria);
}
