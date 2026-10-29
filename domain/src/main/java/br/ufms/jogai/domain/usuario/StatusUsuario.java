package br.ufms.jogai.domain.usuario;

public enum StatusUsuario {

    ATIVO("Ativo", "Usuário ativo e apto a realizar empréstimos"),
    BLOQUEADO("Bloqueado", "Usuário bloqueado, impedido de realizar novos empréstimos"),
    INATIVO("Inativo", "Usuário inativo, sem permissão para realizar operações");

    private final String nome;
    private final String descricao;

    StatusUsuario(String nome, String descricao) {
        this.nome = nome;
        this.descricao = descricao;
    }

    public String nome() {
        return nome;
    }

    public String descricao() {
        return descricao;
    }
}
