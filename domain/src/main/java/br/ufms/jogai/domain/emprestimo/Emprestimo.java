package br.ufms.jogai.domain.emprestimo;

import br.ufms.jogai.domain.jogo.Exemplar;
import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.usuario.UsuarioInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

public class Emprestimo extends Entidade<UUID> {

    private UsuarioInfo proprietario;
    private UsuarioInfo tomador;
    private LocalDateTime dataEmprestimo;
    private LocalDate dataDevolucao;

    private Collection<ItemEmprestimo> itens;

    public Emprestimo(UUID uuid, UsuarioInfo proprietario, UsuarioInfo tomador, LocalDateTime dataEmprestimo, LocalDate dataDevolucao, Collection<ItemEmprestimo> itens) {
        super(uuid);
        this.proprietario = proprietario;
        this.tomador = tomador;
        this.dataEmprestimo = dataEmprestimo;
        this.dataDevolucao = dataDevolucao;
        this.itens = itens;
    }

    public UsuarioInfo getProprietario() {
        return proprietario;
    }

    public UsuarioInfo getTomador() {
        return tomador;
    }

    public LocalDate getDataDevolucao() {
        return dataDevolucao;
    }

    public LocalDateTime getDataEmprestimo() {
        return dataEmprestimo;
    }

    public Collection<ItemEmprestimo> getItens() {
        return itens;
    }

    public void addItem(ItemEmprestimo item) {
        itens.add(item);
    }

    public void removeItem(ItemEmprestimo item) {
        itens.remove(item);
    }

    public static Emprestimo create(UsuarioInfo proprietario, UsuarioInfo tomador, LocalDateTime dataEmprestimo, LocalDate dataDevolucao, Collection<ItemEmprestimo> itens) {
        return new Emprestimo(UUID.randomUUID(), proprietario, tomador, dataEmprestimo, dataDevolucao, itens);
    }

    public void adicionarItem(Exemplar exemplar) {
        itens.add(ItemEmprestimo.create(exemplar));
    }
}
