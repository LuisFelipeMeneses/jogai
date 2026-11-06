package br.ufms.jogai.domain.emprestimo;

import br.ufms.jogai.domain.shared.Page;
import br.ufms.jogai.domain.shared.Repository;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface EmprestimoRepository extends Repository<Emprestimo, UUID> {
    CompletableFuture<Page<Emprestimo, UUID>> listarPorProprietário(String proprietario, int pageSize, UUID afterId);
    CompletableFuture<Page<Emprestimo, UUID>> listarPorTomador(String tomador, int pageSize, UUID afterId);
}
