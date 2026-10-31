package br.ufms.jogai.domain.usuario;

public class UsuarioDuplicadoException extends RuntimeException {

    private final String campo;

    public UsuarioDuplicadoException(String campo) {
        super("Já existe um usuário com este " + campo + ".");
        this.campo = campo;
    }

    public String getCampo() {
        return campo;
    }
}
