package br.nom.mattos.flavio.instaladevdrive.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executa o {@code fsutil.exe} (padrao em qualquer Windows) resolvido por
 * caminho absoluto de System32, como {@link PowerShellRunner} e
 * {@link DiskpartRunner}.
 *
 * <p>O uso previsto e {@code fsutil devdrv query <letra>:}, do qual o
 * programa le apenas o <em>codigo de saida</em> - nunca o texto impresso,
 * que e localizado. Manter a decisao presa a um valor numerico (0 = e Dev
 * Drive) segue a mesma regra do resto do projeto: nao depender do idioma do
 * Windows.
 *
 * @author flavio mattos
 */
public final class FsutilRunner {

    private FsutilRunner() {
    }

    public static ProcessResult run(String... args) {
        List<String> command = buildCommand(TrustedExecutables.fsutilPath(), args);
        VerboseLog.log("fsutil", String.join(" ", command));
        return ProcessRunner.execute(command);
    }

    /**
     * Monta a lista de argumentos do processo. Extraido (visivel para
     * testes) para verificar a montagem sem resolver o caminho real do
     * executavel nem executar nada - mesmo padrao de
     * {@link PowerShellRunner#buildArgs(String, String)}.
     */
    static List<String> buildCommand(String executablePath, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executablePath);
        command.addAll(Arrays.asList(args));
        return command;
    }
}
