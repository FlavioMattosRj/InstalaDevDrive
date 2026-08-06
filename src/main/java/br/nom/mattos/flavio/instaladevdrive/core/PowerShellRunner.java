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
     * final via Format-Volume). Em modo verboso (ver {@link VerboseLog}),
     * imprime o comando antes de roda-lo.
     */
    public static ProcessResult runCommand(String command) {
        List<String> args = buildArgs(TrustedExecutables.powershellPath(), command);
        VerboseLog.log("PowerShell", command);
        return ProcessRunner.execute(args);
    }

    /**
     * Monta a lista de argumentos do processo powershell.exe. Extraido como
     * metodo separado (visivel para testes) para permitir verificar a
     * geracao do comando sem precisar executar um processo real nem
     * resolver o caminho real de System32 - por isso recebe o caminho do
     * executavel como parametro em vez de resolve-lo aqui.
     */
    static List<String> buildArgs(String executablePath, String command) {
        return Arrays.asList(
                executablePath,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", command
        );
    }
}
