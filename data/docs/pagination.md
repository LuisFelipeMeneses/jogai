# Consultas paginadas

O repositório assume uma base inicialmente limpa, com todas as gravações seguindo
o protocolo de reservas. Não há preparação administrativa, marcador de prontidão
ou migração de documentos antigos. `create`, `save` e `delete` funcionam desde a
primeira operação, mantendo documentos e reservas no mesmo commit.

## Ler uma página por vez

`getAll()` foi removido. A substituição retorna um `Page` imutável com os itens e
um cursor opcional:

```java
var users = new UsuarioFirestoreRepository(firestore);
var first = users.getPage(50).get();
for (var user : first.items()) {
    System.out.println(user.getNome());
}
if (first.nextCursor().isPresent()) {
    var second = users.getPage(50, first.nextCursor().orElseThrow()).get();
    // Exiba ou processe second.items(); use seu cursor para continuar.
}
```

O tamanho permitido é de 1 a `MAX_PAGE_SIZE` (200). O método ordena pelo ID do
documento e usa um cursor exclusivo: a página seguinte começa depois do último
item devolvido. Esse cursor continua funcionando se o documento correspondente
for excluído. Cursor vazio indica o fim dos resultados daquela consulta.

A consulta busca no máximo `pageSize + 1` documentos. O documento adicional
permite detectar a próxima página; apenas os itens retornados são reconstruídos
como entidades. Portanto, uma página de 50 pode ler 51 documentos. Não há leitura
por offset nem coleção inteira mantida em memória. Acumular todas as páginas em
uma lista volta a consumir memória proporcional à coleção; processe cada página
e descarte-a quando terminar.

## Filtrar e ordenar nas classes filhas

Use o auxiliar protegido `queryPage`:

```java
public CompletableFuture<Page<Usuario, UUID>> activeUsers(int size, UUID afterId) {
    return queryPage(collection.whereEqualTo("status", "ATIVO").orderBy("nome"),
            size, afterId);
}
```

O `Page` utiliza o ID do último item como cursor. Em consultas filtradas ou com
outra ordenação, o auxiliar busca o documento desse ID e usa seu snapshot para
preservar os valores de ordenação e desempatar corretamente. Isso acrescenta
uma leitura nas páginas posteriores à primeira. Se o documento do cursor deixou
de existir, a operação falha explicitamente; reinicie a consulta se necessário.
Passe uma consulta da coleção do repositório, sem cursor próprio, e reutilize o
cursor somente com os mesmos filtros e ordenação.

O repositório de usuários oferece `listarPorNome(nome, size, afterId)` e
`listarPorStatus(status, size, afterId)`, além das versões sem cursor para a
primeira página. `queryOne` permanece disponível para buscar no máximo um
resultado. O auxiliar de consultas ilimitadas foi removido.

As páginas são leituras independentes. Alterações simultâneas nos dados podem
mudar as páginas seguintes; elas não representam um snapshot congelado da base.
Essa característica das listagens não altera a atomicidade das gravações e das
reservas de campos únicos.
