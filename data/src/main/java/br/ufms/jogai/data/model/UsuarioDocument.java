package br.ufms.jogai.data.model;

import br.ufms.jogai.domain.usuario.StatusUsuario;
import br.ufms.jogai.domain.usuario.Usuario;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.UUID;

public record UsuarioDocument(
        @DocumentId String id,
        String nome,
        @Unique String email,
        @Unique String telefone,
        Timestamp nascimento,
        Timestamp cadastradoEm,
        String status
) implements EntityDocument<Usuario> {
    public UsuarioDocument(Usuario usuario) {
        this(
                usuario.getId().toString(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.getTelefone(),
                EntityDocument.toTimestamp(usuario.getDataNascimento()),
                EntityDocument.toTimestamp(usuario.getDataCadastro()),
                usuario.getStatus().name()
        );
    }

    public Usuario toEntity() {
        return Usuario.reconstitute(
                UUID.fromString(id),
                nome,
                email,
                telefone,
                EntityDocument.toLocalDate(nascimento),
                EntityDocument.toInstant(cadastradoEm),
                StatusUsuario.valueOf(status)
        );
    }

//    public Map<String, Object> toMap() {
//        return Map.of(
//                "nome", nome,
//                "email", email,
//                "telefone", telefone,
//                "nascimento", nascimento,
//                "cadastradoEm", cadastradoEm,
//                "status", status
//        );
//    }
//
//    public UsuarioDocument(DocumentSnapshot doc) {
//        this(
//                doc.getId(),
//                doc.getString("nome"),
//                doc.getString("email"),
//                doc.getString("telefone"),
//                doc.getTimestamp("nascimento"),
//                doc.getTimestamp("cadastradoEm"),
//                doc.getString("status")
//        );
//    }
}
