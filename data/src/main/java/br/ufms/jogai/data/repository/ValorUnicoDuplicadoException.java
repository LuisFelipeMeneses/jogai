package br.ufms.jogai.data.repository;

/** Conflito de infraestrutura, traduzido pelo repositório da entidade. */
public class ValorUnicoDuplicadoException extends RuntimeException {

    private final String campo;

    public ValorUnicoDuplicadoException(String campo) {
        super("O valor do campo " + campo + " já pertence a outra entidade.");
        this.campo = campo;
    }

    public String getCampo() {
        return campo;
    }
}
