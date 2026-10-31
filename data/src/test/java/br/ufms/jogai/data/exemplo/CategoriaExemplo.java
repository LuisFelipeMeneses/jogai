package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.domain.shared.Entidade;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade didática usada para demonstrar a reutilização sem alterar o domínio do projeto.
 */
public final class CategoriaExemplo extends Entidade<UUID> {

    private final String nome;
    private String codigo;

    public CategoriaExemplo(UUID id, String nome, String codigo) {
        super(id);
        this.nome = Objects.requireNonNull(nome).strip();
        setCodigo(codigo);
    }

    public String getNome() {
        return nome;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = normalizarCodigo(codigo);
    }

    public static String normalizarCodigo(String codigo) {
        String normalizado = Objects.requireNonNull(codigo).strip().toUpperCase(Locale.ROOT);
        if (normalizado.isBlank()) throw new IllegalArgumentException("Código obrigatório");
        return normalizado;
    }
}
