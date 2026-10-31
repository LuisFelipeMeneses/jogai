package br.ufms.jogai.domain.jogo;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.util.Validar;


public class CategoriaJogo extends Entidade<String> {

    private String nome;
    private String descricao;

    private CategoriaJogo(String id, String nome, String descricao) {
        super(Validar.chaveCategoria(id));

        setNome(nome);
        setDescricao(descricao);
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = Validar.nomeCategoriaJogo(nome);
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao != null ? descricao.trim() : null;
    }

    public static CategoriaJogo create(String chave, String nome, String descricao) {
        return new CategoriaJogo(chave, nome, descricao);
    }

    public static CategoriaJogo reconstitute(String id, String nome, String descricao) {
        return new CategoriaJogo(id, nome, descricao);
    }
}
