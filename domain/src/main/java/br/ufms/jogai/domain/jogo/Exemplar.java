package br.ufms.jogai.domain.jogo;

import br.ufms.jogai.domain.shared.Entidade;
import br.ufms.jogai.domain.util.Validar;
import br.ufms.jogai.domain.usuario.Usuario;
import br.ufms.jogai.domain.usuario.UsuarioInfo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Exemplar extends Entidade<UUID> {

    private final JogoInfo jogo;
    private final UsuarioInfo proprietario;
    private EstadoConservacao estadoConservacao;
    private String observacao;
    private final Instant dataCadastro;

    private Exemplar(
            UUID id,
            JogoInfo jogoInfo,
            UsuarioInfo proprietarioInfo,
            EstadoConservacao estadoConservacao,
            String observacao,
            Instant dataCadastro
    ) {
        super(id);

        this.jogo = Objects.requireNonNull(
                jogoInfo, "As informações do jogo não podem ser nulas");
        this.proprietario = Objects.requireNonNull(
                proprietarioInfo, "As informações do proprietário do jogo não podem ser nulas");
        Validar.notNull(jogoInfo.id(), "O id do jogo não pode ser nulo");
        Validar.nomeJogo(jogoInfo.nome());
        Validar.nomeEditora(jogoInfo.editora());
        Validar.categoriasJogo(jogoInfo.categorias());
        Validar.notNull(proprietarioInfo.id(), "O id do proprietário não pode ser nulo");
        Validar.nomePessoa(proprietarioInfo.nome(), true);
        Validar.notNull(proprietarioInfo.email(), "O email do proprietário não pode ser nulo");
        Usuario.normalizarEmail(proprietarioInfo.email());
        Validar.telefone(proprietarioInfo.telefone());
        Validar.dataNascimento(proprietarioInfo.dataNascimento());
        this.dataCadastro = Validar.dataCadastro(dataCadastro);

        setEstadoConservacao(estadoConservacao);
        setObservacao(observacao);
    }

    public JogoInfo getJogoInfo() {
        return jogo;
    }

    public UsuarioInfo getProprietarioInfo() {
        return proprietario;
    }

    public EstadoConservacao getEstadoConservacao() {
        return estadoConservacao;
    }

    public boolean isJogavel() {
        return estadoConservacao != EstadoConservacao.DANIFICADO;
    }

    public boolean isDanificado() {
        return estadoConservacao == EstadoConservacao.DANIFICADO;
    }

    public void setEstadoConservacao(EstadoConservacao estadoConservacao) {
        this.estadoConservacao = Objects.requireNonNull(
                estadoConservacao, "O estado de conservação não pode ser nulo");
    }

    public String getObservacao() {
        return observacao;
    }

    public void setObservacao(String observacao) {
        this.observacao = observacao != null ? observacao.trim() : null;
    }

    public Instant getDataCadastro() {
        return dataCadastro;
    }

    public static Exemplar create(
            JogoInfo jogoInfo,
            UsuarioInfo proprietarioInfo,
            EstadoConservacao estadoConservacao,
            String observacao
    ) {
        return new Exemplar(
                UUID.randomUUID(),
                jogoInfo,
                proprietarioInfo,
                estadoConservacao,
                observacao,
                Instant.now()
        );
    }

    public static Exemplar reconstitute(
            UUID id,
            JogoInfo jogoInfo,
            UsuarioInfo proprietarioInfo,
            EstadoConservacao estadoConservacao,
            String observacao,
            Instant dataCadastro
    ) {
        return new Exemplar(
                id,
                jogoInfo,
                proprietarioInfo,
                estadoConservacao,
                observacao,
                dataCadastro
        );
    }
}
