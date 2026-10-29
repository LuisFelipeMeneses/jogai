//package br.ufms.jogai.data.datasource;
//
//import br.ufms.jogai.data.firebase.FirestoreRepository;
//import br.ufms.jogai.data.model.UsuarioDocument;
//import com.google.cloud.firestore.DocumentSnapshot;
//
//import java.util.Map;
//import java.util.Optional;
//import java.util.concurrent.CompletableFuture;
//
//public class UsuarioRepository extends FirestoreRepository<UsuarioDocument, String> {
//
//    public UsuarioRepository() {
//        super(db.collection("usuarios"));
//    }
//
//    @Override
//    protected Optional<String> getId(UsuarioDocument usuarioDocument) {
//        return Optional.empty();
//    }
//
//    @Override
//    protected Map<String, Object> entityToMap(UsuarioDocument usuarioDocument) {
//        return Map.of(
//                "nome", usuarioDocument.nome(),
//                "email", usuarioDocument.email(),
//                "telefone", usuarioDocument.telefone(),
//                "nascimento", usuarioDocument.nascimento(),
//                "cadastradoEm", usuarioDocument.cadastradoEm(),
//                "status", usuarioDocument.status()
//        );
//    }
//
//    @Override
//    protected CompletableFuture<UsuarioDocument> documentToEntity(DocumentSnapshot document) {
//        return CompletableFuture.completedFuture(new UsuarioDocument(
//                document.getId(),
//                document.getString("nome"),
//                document.getString("email"),
//                document.getString("telefone"),
//                document.getTimestamp("nascimento"),
//                document.getTimestamp("cadastradoEm"),
//                document.getString("status")
//        ));
//    }
//}
