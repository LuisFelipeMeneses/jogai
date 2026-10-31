# Cadastro de usuários

`UsuarioRepository.cadastrar(usuario)` cria um usuário e rejeita id, email ou
telefone já utilizados. `save(usuario)` mantém o comportamento de criar ou
atualizar, mas aplica a mesma garantia de unicidade. Os conflitos completam o
`CompletableFuture` excepcionalmente com `UsuarioDuplicadoException`, que informa
o campo em conflito (podendo estar encapsulada por exceções assíncronas).

Emails são armazenados em minúsculas, sem espaços nas extremidades. Telefones
seguem a normalização brasileira já realizada por `Validar.telefone`.

O repositório grava o usuário e suas reservas em uma única coleção
`valores_unicos`, na mesma transação. Cada documento contém `entidade`, `campo` e
`entidadeId`. Sua chave combina o tipo de entidade, o campo e o valor normalizado,
com cada parte codificada em Base64 URL e separada por ponto. Isso evita colisões
entre campos e entidades diferentes. Base64 é reversível, não criptografia.

`ValoresUnicosFirestoreRepository` centraliza a construção das chaves, a detecção
de conflitos e a reserva/liberação de valores. Não depende do domínio de usuário
nem inicia transações. A classe `FirestoreRepositoryComValoresUnicos` centraliza
a transação completa e oferece `cadastrar`, `save` e `delete`. Cada repositório
concreto declara os campos e suas funções de normalização e implementa somente
`entityToMap` e `mapToEntity`. A reconstrução também serve para capturar o resultado
independente da gravação, sem um método `copiar`. `UsuarioFirestoreRepository` traduz os
conflitos em `UsuarioDuplicadoException`.

O guia [Repositórios com valores únicos](docs/repositorios-com-valores-unicos.md)
mostra como criar outro repositório, com o exemplo compilado de uma categoria.

Cada tentativa de transação cria uma `Operacao` de reservas. `reservar` e
`liberar` fazem as leituras e preparam as alterações em memória; `aplicar` apenas
agenda essas escritas na transação recebida. Depois de `aplicar`, não são aceitas
novas leituras nessa operação. O chamador deve terminar também suas próprias
leituras antes de aplicá-la. Apenas o sucesso de `runTransaction` confirma as
reservas e o usuário juntos. Falhas no callback ou no commit não aplicam nenhuma
parte da operação.

Uma atualização aceita valores pertencentes ao próprio usuário, rejeita valores
pertencentes a outra pessoa e libera os valores anteriores quando mudam. Se um
dos campos conflitar, nem o outro campo nem suas reservas são alterados.
Exclusões liberam apenas reservas pertencentes ao usuário excluído.

A primeira gravação de cada repositório lê seus documentos existentes e prepara
as reservas na própria transação. A coleção `valores_unicos_controle` registra a
inicialização por coleção, escopo e conjunto de campos. Os antigos marcadores
`usuarios_indices/unicidade_v1` e `unicidade_v2` não substituem esse controle;
as reservas já existentes em `valores_unicos` são reutilizadas, sem trocar suas
chaves ou proprietários. Cadastros posteriores consultam documentos específicos.
As antigas coleções `usuarios_unicos_email` e `usuarios_unicos_telefone` não são
usadas; seus documentos não são apagados automaticamente. Todos os processos que
ainda usam as coleções separadas devem ser atualizados antes de retomar gravações.

Duplicidades preexistentes interrompem a inicialização sem gravações parciais e
precisam ser corrigidas antes de tentar novamente. Essa inicialização atende à
base inicial do projeto; bases grandes exigem migração planejada por causa dos
limites de tamanho e duração das transações. Não foi feita migração nem qualquer
acesso ao banco real durante o desenvolvimento.

Todas as gravações de usuários devem passar por este repositório no backend.
Escritas diretas no console ou por outros serviços podem violar a unicidade.
Não remova nem edite as reservas ou o marcador de inicialização isoladamente.
O SDK de servidor usa credenciais administrativas e não é limitado pelas
Firestore Security Rules; clientes não devem ter permissão de escrita direta
nessas coleções.

## Testes de integração

Com um emulador Firestore em execução na porta 8185:

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8185 ./gradlew :data:test
```

Os testes usam projetos `demo-jogai-*` isolados, sem credenciais reais. Sem a
variável `FIRESTORE_EMULATOR_HOST`, são explicitamente ignorados.
Cobrem conflitos de email e telefone, normalização, atualizações parciais ou
completas, exclusões, inicialização e transição do marcador antigo. Incluem
cadastros concorrentes, atualizações concorrentes de usuários diferentes e do
mesmo usuário, disputa entre cadastro e atualização, falha no callback e falha
no commit. Verificam que cada usuário persistido tem exatamente suas duas reservas
e que operações rejeitadas não deixam reservas órfãs. O componente compartilhado
também é testado quanto a isolamento de campos/entidades e propriedade das reservas.

O exemplo `CategoriaExemploRepository`, em `src/test/java`, usa a mesma classe
base para garantir código único. Seus testes cobrem criação, alteração, exclusão,
normalização, concorrência e documentos preexistentes.
