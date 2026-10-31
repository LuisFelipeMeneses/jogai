# Repositórios com valores únicos

A unicidade fica declarada no repositório concreto. A classe base
`FirestoreRepository<E, ID>` executa as transações e delega a
verificação e a manutenção das reservas ao `UniqueValuesFirestoreRepository`.
Nenhum service precisa calcular chaves ou manter reservas.

## Configuração mínima

```java
super(firestore.collection("usuarios"), "usuario", Set.of("email", "telefone"));
```

Os argumentos são a coleção dos documentos, o escopo de exclusividade e o conjunto
de nomes dos campos únicos. Os nomes devem coincidir com os campos
persistidos por `entityToMap`. Cada campo é único independentemente dos demais.
Valores iguais em escopos ou campos distintos não colidem.

O escopo deve ser exclusivo e estável para a entidade/repositório. Não reutilize
o mesmo escopo em coleções independentes: os IDs dos proprietários poderiam
coincidir e tornar as reservas ambíguas. Para um repositório novo, usar o próprio
caminho da coleção como escopo é uma convenção possível. O usuário mantém
`usuario` para preservar as reservas anteriores.

A entidade de domínio é responsável por validar e normalizar seus valores ao
criar, alterar e reconstituir objetos. A classe base não recebe funções de
normalização: usa os campos canônicos produzidos por `entityToMap` e verifica se
os campos configurados existem como String. O mapeamento deve preservar os
valores do domínio.

Para documentos preexistentes, a classe base chama `mapToEntity` antes de extrair
as reservas, tanto na inicialização quanto na alteração ou exclusão. Isso aplica
as invariantes do domínio aos dados legados sem duplicar as regras no módulo
`data`. Se os dados antigos forem inválidos, a operação falha sem escritas
parciais; a correção deve ser feita por migração.

Consultas que recebem uma String, como `buscarPorEmail`, ainda precisam tratar
essa entrada. Elas reutilizam a regra do domínio (`Usuario.normalizarEmail`),
pois uma String recebida não carrega as garantias de uma entidade construída.

O contrato atual suporta campos String obrigatórios, como email, telefone, CPF e
código. Campos opcionais, valores numéricos ou unicidade de uma combinação de
campos exigem uma extensão explícita do contrato.

## Repositório sem campos únicos

A mesma classe atende a entidades que não têm campos exclusivos:

```java
public CategoriaRepository(Firestore firestore) {
    super(firestore.collection("categorias"));
}
```

O construtor simples equivale a configurar um conjunto vazio de campos únicos.
Não há consultas a reservas, inicialização de índices auxiliares nem transações
com múltiplos documentos. `create` usa a precondição atômica de `create` para
rejeitar um ID existente. `save` usa `set` para criar ou atualizar. `delete` faz
uma exclusão direta, inclusive quando o documento já não existe.

Valores iguais em documentos de IDs diferentes são permitidos nesse fluxo.
Uma resposta `ALREADY_EXISTS` de `create` é traduzida por `duplicateValueException("id")`;
outros erros são propagados sem serem confundidos com duplicidade. O contrato de
mapeamento e o resultado independente da entidade original são iguais nos dois
fluxos.

A classe `FirestoreRepositoryComValoresUnicos` foi removida. O nome genérico
`FirestoreRepository` é usado tanto com quanto sem configuração de unicidade.
Os métodos públicos de gravação são finais e os antigos auxiliares protegidos
que gravavam/excluíam diretamente em uma transação foram retirados.

## Exemplo completo: categoria com código único

Os arquivos abaixo estão em `data/src/test/java/br/ufms/jogai/data/exemplo` e são
compilados e exercitados pela suíte. São exemplos didáticos, não novas entidades
de produção. Em uma funcionalidade real, a entidade e o contrato do repositório
ficam em `domain`, e a implementação Firestore fica em `data`.

### Entidade

```java
package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.domain.shared.Entidade;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Entidade didática usada para demonstrar a reutilização sem alterar o domínio do projeto. */
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
```

### Repositório

```java
package br.ufms.jogai.data.exemplo;

import br.ufms.jogai.data.firebase.FirestoreRepository;
import com.google.cloud.firestore.Firestore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exemplo compilado e exercitado pelos testes de integração. */
public final class CategoriaExemploRepository extends FirestoreRepository<CategoriaExemplo, UUID> {

    public CategoriaExemploRepository(Firestore firestore) {
        super(firestore.collection("categorias_exemplo"), "categoria_exemplo",
                Set.of("codigo"));
    }

    @Override
    protected Map<String, Object> entityToMap(CategoriaExemplo categoria) {
        return Map.of(
                "nome", categoria.getNome(),
                "codigo", categoria.getCodigo()
        );
    }

    @Override
    protected CategoriaExemplo mapToEntity(String id, Map<String, Object> dados) {
        return new CategoriaExemplo(
                idToUUID(id),
                (String) dados.get("nome"),
                (String) dados.get("codigo")
        );
    }
}
```

O repositório não implementa manualmente `create`, `save` nem `delete`:
esses métodos finais são herdados da classe base. Eles são finais para evitar
substituir acidentalmente a persistência protegida por uma gravação simples.
`get`, `getAll` e os métodos auxiliares de consultas continuam disponíveis.

O repositório concreto implementa apenas dois mapeamentos:

- `entityToMap(entidade)` captura os dados que serão gravados.
- `mapToEntity(id, dados)` reconstrói uma entidade independente a partir desses dados.

