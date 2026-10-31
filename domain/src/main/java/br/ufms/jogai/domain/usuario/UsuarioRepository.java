package br.ufms.jogai.domain.usuario;

import br.ufms.jogai.domain.shared.Repository;
import br.ufms.jogai.domain.shared.Page;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UsuarioRepository extends Repository<Usuario, UUID> {

    /** Cadastra um novo usuário, rejeitando id, email ou telefone já utilizados. */
    CompletableFuture<Usuario> cadastrar(Usuario usuario);

    /** Cadastra todos os usuários ou rejeita o lote inteiro em caso de conflito. */
    CompletableFuture<Collection<Usuario>> cadastrarTodos(Collection<Usuario> usuarios);

    CompletableFuture<Page<Usuario, UUID>> listarPorNome(String nome, int pageSize, UUID afterId);

    default CompletableFuture<Page<Usuario, UUID>> listarPorNome(String nome, int pageSize) {
        return listarPorNome(nome, pageSize, null);
    }

    CompletableFuture<Optional<Usuario>> buscarPorEmail(String email);

    CompletableFuture<Optional<Usuario>> buscarPorTelefone(String telefone);

    CompletableFuture<Page<Usuario, UUID>> listarPorStatus(StatusUsuario status, int pageSize, UUID afterId);

    default CompletableFuture<Page<Usuario, UUID>> listarPorStatus(StatusUsuario status, int pageSize) {
        return listarPorStatus(status, pageSize, null);
    }
}
