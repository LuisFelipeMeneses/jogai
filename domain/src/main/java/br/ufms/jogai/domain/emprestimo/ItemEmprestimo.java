package br.ufms.jogai.domain.emprestimo;

import br.ufms.jogai.domain.jogo.EstadoConservacao;
import br.ufms.jogai.domain.jogo.Exemplar;
import br.ufms.jogai.domain.shared.Entidade;

import java.time.Instant;
import java.util.UUID;

public class ItemEmprestimo extends Entidade<UUID> {

    private Exemplar exemplar;
    private Instant dataEfetivaDevolucao;

    public ItemEmprestimo(UUID id, Exemplar exemplar, Instant dataEfetivaDevolucao) {
        super(id);
        this.exemplar = exemplar;
        this.dataEfetivaDevolucao = dataEfetivaDevolucao;
    }

    public Exemplar getExemplar() {
        return exemplar;
    }

    public Instant getDataEfetivaDevolucao() {
        return dataEfetivaDevolucao;
    }

    @Override
    public UUID getId() {
        return super.getId();
    }

    public void setDataEfetivaDevolucao(Instant dataEfetivaDevolucao) {
        this.dataEfetivaDevolucao = dataEfetivaDevolucao;
    }

    public static ItemEmprestimo create(Exemplar exemplar) {
        ItemEmprestimo item = new ItemEmprestimo(UUID.randomUUID(), exemplar, null);
        return item;
    }

    public void relatarDano(EstadoConservacao novoEstado, String texto) {
        exemplar.setEstadoConservacao(novoEstado);
    }
}
