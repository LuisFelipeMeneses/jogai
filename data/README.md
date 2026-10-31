# Cadastro de usuários

`UsuarioRepository.cadastrar(usuario)` cria um usuário e rejeita id, email ou
telefone já utilizados. `save(usuario)` mantém o comportamento de criar ou
atualizar, mas aplica a mesma garantia de unicidade. Os conflitos completam o
`CompletableFuture` excepcionalmente com `UsuarioDuplicadoException`, que informa
o campo em conflito (podendo estar encapsulada por exceções assíncronas).

O domínio fornece emails em minúsculas, sem espaços nas extremidades, e telefones
no formato brasileiro normalizado. O repositório preserva esses valores.
Consultas com parâmetros String reutilizam as regras do domínio
(`Usuario.normalizarEmail` e `Validar.telefone`), pois não recebem uma entidade.

O repositório grava o usuário e suas reservas em uma única coleção
`_uniqueValues_`, definida por `UniqueValuesFirestoreRepository.COLLECTION_PATH`,
na mesma transação. Cada documento contém `entidade`, `campo` e
`entidadeId`. Sua chave combina o escopo de unicidade, o campo e o valor normalizado,
com cada parte codificada em Base64 URL e separada por ponto. Isso evita colisões
entre campos e escopos diferentes. Base64 é reversível, não criptografia.

`UniqueValuesFirestoreRepository` centraliza a construção das chaves, a detecção
de conflitos e a reserva/liberação de valores. Não depende do domínio de usuário
nem inicia transações. A classe `FirestoreRepository` centraliza
a persistência e oferece `create`, `save` e `delete`, com unicidade opcional.
O construtor simples `super(collection)` não configura campos únicos. Nesse
fluxo, o método genérico `create` usa a operação Firestore `create` e rejeita um ID existente atomicamente; `save`
usa `set`, e `delete` exclui diretamente, sem ler reservas ou controles.
Com `super(collection, Set.of(...))`, o repositório mantém documento e
reservas na mesma transação e usa o caminho completo da coleção como escopo.
`UsuarioFirestoreRepository` deriva o escopo `usuarios` da coleção raiz, sem
repetir um escopo manual no construtor. O construtor com escopo explícito continua
disponível. Cada repositório
concreto declara somente os nomes dos campos únicos com `Set.of(...)` e
implementa `entityToMap` e `mapToEntity`. Validação e normalização pertencem ao
domínio; a persistência verifica o tipo dos campos e a exclusividade dos valores. A reconstrução também serve para capturar o resultado
independente da gravação, sem um método `copiar`. `UsuarioFirestoreRepository` traduz os
conflitos em `UsuarioDuplicadoException`.

O guia [Repositórios com valores únicos](docs/repositorios-com-valores-unicos.md)
mostra como criar outro repositório, com o exemplo compilado de uma categoria.

Cada tentativa de transação cria uma `Operation` de reservas. `reserve` e
`release` fazem as leituras e preparam as alterações em memória; `apply` apenas
agenda essas escritas na transação recebida. Depois de `apply`, não são aceitas
novas leituras nessa operação. O chamador deve terminar também suas próprias
leituras antes de aplicá-la. Apenas o sucesso de `runTransaction` confirma as
reservas e o usuário juntos. Falhas no callback ou no commit não aplicam nenhuma
parte da operação.

Uma atualização aceita valores pertencentes ao próprio usuário, rejeita valores
pertencentes a outra pessoa e libera os valores anteriores quando mudam. Se um
dos campos conflitar, nem o outro campo nem suas reservas são alterados.
Exclusões liberam apenas reservas pertencentes ao usuário excluído.

A base começa limpa e todas as gravações usam o mesmo protocolo de reservas.
`create`, `save` e `delete` funcionam imediatamente, sem inicialização ou migração.
Não há marcador de prontidão nem leitura de uma coleção de controle por transação.
Nas atualizações e exclusões, os valores únicos anteriores são extraídos dos
campos canônicos persistidos, sem reconstruir a entidade antiga nem normalizá-la
novamente. A implementação não procura documentos legados sem reservas.

As listagens retornam `Page`, com `items()` e `nextCursor()`. Use `getPage(50)`
e `getPage(50, cursor)`; o tamanho permitido é de 1 a 200. `getAll()` foi removido,
assim como o auxiliar de consultas sem limite. `listarPorNome` e `listarPorStatus`
também recebem tamanho e cursor. A consulta busca no máximo uma página mais um
documento para detectar continuação. Veja o guia de
[paginação](docs/pagination.md).

