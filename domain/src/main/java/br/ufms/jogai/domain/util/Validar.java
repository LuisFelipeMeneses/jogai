package br.ufms.jogai.domain.util;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class Validar {

    public static <T> T notNull(T object, String mensagem) {
        if (object == null) {
            throw new IllegalArgumentException(mensagem);
        }
        return object;
    }

    public static <T, E extends RuntimeException> T require(T object, Supplier<E> exceptionSupplier) {
        if (object == null) {
            throw exceptionSupplier.get();
        }
        return object;
    }

    /**
     * Valida o nome de uma pessoa física.
     *
     * @param nome nome
     * @return nome validado
     * @throws IllegalArgumentException caso o nome da pessoa seja inválido
     */
    public static String nomePessoa(String nome) {
        return nomePessoa(nome, false);
    }

    /**
     * Valida o nome de uma pessoa física.
     *
     * @param nome     nome
     * @param completo verdadeiro quando for necessário nome e sobrenome
     * @return nome validado
     * @throws IllegalArgumentException caso o nome da pessoa seja inválido
     */
    public static String nomePessoa(String nome, boolean completo) {
        String normalizado = nome == null ? null : Normalizer.normalize(nome, Normalizer.Form.NFC);
        String parte = "[\\p{L}][\\p{L}\\p{M}]*(?:[-'’][\\p{L}][\\p{L}\\p{M}]*)*";
        return new ValidadorString("Nome", normalizado)
                .validarNulo()
                .validarPorExpressao(parte + "(?: +" + parte + ")*", "Caracteres ou separadores não permitidos")
                .validarTamanho(3, 50)
                .validarValor(valor -> {
                    if (completo && valor.split(" +").length < 2) {
                        throw new IllegalArgumentException("Nome incompleto. Informe o sobrenome");
                    }
                }).getValor(ValidadorString::removerEspacosAdicionais);
    }

    /**
     * Valida o nome de uma pessoa jurídica.
     *
     * @param nome nome
     * @return nome validado
     * @throws IllegalArgumentException caso o nome da empresa seja inválido
     */
    public static String nomeEmpresa(String nome) {
        return new ValidadorString("Nome", nome)
                .validarNulo()
                .validarTamanho(1, 50)
                .getValor(ValidadorString::removerEspacosAdicionais);
    }

    /**
     * Valida o nome de um jogo
     *
     * @param nome nome do jogo
     * @return nome validado
     */
    public static String nomeJogo(String nome) {
        return nomeCatalogo(nome, "Nome do jogo", 1, 50);
    }

    /**
     * Valida o nome de uma editora
     *
     * @param nome nome da editora
     * @return nome validado
     */
    public static String nomeEditora(String nome) {
        return nomeCatalogo(nome, "Nome da editora", 1, 50);
    }

    /**
     * Valida o nome de uma categoria de jogo
     *
     * @param nome nome da categoria
     * @return nome da categoria validado
     */
    public static String nomeCategoriaJogo(String nome) {
        return nomeCatalogo(nome, "Nome da categoria", 2, 30);
    }

    private static String nomeCatalogo(String valor, String campo, int minimo, int maximo) {
        if (valor != null && valor.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException(campo + " contém caracteres não permitidos");
        }
        if (valor != null && valor.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException(campo + " não pode estar em branco");
        }
        return new ValidadorString(campo, valor).validarNulo().validarTamanho(minimo, maximo)
                .getValor(ValidadorString::removerEspacosAdicionais);
    }

    public static String chaveCategoria(String chave) {
        Objects.requireNonNull(chave, "O id não pode ser nulo");
        return nomeCatalogo(chave, "Chave da categoria", 1, Integer.MAX_VALUE);
    }

    public static Instant dataCadastro(Instant data) {
        Objects.requireNonNull(data, "A data de cadastro não pode ser nula");
        if (data.isAfter(Instant.now())) {
            throw new IllegalArgumentException("A data de cadastro não pode ser futura");
        }
        return data;
    }

    public static <T> Set<T> categoriasJogo(Set<T> categorias) {
        if (categorias == null || categorias.isEmpty() || categorias.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("O jogo deve possuir pelo menos uma categoria e nenhuma categoria nula");
        }
        return Set.copyOf(categorias);
    }

    /**
     * Valida o ano de lançamento do jogo
     *
     * @param anoLancamento ano de lançamento como inteiro
     * @return o ano de lançamento
     */
    public static Year anoLancamentoJogo(Integer anoLancamento) {
        return anoLancamentoJogo(anoLancamento == null ? null : Year.of(anoLancamento));
    }

    /**
     * Valida o ano de lançamento do jogo
     *
     * @param anoLancamento ano de lançamento como inteiro
     * @param obrigatorio   verdadeiro quando obrigatório
     * @return o ano de lançamento
     */
    public static Year anoLancamentoJogo(Integer anoLancamento, boolean obrigatorio) {
        return anoLancamentoJogo(anoLancamento == null ? null : Year.of(anoLancamento), obrigatorio);
    }

    public static Year anoLancamentoJogo(Year anoLancamento) {
        return anoLancamentoJogo(anoLancamento, false);
    }

    public static Year anoLancamentoJogo(Year anoLancamento, boolean obrigatorio) {
        if (anoLancamento == null) {
            if (obrigatorio) {
                throw new IllegalArgumentException("O ano de lançamento é obrigatório");
            }
            return null;
        }

        if (anoLancamento.isAfter(Year.now())) {
            throw new IllegalArgumentException("O ano de lançamento não pode ser futuro: " + anoLancamento);
        } else if (anoLancamento.isBefore(Year.of(1))) {
            throw new IllegalArgumentException("O ano de lançamento deve ser posterior a 1");
        }

        return anoLancamento;
    }

    /**
     * Valida a razão social de uma pessoa jurídica.
     *
     * @param razaoSocial razão social
     * @return razão social validada
     * @throws IllegalArgumentException caso a razão social seja inválida
     */
    public static String razaoSocial(String razaoSocial) {
        return razaoSocial(razaoSocial, true);
    }

    /**
     * Valida a razão social de uma pessoa jurídica.
     *
     * @param razaoSocial razão social
     * @param obrigatorio verdadeiro caso o valor seja obrigatório
     * @return razão social validada
     * @throws IllegalArgumentException caso a razão social seja inválida
     */
    public static String razaoSocial(String razaoSocial, boolean obrigatorio) {
        return new ValidadorString("Razão social", razaoSocial, obrigatorio)
                .validarNulo()
                .validarTamanho(3, 80)
                .validarPorExpressao(".*[\\p{L}].*", "Informe ao menos uma letra")
                .getValor(ValidadorString::removerEspacosAdicionais);
    }

    /**
     * Valida um número de telefone.
     *
     * @param telefone número de telefone
     * @return número de telefone validado
     * @throws IllegalArgumentException caso o número de telefone seja inválido
     */
    public static String telefone(String telefone) {
        return telefone(telefone, true);
    }

    /**
     * Valida um número de telefone.
     *
     * @param telefone    número de telefone
     * @param obrigatorio verdadeiro caso o valor seja obrigatório
     * @return número de telefone validado
     * @throws IllegalArgumentException caso o número de telefone seja inválido
     */
    public static String telefone(String telefone, boolean obrigatorio) {
        String ddd = "(?:1[1-9]|2[12478]|3[1-578]|4[1-9]|5[1345]|6[1-9]|7[134579]|8[1-9]|9[1-9])";
        String assinante = "(?:[2-5]\\d{7}|9\\d{8})";
        String formatado = "(?:[2-5]\\d{3}-\\d{4}|9\\d{4}-\\d{4})";
        String regex = ddd + assinante + "|\\(" + ddd + "\\) " + formatado
                + "|\\+55 " + ddd + " " + formatado;
        return new ValidadorString("Telefone", telefone, obrigatorio)
                .validarNulo()
                .validarPorExpressao(regex, "Use somente números ou algum formato de telefone brasileiro válido")
                .getValor(t -> t.replaceAll("\\+55|\\D", ""));
    }

    /**
     * Valida um email.
     *
     * @param email email
     * @return email validado
     * @throws IllegalArgumentException caso o email seja inválido
     */
    public static String email(String email) {
        return email(email, true);
    }

    /**
     * Valida um email.
     *
     * @param email       email
     * @param obrigatorio verdadeiro caso o valor seja obrigatório
     * @return email validado
     * @throws IllegalArgumentException caso o email seja inválido
     */
    public static String email(String email, boolean obrigatorio) {
        String local = "[a-zA-Z0-9_%+-]+(?:\\.[a-zA-Z0-9_%+-]+)*";
        String rotulo = "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?";
        String regex = "^" + local + "@(?:" + rotulo + "\\.)+[a-zA-Z]{2,63}$";
        return new ValidadorString("Email", email, obrigatorio)
                .validarNulo()
                .validarTamanho(3, 50)
                .validarPorExpressao(regex)
                .getValor();
    }

    /**
     * Valida um nome de usuário.
     *
     * @param usuario nome de usuário
     * @return nome de usuário validado
     * @throws IllegalArgumentException caso o nome de usuário seja inválido
     */
    public static String usuario(String usuario) {
        String regex = "^(?!.*([._])\\1)(?!.*\\.$)(?!^\\.)[a-zA-Z0-9_]+(?:[._][a-zA-Z0-9_]+)*_?$";
        return new ValidadorString("Usuário", usuario)
                .validarNulo()
                .validarTamanho(3, 30)
                .validarPorExpressao(regex)
                .getValor();
    }

    /**
     * Valida uma senha.
     *
     * @param senha senha
     * @return senha validada
     * @throws IllegalArgumentException caso a senha seja inválida
     */
    public static String senha(String senha) {
        return senha(senha, false);
    }

    /**
     * Valida uma senha.
     *
     * @param senha    senha
     * @param numerica verdadeiro caso seja uma senha numérica
     * @return senha validada
     * @throws IllegalArgumentException caso a senha seja inválida
     */
    public static String senha(String senha, boolean numerica) {
        return new ValidadorString("Senha", senha, null)
                .validarNulo()
                .validarTamanho(6, 30)
                .validarPorExpressao(numerica ? "^\\d+$" : null)
                .getValor();
    }

    /**
     * Valida uma data de nascimento.
     *
     * @param dataNascimento data de nascimento
     * @return data de nascimento validada
     * @throws IllegalArgumentException caso a data de nascimento seja inválida
     */
    public static LocalDate dataNascimento(LocalDate dataNascimento) {
        return dataNascimento(dataNascimento, true);
    }

    /**
     * Valida uma data de nascimento.
     *
     * @param data        data de nascimento
     * @param obrigatorio verdadeiro caso o valor seja obrigatório
     * @return data de nascimento validada
     * @throws IllegalArgumentException caso a data de nascimento seja inválida
     */
    public static LocalDate dataNascimento(LocalDate data, boolean obrigatorio) {
        if (data == null) {
            if (obrigatorio) throw new IllegalArgumentException("A data de nascimento não pode ser nula");
            return null;
        }
        LocalDate hoje = LocalDate.now();
        if (data.isAfter(hoje) || data.isBefore(hoje.minusYears(150))) {
            throw new IllegalArgumentException("Data de nascimento inválida");
        }
        return data;
    }

    /**
     * Valida se uma data e hora está entre um determinado período
     *
     * @param campo           Nome do campo
     * @param dataHoraInicial data e hora inicial
     * @param dataHora        data e hora a ser validada
     * @param dataHoraFinal   data e hora final
     * @return a data e hora validada
     */
    public static LocalDateTime estaEntrePeriodo(String campo, LocalDateTime dataHoraInicial, LocalDateTime dataHora,
                                                 LocalDateTime dataHoraFinal) {
        return estaEntrePeriodo(campo, dataHoraInicial, dataHora, dataHoraFinal, true);
    }

    /**
     * Valida se uma data e hora está entre um determinado período
     *
     * @param campo           Nome do campo
     * @param dataHoraInicial data e hora inicial
     * @param dataHora        data e hora a ser validada
     * @param dataHoraFinal   data e hora final
     * @param obrigatorio     verdadeiro caso o campo seja obrigatório
     * @return a data e hora validada
     */
    public static LocalDateTime estaEntrePeriodo(String campo, LocalDateTime dataHoraInicial, LocalDateTime dataHora,
                                                 LocalDateTime dataHoraFinal, boolean obrigatorio) {
        return new Validador<>(Objects.requireNonNull(campo, "Nome do campo nulo"), dataHora, obrigatorio)
                .validar((attr, date) -> date.ifPresent(d -> {
                    if (d.isAfter(dataHoraFinal) || d.isBefore(dataHoraInicial)) {
                        throw new IllegalArgumentException(attr + " inválida");
                    }
                })).getValor();
    }

    /**
     * Valida um CPF.
     *
     * @param cpf data de nascimento
     * @return CPF validado
     * @throws IllegalArgumentException caso o CPF seja inválido
     */
    public static String cpf(String cpf) {
        return new ValidadorString("CPF", cpf)
                .validarNulo()
                .validarPorExpressao("\\d{11}|\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}")
                .validarValor(Validar::validarCPF)
                .getValor(ValidadorString::manterSomenteNumeros);
    }

    private static void validarCPF(String cpf) {
        if (cpf.matches("^(\\d)\\1*$")) {
            throw new IllegalArgumentException("CPF inválido: " + cpf);
        }

        int[] digitos = cpf.chars().map(Character::getNumericValue).toArray();

        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += digitos[i] * (10 - i);
        }

        int resto = soma % 11;
        int digitoVerificador1 = (resto < 2) ? 0 : 11 - resto;

        if (digitos[9] != digitoVerificador1) {
            throw new IllegalArgumentException("CPF inválido: " + cpf);
        }

        soma = 0;
        for (int i = 0; i < 10; i++) {
            soma += digitos[i] * (11 - i);
        }

        resto = soma % 11;
        int digitoVerificador2 = (resto < 2) ? 0 : 11 - resto;

        if (digitos[10] != digitoVerificador2) {
            throw new IllegalArgumentException("CPF inválido");
        }
    }

    /**
     * Valida um CNPJ.
     *
     * @param cnpj data de nascimento
     * @return CNPJ validado
     * @throws IllegalArgumentException caso o CNPJ seja inválido
     */
    public static String cnpj(String cnpj) {
        return new ValidadorString("CNPJ", cnpj)
                .validarNulo()
                .validarPorExpressao("\\d{14}|\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}")
                .validarValor(Validar::validarCNPJ)
                .getValor(ValidadorString::manterSomenteNumeros);
    }

    private static void validarCNPJ(String cnpj) {
        if (cnpj.matches("^(\\d)\\1*$")) {
            throw new IllegalArgumentException("CNPJ inválido: " + cnpj);
        }

        int[] digitos = cnpj.chars().map(Character::getNumericValue).toArray();

        int soma = 0;
        int peso = 2;
        for (int i = 11; i >= 0; i--) {
            soma += digitos[i] * peso;
            peso++;
            if (peso == 10) peso = 2;
        }

        int digitoVerificador1 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);
        if (digitos[12] != digitoVerificador1) {
            throw new IllegalArgumentException("CNPJ inválido: " + cnpj);
        }

        soma = 0;
        peso = 2;
        for (int i = 12; i >= 0; i--) {
            soma += digitos[i] * peso;
            peso++;
            if (peso == 10) peso = 2;
        }

        int digitoVerificador2 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);
        if (digitos[13] != digitoVerificador2) {
            throw new IllegalArgumentException("CNPJ inválido");
        }
    }
}