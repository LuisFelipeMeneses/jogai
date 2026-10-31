package br.ufms.jogai.domain.jogo;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.util.Validar;

import java.time.Duration;
import java.time.Year;
import java.util.*;

public class Jogo extends Entidade<UUID> {
    private String nome;
    private String editora;
    private Year anoLancamento;
    private NumeroJogadores numeroJogadores;
    private FaixaEtaria faixaEtaria;
    private DuracaoPartida duracaoPartida;
    private final Set<CategoriaJogo> categorias;
    private String descricao;
    private boolean ativo;

    private Jogo(
            UUID id,
            String nome,
            String editora,
            Year anoLancamento,
            NumeroJogadores numeroJogadores,
            FaixaEtaria faixaEtaria,
            DuracaoPartida duracaoPartida,
            Set<CategoriaJogo> categorias,
            String descricao,
            boolean ativo
    ) {
        super(id);

        setNome(nome);
        setEditora(editora);
        setAnoLancamento(anoLancamento);
        setNumeroJogadores(numeroJogadores);
        setFaixaEtaria(faixaEtaria);
        setDuracaoPartida(duracaoPartida);
        setDescricao(descricao);

        this.categorias = new HashSet<>(Validar.categoriasJogo(categorias));
        this.ativo = ativo;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = Validar.nomeJogo(nome);
    }

    public String getEditora() {
        return editora;
    }

    public void setEditora(String editora) {
        this.editora = Validar.nomeEditora(editora);
    }

    public Year getAnoLancamento() {
        return anoLancamento;
    }

    public void setAnoLancamento(Year anoLancamento) {
        this.anoLancamento = Validar.anoLancamentoJogo(anoLancamento);
    }

//    public NumeroJogadores getNumeroJogadores() {
//        return numeroJogadores;
//    }

    public int getMinimoJogadores() {
        return numeroJogadores.minimo();
    }

    public Integer getMaximoJogadores() {
        return numeroJogadores.maximo();
    }

    void setNumeroJogadores(NumeroJogadores numeroJogadores) {
        this.numeroJogadores = Validar.notNull(numeroJogadores, "O número de jogadores não pode ser nulo");
    }

    public FaixaEtaria getFaixaEtaria() {
        return faixaEtaria;
    }

    public Integer getIdadeMinimaRecomendada() {
        return faixaEtaria.idadeMinima();
    }

    public Integer getIdadeMaximaRecomendada() {
        return faixaEtaria.idadeMaxima();
    }

    public void setFaixaEtaria(FaixaEtaria faixaEtaria) {
        this.faixaEtaria = faixaEtaria == null ? new FaixaEtaria() : faixaEtaria;
    }

    public Duration getTempoMinimoEstimado() {
        return duracaoPartida.minima();
    }

    public Duration getTempoMaximoEstimado() {
        return duracaoPartida.maxima();
    }

    public Duration getTempoEstimado() {
        return duracaoPartida.estimativa();
    }

    void setDuracaoPartida(DuracaoPartida duracaoPartida) {
        this.duracaoPartida = duracaoPartida == null ? new DuracaoPartida() : duracaoPartida;
    }

    public void adicionarCategoria(CategoriaJogo categoria) {
        if (categoria == null) {
            throw new IllegalArgumentException("A categoria não pode ser nula");
        }
        categorias.add(categoria);
    }

    public void adicionarCategorias(CategoriaJogo... categorias) {
        if (categorias == null) {
            throw new IllegalArgumentException("As categorias não podem ser nulas");
        }
        if (Arrays.stream(categorias).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A categoria não pode ser nula");
        }
        Collections.addAll(this.categorias, categorias);
    }

    public void removerCategoria(CategoriaJogo categoria) {
        if (!categorias.contains(categoria)) {
            return;
        }
        if (categorias.size() == 1) {
            throw new IllegalStateException("O jogo deve possuir pelo menos uma categoria");
        }
        categorias.remove(categoria);
    }

    public void removerCategorias(CategoriaJogo... categorias) {
        if (categorias == null) {
            throw new IllegalArgumentException("As categorias não podem ser nulas");
        }
        Set<CategoriaJogo> aRemover = new HashSet<>(Arrays.asList(categorias));
        if (!this.categorias.containsAll(aRemover)) {
            aRemover.retainAll(this.categorias);
        }
        if (this.categorias.size() - aRemover.size() < 1) {
            throw new IllegalStateException("O jogo deve possuir pelo menos uma categoria");
        }
        this.categorias.removeAll(aRemover);
    }

    public Set<CategoriaJogo> getCategorias() {
        return Collections.unmodifiableSet(categorias);
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao != null ? descricao.trim() : null;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void ativar() {
        this.ativo = true;
    }

    public void inativar() {
        this.ativo = false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder create() {
        return new Builder(UUID.randomUUID(), true);
    }

    public static Builder reconstitute(UUID id, boolean ativo) {
        return new Builder(id, ativo);
    }

    public static class Builder {

        private UUID id;
        private String nome;
        private String editora;
        private Year anoLancamento;
        private NumeroJogadores numeroJogadores;
        private FaixaEtaria faixaEtaria;
        private DuracaoPartida duracaoPartida;
        private Set<CategoriaJogo> categorias;
        private String descricao;
        private Boolean ativo;

        public Builder() {
        }

        public Builder(UUID id, boolean ativo) {
            this.id = id;
            this.ativo = ativo;
        }

        public Builder nomeEditora(String nome, String editora) {
            return nomeEditoraLancamento(nome, editora, null);
        }

        public Builder nomeEditoraLancamento(String nome, String editora, Integer anoLancamento) {
            this.nome = nome;
            this.editora = editora;
            this.anoLancamento = anoLancamento != null ? Year.of(anoLancamento) : null;
            return this;
        }

        public Builder numeroJogadores(int minimo, Integer maximo) {
            this.numeroJogadores = new NumeroJogadores(minimo, maximo);
            return this;
        }

        public Builder faixaEtaria(Integer idadeMinima, Integer idadeMaxima) {
            this.faixaEtaria = new FaixaEtaria(idadeMinima, idadeMaxima);
            return this;
        }

        public Builder duracaoPartida(Integer tempoMinimoMin, Integer tempoMaximoMin) {
            this.duracaoPartida = new DuracaoPartida(tempoMinimoMin, tempoMaximoMin);
            return this;
        }

        public Builder categorias(CategoriaJogo... categorias) {
            if (categorias == null) {
                throw new IllegalArgumentException("As categorias não podem ser nulas");
            }
            this.categorias = new HashSet<>(Arrays.asList(categorias));
            return this;
        }

        public Builder descricao(String descricao) {
            this.descricao = descricao;
            return this;
        }

        public Jogo create() {
            this.id = UUID.randomUUID();
            this.ativo = ativo == null || ativo;
            return build();
        }

        public Jogo reconstitute(UUID id, boolean ativo) {
            this.id = id;
            this.ativo = ativo;
            return build();
        }

        public Jogo build() {
            return new Jogo(
                    id,
                    nome,
                    editora,
                    anoLancamento,
                    numeroJogadores,
                    faixaEtaria,
                    duracaoPartida,
                    categorias,
                    descricao,
                    ativo
            );
        }
    }
}