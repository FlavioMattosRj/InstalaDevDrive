package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static ProcessResult runScript(String scriptContent) {
        Path tempScript;
        try {
            tempScript = Files.createTempFile("instaladevdrive-", ".diskpart.txt");
            Files.write(tempScript, scriptContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel criar o script do DISKPART", e);
        }

        try {
            List<String> command = Arrays.asList(
                    "diskpart.exe", "/s", tempScript.toAbsolutePath().toString());
            return ProcessRunner.execute(command);
        } finally {
            try {
                Files.deleteIfExists(tempScript);
            } catch (IOException ignored) {
                // arquivo temporario; falha na limpeza nao deve interromper o fluxo
            }
        }
    }
}
