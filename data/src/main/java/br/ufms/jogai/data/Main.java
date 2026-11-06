package br.ufms.jogai.data;

import br.ufms.jogai.data.repository.*;
import br.ufms.jogai.domain.emprestimo.Emprestimo;
import br.ufms.jogai.domain.emprestimo.ItemEmprestimo;
import br.ufms.jogai.domain.jogo.*;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioDuplicadoException;
import br.ufms.jogai.domain.usuario.UsuarioInfo;
import br.ufms.jogai.domain.usuario.UsuarioRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

public class Main {

    static void main() {
        try {
            UsuarioRepository repository = new UsuarioFirestoreRepository();

            var usuario = Usuario.create(
                    "Kleber Kruger",
                    "kleberkruger@gmail.com",
                    "67996122809",
                    LocalDate.of(1988, 12, 8)
            );

            Usuario usuario1 = Usuario.create(
                    "Luis Felipe",
                    "luis@gmail.com",
                    "67999999999",
                    LocalDate.of(2007, 5, 22)
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

            repository.create(usuario1)
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

            var repositoryJogo = new JogoFirestoreRepository();
            var repositoryCategoriaJogo = new CategoriaJogoFirestoreRepository();
            var repositoryExemplar = new ExemplarFirestoreRepository();
            var repositoryEmprestimo = new EmprestimoFirestoreRepository();

            var categoria = CategoriaJogo.create("teste", "Aventura", "Jogo de aventura");
            repositoryCategoriaJogo.create(categoria)
                    .thenAccept(u -> IO.println(u.getNome() + " inserido com sucesso"))
                    .get();

            var jogo = Jogo.create()
                    .nomeEditoraLancamento("Minecraft", "Mojang", 2009)
                    .descricao("O melhor jogo da história humana")
                    .numeroJogadores(1, 12)
                    .faixaEtaria(0, 99)
                    .categorias(categoria)
                    .duracaoPartida(10, 20)
                    .build();

            repositoryJogo.create(jogo)
                    .thenAccept(u -> IO.println(u.getNome() + " inserido com sucesso"))
                    .get();
            var exemplar = Exemplar.create(new JogoInfo(jogo), new UsuarioInfo(usuario), EstadoConservacao.BOM, "Exemplar de teste");

            repositoryExemplar.create(exemplar)
                    .thenAccept(u -> IO.println(u.getJogoInfo().nome() + " inserido com sucesso"))
                    .get();

            ItemEmprestimo itemEmprestimo = ItemEmprestimo.create(exemplar);
            Emprestimo emprestimo = Emprestimo.create( new UsuarioInfo(usuario), new UsuarioInfo(usuario1), LocalDateTime.now(), LocalDate.now().plusDays(7), Collections.singletonList(itemEmprestimo));

            repositoryEmprestimo.create(emprestimo)
                    .thenAccept(u -> IO.println("Empréstimo inserido com sucesso"))
                    .get();

//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            System.err.println("Operação interrompida.");
//        } catch (ExecutionException e) {
//            Throwable causa = e;
//            while (causa.getCause() != null && !(causa instanceof UsuarioDuplicadoException)) {
//                causa = causa.getCause();
//            }
//            System.err.println(causa.getMessage());
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
}
