package br.ufms.jogai.data;

import br.ufms.jogai.data.repository.UsuarioFirestoreRepository;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;

public class Main {

    static void main() {
        try {
            var repository = new UsuarioFirestoreRepository();

            var usuario = Usuario.create(
                    "Kleber Kruger",
                    "kleberkruger@gmail.com",
                    "67996122809",
                    LocalDate.of(1988, 12, 8)
            );

            repository.getPage(50)
                    .thenAccept(page -> {
                        IO.println("Página de usuários cadastrados:");
                        for (Usuario u : page.items()) {
                            IO.println("- " + u.getNome());
                        }
                        page.nextCursor().ifPresent(cursor -> IO.println("Próximo cursor: " + cursor));
                    })
                    .get();

            repository.create(usuario)
                    .thenAccept(u -> IO.println(u.getNome() + " inserido com sucesso"))
                    .get();

            repository.get(usuario.getId()).thenAccept(
                    opt -> opt.ifPresentOrElse(
                            u -> System.out.println("Encontrado o: " + u.getNome()),
                            () -> IO.println("Usuário não encontrado")
                    )).get();

            repository.getPage(50)
                    .thenAccept(page -> {
                        IO.println("Página de usuários cadastrados:");
                        for (Usuario u : page.items()) {
                            IO.println("- " + u.getNome());
                        }
                        page.nextCursor().ifPresent(cursor -> IO.println("Próximo cursor: " + cursor));
                    })
                    .get();

//            repository.delete(usuario.getId())
//                    .thenAccept(unused -> System.out.println("Usuario deletado com sucesso"))
//                    .get();

            repository.getPage(50)
                    .thenAccept(page -> {
                        IO.println("Página de usuários cadastrados:");
                        for (Usuario u : page.items()) {
                            IO.println("- " + u.getNome());
                        }
                        page.nextCursor().ifPresent(cursor -> IO.println("Próximo cursor: " + cursor));
                    })
                    .get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Operação interrompida.");
        } catch (ExecutionException e) {
            Throwable causa = e;
            while (causa.getCause() != null && !(causa instanceof UsuarioDuplicadoException)) {
                causa = causa.getCause();
            }
            System.err.println(causa.getMessage());
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
}
