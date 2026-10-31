package br.ufms.jogai.domain.usuario;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.util.Validar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

public class Usuario extends Entidade<UUID> {

    private String nome;
    private String email;
    private String telefone;
    private LocalDate dataNascimento;
    private final Instant dataCadastro;
    private StatusUsuario status;

    private Usuario(
            UUID id,
            String nome,
            String email,
            String telefone,
            LocalDate dataNascimento,
            Instant dataCadastro,
            StatusUsuario status
    ) {
        super(id);

        setNome(nome);
        setEmail(email);
        setTelefone(telefone);
        setDataNascimento(dataNascimento);

        this.dataCadastro = Validar.dataCadastro(dataCadastro);
        this.status = Objects.requireNonNull(status, "O status do usuário não pode ser nulo");
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = Validar.nomePessoa(nome, true);
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizarEmail(email);
    }

    /** Representação canônica usada pelo usuário e pelas consultas por email. */
    public static String normalizarEmail(String email) {
        return Validar.email(Objects.requireNonNull(email).strip(), true).toLowerCase(Locale.ROOT);
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String telefone) {
        this.telefone = Validar.telefone(telefone, true);
    }

    public LocalDate getDataNascimento() {
        return dataNascimento;
    }

    public void setDataNascimento(LocalDate dataNascimento) {
        this.dataNascimento = Validar.dataNascimento(dataNascimento, true);
    }

    public static int idade(LocalDate dataNascimento) {
        Objects.requireNonNull(dataNascimento, "A data de nascimento não pode ser nula");
        return Period.between(dataNascimento, LocalDate.now()).getYears();
    }

    public static boolean idadeIgualOuMaiorQue(LocalDate dataNascimento, int idade) {
        return idade(dataNascimento) >= idade;
    }

    public int getIdade() {
        return idade(dataNascimento);
    }

    public boolean isMaiorIdade() {
        return idadeIgualOuMaiorQue(dataNascimento, 18);
    }

    public Instant getDataCadastro() {
        return dataCadastro;
    }

    public StatusUsuario getStatus() {
        return status;
    }

    public void ativar() {
        this.status = StatusUsuario.ATIVO;
    }

    public void inativar() {
        this.status = StatusUsuario.INATIVO;
    }

    public void bloquear() {
        this.status = StatusUsuario.BLOQUEADO;
    }

    public static Usuario create(
            String nome,
            String email,
            String telefone,
            LocalDate dataNascimento
    ) {
        return new Usuario(
                UUID.randomUUID(),
                nome,
                email,
                telefone,
                dataNascimento,
                Instant.now(),
                StatusUsuario.ATIVO
        );
    }

    /**
     * Reconstrói o objeto.
     *
     * @param id id
     * @param nome nome
     * @param email email
     * @param telefone telefone
     * @param dataNascimento data de nascimento
     * @param dataCadastro data de cadastro
     * @param status status
     *
     * @return o objeto Usuario
     */
    public static Usuario reconstitute(
            UUID id,
            String nome,
            String email,
            String telefone,
            LocalDate dataNascimento,
            Instant dataCadastro,
            StatusUsuario status
    ) {
        return new Usuario(
                id,
                nome,
                email,
                telefone,
                dataNascimento,
                dataCadastro,
                status
        );
    }
}
