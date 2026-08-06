package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.List;

/**
 * Executa scripts do DISKPART de forma controlada.
 *
 * Regras de seguranca adotadas por esta classe (e pelo codigo que gera os
 * scripts, em {@link DevDriveCreator}):
 * <ul>
 *   <li>O conteudo do script e sempre gerado inteiramente pelo programa, a
 *       partir de valores ja validados - nunca a partir de texto livre
 *       digitado pelo usuario.</li>
 *   <li>O disco alvo e sempre selecionado por caminho de arquivo
 *       ("select vdisk file=..."), nunca por numero ("select disk N"). Isso
 *       elimina o principal risco classico do DISKPART: acertar o disco
 *       fisico errado por engano/numeracao inesperada.</li>
 *   <li>O script roda com um tempo limite (ver {@link ProcessRunner}), para
 *       nunca travar esperando confirmacao interativa.</li>
 * </ul>
 *
 * @author flavio mattos
 */
public final class DiskpartRunner {

    private DiskpartRunner() {
    }

    /**
     * Executa um script do DISKPART. Em modo verboso (ver {@link VerboseLog}),
     * imprime o script antes de roda-lo.
     */
    public static ProcessResult runScript(String scriptContent) {
        VerboseLog.log("DISKPART", scriptContent);
        Path tempScript = createRestrictedTempScript(scriptContent);

        try {
            List<String> command = Arrays.asList(
                    TrustedExecutables.diskpartPath(), "/s", tempScript.toAbsolutePath().toString());
            return ProcessRunner.execute(command);
        } finally {
            try {
                Files.deleteIfExists(tempScript);
            } catch (IOException ignored) {
                // arquivo temporario; falha na limpeza nao deve interromper o fluxo
            }
        }
    }

    /**
     * Cria o arquivo temporario do script e restringe sua ACL ao dono antes
     * de gravar qualquer conteudo, removendo entradas herdadas do diretorio
     * temporario (que podem ser mais amplas do que o necessario). O script
     * contem apenas caminhos de arquivo ja validados, mas nao deve ficar
     * legivel por outros usuarios locais enquanto existe no disco.
     */
    private static Path createRestrictedTempScript(String scriptContent) {
        try {
            Path tempScript = Files.createTempFile("instaladevdrive-", ".diskpart.txt");
            restrictToOwner(tempScript);
            Files.write(tempScript, scriptContent.getBytes(StandardCharsets.UTF_8));
            return tempScript;
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel criar o script do DISKPART", e);
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (aclView == null) {
            return;
        }

        UserPrincipal owner = aclView.getOwner();
        AclEntry ownerFullControl = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.values())
                .build();
        aclView.setAcl(List.of(ownerFullControl));
    }
}