A classe base usa `mapToEntity` tanto na leitura de documentos quanto para
construir o resultado da gravação. Não é necessário implementar `copiar` nem
`documentToEntity`. O ID recebido deve ser preservado, sem gerar outro UUID.
Para entidades com listas ou objetos mutáveis internos, os mapeamentos devem
produzir estruturas independentes. A captura evita que uma edição feita após
iniciar a operação mude os dados usados nas tentativas da transação. Valores
normalizados persistidos e resultado da gravação usam o mesmo mapa.

## Uso pelo service ou exemplo de aplicação

```java
var repository = new CategoriaExemploRepository(firestore);

var estrategia = new CategoriaExemplo(UUID.randomUUID(), "Estratégia", " est ");
repository.create(estrategia).get(); // persiste EST e reserva o código

estrategia.setCodigo("COOP");
repository.save(estrategia).get(); // reserva COOP e libera EST no mesmo commit

repository.save(estrategia).get(); // manter o próprio código é permitido

var outra = new CategoriaExemplo(UUID.randomUUID(), "Família", "COOP");
repository.create(outra).get(); // falha: COOP pertence à primeira categoria

// A tentativa rejeitada não cria a segunda categoria nem altera a primeira.
// A linha abaixo seria executada após tratar a exceção da tentativa anterior.
repository.delete(estrategia.getId()).get(); // libera COOP
```

O uso de `.get()` é apenas para tornar o exemplo sequencial; a API retorna
`CompletableFuture`, permitindo composição assíncrona. `create` rejeita um ID
existente. `save` cria ou atualiza conforme a existência do ID. Ambos rejeitam
valores únicos de outra entidade. `delete` de um ID inexistente é uma operação
sem efeito.

## Tratamento de conflito

Por padrão, o erro é `DuplicateUniqueValueException`, com `getField()` informando
`codigo` ou `id` no exemplo. É possível traduzi-lo em uma exceção do domínio:

```java
@Override
protected RuntimeException duplicateValueException(String campo) {
    return new CategoriaDuplicadaException(campo);
}
```

`CategoriaDuplicadaException` é uma sugestão para uma aplicação real, não uma
classe do exemplo. O repositório de usuários usa esse método para preservar
`UsuarioDuplicadoException`. Com `.get()` ou `.join()`, o erro pode estar
encapsulado por exceções assíncronas; a aplicação deve examinar suas causas.

## Como a atualização permanece atômica

1. A classe base captura os valores já normalizados pela entidade.
2. A transação lê o documento persistido e o controle de inicialização.
3. A operação de reservas verifica todos os valores novos. Uma reserva do próprio
   ID é aceita; a de outro ID causa conflito.
4. A operação prepara a liberação somente dos valores antigos que mudaram.
5. Terminadas todas as leituras, agenda as reservas e o documento na transação.
6. O Firestore confirma tudo junto. Qualquer falha impede todas as gravações.

O componente de valores únicos não oferece um simples `isUnique` seguido de
`save`: essa separação abriria uma janela de concorrência. A verificação está
vinculada à operação transacional de reserva. Cada repetição do callback cria uma
operação nova, evitando reutilizar estado de uma tentativa abortada.

## Inicialização e evolução

Na primeira gravação, a classe base também reserva os valores dos documentos
preexistentes, sem confirmar alterações parciais. Se já existirem duplicidades,
a inicialização falha e elas precisam ser resolvidas antes de prosseguir.

Os documentos de `valores_unicos_controle` identificam a coleção, o escopo e os
nomes dos campos, em ordem estável. Adicionar um campo muda esse controle e exige
verificar os documentos existentes novamente. Remover um campo ou alterar sua
normalização requer uma migração planejada, incluindo limpeza das reservas
obsoletas; isso não é detectado nem resolvido automaticamente. Mudar o escopo
significa criar outro espaço de exclusividade e também exige planejamento.

A inicialização automática atende à base inicial do projeto. Bases grandes
precisam de migração administrativa por causa dos limites transacionais. Todas as
escritas devem passar por esse mecanismo: gravações diretas com credenciais de
servidor podem violar a consistência. As chaves Base64 das reservas são reversíveis.

## Validação

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8185 ./gradlew test
```

Os testes usam apenas o emulador, com projetos isolados. Sem a variável de
ambiente, os testes de integração são ignorados. Há testes de conflitos,
normalização, atualização e liberação, concorrência e rollback de usuário e
reservas, além do mesmo fluxo aplicado à categoria.

As reservas pendentes usam um único mapa de alterações: cada chave termina em
uma gravação ou exclusão, sem manter uma lista de exclusões paralela. Um teste de
regressão verifica reservar, liberar e reservar novamente na mesma operação,
além de cancelar uma reserva ainda não gravada. As chaves e a estrutura dos
documentos existentes permanecem compatíveis.

## API em inglês e adaptação do domínio

A API genérica de criação é `create(entity)`. Os métodos `save`, `delete`, `get`
e `getAll` mantêm seus nomes. O hook de tradução de conflitos é
`duplicateValueException(field)` e a exceção genérica oferece `getField()`.
O contrato existente `UsuarioRepository.cadastrar` é preservado pelo adaptador:

```java
@Override
public CompletableFuture<Usuario> cadastrar(Usuario usuario) {
    return create(usuario);
}
```

O Javadoc de `FirestoreRepository` está em inglês e descreve os contratos de
mapeamento, os modos de persistência, as transações, os erros assíncronos, as
conversões temporais e a inicialização de documentos preexistentes. Pode ser
gerado com `./gradlew :data:javadoc`.