Todas as gravações de usuários devem passar por este repositório no backend.
Escritas diretas no console ou por outros serviços podem violar a unicidade.
Não remova nem edite as reservas isoladamente.
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
completas e exclusões desde a primeira operação numa base vazia. Incluem
cadastros concorrentes, atualizações concorrentes de usuários diferentes e do
mesmo usuário, disputa entre cadastro e atualização, falha no callback e falha
no commit. Verificam que cada usuário persistido tem exatamente suas duas reservas
e que operações rejeitadas não deixam reservas órfãs. O componente compartilhado
também é testado quanto a isolamento de campos/entidades e propriedade das reservas.

O exemplo `CategoriaExemploRepository`, em `src/test/java`, usa a mesma classe
base para garantir código único. Seus testes cobrem criação, alteração, exclusão,
normalização e concorrência.

A classe separada `FirestoreRepositoryComValoresUnicos` foi incorporada ao
`FirestoreRepository`. Os antigos métodos protegidos de gravação/exclusão bruta
com `Transaction` foram removidos para não oferecer um atalho que ignorasse as
reservas. Os repositórios concretos continuam implementando apenas `entityToMap`
e `mapToEntity`.

O teste `FirestoreRepositoryTest` cobre o fluxo sem campos únicos: valores
repetidos, conflito de ID, concorrência, criação/atualização, consultas, exclusão
idempotente, captura dos dados mutáveis, campos opcionais nulos e propagação de
falhas de persistência. Esse fluxo não cria documentos de reserva ou controle.

A API genérica usa inglês: `create` cria documentos novos e
`duplicateValueException` traduz conflitos. `UsuarioFirestoreRepository.cadastrar`
é um adaptador do contrato de domínio que delega a `create`. A infraestrutura
auxiliar usa `UniqueValuesFirestoreRepository` e `DuplicateUniqueValueException`;
esta última expõe `getField()`. A exceção de domínio continua expondo `getCampo()`.
Os nomes já persistidos em coleções, chaves e campos permanecem compatíveis.

## Desempenho

A análise de operações, memória e limitações de consultas está em
[Persistence performance and correctness](docs/persistence-performance.md).
Reservas já pertencentes à entidade continuam sendo verificadas, mas não são
regravadas. Os testes verificam isso pelo `updateTime` persistido, sem remover
as garantias de concorrência ou de recuperação de reservas ausentes.

As leituras transacionais dos documentos trazem somente os campos únicos. Valores
inalterados continuam tendo a propriedade verificada, sem liberação temporária
seguida de nova reserva. O plano também dispensa um segundo conjunto de
deduplicação de referências, pois já rejeita duas mutações no mesmo documento.
Essas mudanças reduzem dados transferidos e alocações; a quantidade de documentos
lidos para verificar unicidade permanece a mesma.

As leituras de reservas em cada substituição são agrupadas em `getAll` dentro da
transação, solicitando somente o campo do proprietário. Isso reduz chamadas
sequenciais e dados transferidos, mantendo o número de documentos verificados e
a proteção contra conflitos. A suíte inclui seis cadastros simultâneos para o
mesmo email e a rejeição de uma reserva existente sem proprietário.

## Transações com vários documentos

Classes filhas podem usar `writeTransaction(writes -> ...)` e combinar
`writes.create`, `writes.save` e `writes.delete`, inclusive entre repositórios.
O método `UsuarioRepository.cadastrarTodos` é um exemplo pronto de cadastro
atômico. Há também um exemplo compilado de usuário e categoria na mesma transação.

O plano é montado uma única vez, selado e executado com leituras e reservas
compartilhadas. Cada repetição do Firestore cria estado transacional novo. Conflitos
ou falhas desfazem o lote inteiro; trocas de valores únicos são permitidas quando
o estado final é válido. O plano aceita no máximo uma mutação por documento e
não pode ser montado por threads diferentes.

Veja [Atomic multi-document repository writes](docs/multi-document-transactions.md)
para os exemplos, o fluxo, as limitações e os testes concorrentes.

Coleções e subcoleções podem participar juntas. O construtor
`super(collectionReference, Set.of("email"))` usa o caminho completo da coleção
como escopo, isolando subcoleções de pais diferentes. IDs devem ser nomes simples,
sem `/`; o caminho da subcoleção pertence ao `CollectionReference`. Para excluir
um pai e seus filhos no mesmo commit, planeje explicitamente cada exclusão com
`writes.delete`. O exemplo `RegistrationExampleRepository` também cria e exclui
um usuário e sua categoria em uma subcoleção, com testes de rollback e unicidade.

O plano agora compila os metadados da requisição uma vez e libera os mapas usados
apenas na montagem. Tentativas repetidas continuam buscando snapshots e reservas
novos. Em lotes explícitos, `save`/`delete` sem campos únicos dispensam leituras
prévias, mantendo o commit atômico. Criações e campos únicos preservam as leituras
necessárias. Os exemplos das classes filhas usam a mesma API.
