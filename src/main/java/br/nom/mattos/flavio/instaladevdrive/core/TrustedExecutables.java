package br.nom.mattos.flavio.instaladevdrive.core;

import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.ShlObj;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolve os caminhos absolutos de powershell.exe e diskpart.exe.
 *
 * O diretorio System32 e obtido via SHGetFolderPath/CSIDL_SYSTEM (chamada
 * nativa ao Windows, atraves do JNA) em vez da variavel de ambiente
 * %SystemRoot%: variaveis de usuario (sem privilegio de administrador)
 * podem sobrepor variaveis de sistema no ambiente herdado por este
 * processo, entao confiar em %SystemRoot% permitiria a um atacante local
 * redirecionar a resolucao para uma pasta sob seu controle.
 *
 * Isso fecha a lacuna de seguranca original: como este programa roda
 * elevado (Administrador) e chamava "powershell.exe"/"diskpart.exe" sem
 * caminho, o CreateProcess do Windows os procurava primeiro no diretorio do
 * proprio executavel e no diretorio de trabalho atual - so depois em
 * System32 - abrindo espaco para um binario forjado ali ser executado com
 * privilegios administrativos (binary planting).
 *
 * @author flavio mattos
 */
final class TrustedExecutables {

    private static final Path SYSTEM32 = resolveSystem32();

    private static final Path POWERSHELL =
            resolveUnder(SYSTEM32, "WindowsPowerShell", "v1.0", "powershell.exe");
    private static final Path DISKPART =
            resolveUnder(SYSTEM32, "diskpart.exe");

    private TrustedExecutables() {
    }

    static String powershellPath() {
        return POWERSHELL.toString();
    }

    static String diskpartPath() {
        return DISKPART.toString();
    }

    private static Path resolveSystem32() {
        String path = Shell32Util.getFolderPath(ShlObj.CSIDL_SYSTEM);
        return Paths.get(path).toAbsolutePath().normalize();
    }

    /**
     * Monta o caminho a partir de System32 e valida que o resultado (a)
     * ainda esta dentro de System32 e (b) aponta para um arquivo que
     * realmente existe - nunca confia cegamente na concatenacao de caminho.
     */
    private static Path resolveUnder(Path system32, String... relativeParts) {
        Path candidate = system32;
        for (String part : relativeParts) {
            candidate = candidate.resolve(part);
        }
        Path resolved = candidate.toAbsolutePath().normalize();

        if (!resolved.startsWith(system32)) {
            throw new IllegalStateException("Caminho resolvido fora de System32: " + resolved);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Executavel esperado nao encontrado: " + resolved);
        }
        return resolved;
    }
}
