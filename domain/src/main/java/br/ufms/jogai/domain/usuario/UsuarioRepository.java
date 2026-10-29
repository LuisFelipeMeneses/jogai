package br.ufms.jogai.domain.usuario;

import br.ufms.jogai.domain.shared.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UsuarioRepository extends Repository<Usuario, UUID> {

    CompletableFuture<Collection<Usuario>> listarPorNome(String nome);

    CompletableFuture<Optional<Usuario>> buscarPorEmail(String email);

    CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone);

    CompletableFuture<Collection<Usuario>> listarPorStatus(StatusUsuario status);
}
