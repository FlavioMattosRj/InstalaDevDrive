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

    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_RESET = "[0m";

    private static volatile boolean verbose = false;

    private PowerShellRunner() {
    }

    /**
     * Liga/desliga o modo verboso, que imprime (em uma cor diferente) cada
     * comando PowerShell efetivamente executado antes de roda-lo.
     */
    public static void setVerbose(boolean value) {
        verbose = value;
    }

    /**
     * Executa uma unica expressao/script PowerShell (util para consultas
     * rapidas, como checagem de elevacao, ou para o comando de formatacao
     * final via Format-Volume).
     */
    public static ProcessResult runCommand(String command) {
        List<String> args = buildArgs(TrustedExecutables.powershellPath(), command);
        if (verbose) {
            logCommand(command);
        }
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

    private static void logCommand(String command) {
        String indented = command.replace(System.lineSeparator(), System.lineSeparator() + "  ");
        System.out.println(ANSI_CYAN + "[PowerShell] " + indented + ANSI_RESET);
    }
}
