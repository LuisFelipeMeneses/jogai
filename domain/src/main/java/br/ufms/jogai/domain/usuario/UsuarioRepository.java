package br.ufms.jogai.domain.usuario;

import br.ufms.jogai.domain.shared.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UsuarioRepository extends Repository<Usuario, UUID> {

    /** Cadastra um novo usuário, rejeitando id, email ou telefone já utilizados. */
    CompletableFuture<Usuario> cadastrar(Usuario usuario);

    CompletableFuture<Collection<Usuario>> listarPorNome(String nome);

    CompletableFuture<Optional<Usuario>> buscarPorEmail(String email);

    CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone);

    CompletableFuture<Collection<Usuario>> listarPorStatus(StatusUsuario status);
}
