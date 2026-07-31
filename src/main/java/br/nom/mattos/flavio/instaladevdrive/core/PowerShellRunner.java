package br.nom.mattos.flavio.instaladevdrive.core;

import java.util.Arrays;
import java.util.List;

/**
 * Executa comandos do PowerShell (modulo Storage, sempre presente no
 * Windows) usados para verificacoes e para o passo final de formatacao
 * como Dev Drive. Nao depende do modulo Hyper-V.
 *
 * @author flavio mattos
 */
public final class PowerShellRunner {

    private PowerShellRunner() {
    }

    /**
     * Executa uma unica expressao/script PowerShell (util para consultas
     * rapidas, como checagem de elevacao, ou para o comando de formatacao
     * final via Format-Volume).
     */
    public static ProcessResult runCommand(String command) {
        List<String> args = Arrays.asList(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", command
        );
        return ProcessRunner.execute(args);
    }
}
