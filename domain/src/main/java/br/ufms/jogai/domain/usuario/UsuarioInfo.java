package br.ufms.jogai.domain.usuario;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

public record UsuarioInfo(
        UUID id,
        String nome,
        String email,
        String telefone,
        LocalDate dataNascimento
) {

    public UsuarioInfo(Usuario usuario) {
        this(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getTelefone(), usuario.getDataNascimento());
    }

    public int idade() {
        return Usuario.idade(dataNascimento);
    }

    public boolean isMaiorIdade() {
        return Usuario.idadeIgualOuMaiorQue(dataNascimento, 18);
    }
}
