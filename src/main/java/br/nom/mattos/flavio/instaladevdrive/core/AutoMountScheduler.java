package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Registra, via Agendador de Tarefas do Windows, a remontagem automatica do
 * disco virtual (VHDX) a cada inicializacao do sistema.
 *
 * Um VHDX anexado via DISKPART fica montado apenas ate o proximo
 * desligamento/reinicio: o Windows nao o reconecta sozinho no boot seguinte.
 * A forma nativa de tornar isso persistente ({@code Mount-VHD -Persistent})
 * exige o modulo Hyper-V, que este projeto propositalmente evita (ver
 * {@link DevDriveCreator}). Por isso, uma tarefa agendada roda - a cada boot,
 * antes mesmo do login, como SYSTEM - um script minimo do DISKPART que apenas
 * seleciona o vdisk pelo caminho do arquivo e o anexa; a letra de unidade
 * volta sozinha porque o Windows a associa ao GUID do volume, nao a sessao de
 * anexacao.
 *
 * @author flavio mattos
 */
public final class AutoMountScheduler {

    private static final String TASK_NAME_PREFIX = "InstalaDevDrive - AutoMount - ";

    private AutoMountScheduler() {
    }

    /**
     * Nome da tarefa agendada, derivado do rotulo/nome do Dev Drive. Nomes de
     * tarefa nao podem conter os caracteres reservados de nomes de arquivo do
     * Windows; eles sao substituidos por "_".
     */
    public static String taskName(String label) {
        String sanitized = label.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return TASK_NAME_PREFIX + (sanitized.isEmpty() ? "DevDrive" : sanitized);
    }

    /**
     * Caminho do script de remontagem, persistido ao lado do proprio VHDX
     * (precisa sobreviver a reinicializacoes, ao contrario de um arquivo
     * temporario).
     */
    public static Path scriptPath(Path vhdPath) {
        return vhdPath.resolveSibling(vhdPath.getFileName().toString() + ".automount.diskpart.txt");
    }

    /**
     * Script do DISKPART que apenas reanexa o disco virtual ja existente -
     * nunca cria/particiona/formata nada, apenas "select vdisk" + "attach
     * vdisk", pelo caminho do arquivo (nunca por numero de disco).
     */
    static String buildAttachScript(Path vhdPath) {
        String path = vhdPath.toAbsolutePath().toString();
        return "select vdisk file=\"" + path + "\"" + System.lineSeparator()
                + "attach vdisk" + System.lineSeparator()
                + "exit" + System.lineSeparator();
    }

    /**
     * Monta os argumentos do schtasks.exe que cria/atualiza a tarefa
     * agendada: dispara no boot ("ONSTART"), roda como SYSTEM (nao depende de
     * nenhum usuario logado) com privilegio maximo, e sobrescreve ("/F") uma
     * tarefa de mesmo nome se ja existir.
     */
    static List<String> buildCreateTaskArgs(String taskName, Path scriptPath) {
        String runCommand = "diskpart.exe /s \"" + scriptPath.toAbsolutePath() + "\"";
        return Arrays.asList(
                "schtasks.exe", "/Create",
                "/TN", taskName,
                "/TR", runCommand,
                "/SC", "ONSTART",
                "/RU", "SYSTEM",
                "/RL", "HIGHEST",
                "/F"
        );
    }

    static List<String> buildDeleteTaskArgs(String taskName) {
        return Arrays.asList("schtasks.exe", "/Delete", "/TN", taskName, "/F");
    }

    /**
     * Escreve o script de remontagem e registra a tarefa agendada que o
     * executa a cada boot. Pode ser chamado tanto logo apos criar um Dev
     * Drive quanto, mais tarde, para registrar a remontagem automatica de um
     * Dev Drive ja existente (sem tocar no disco/particao/formatacao).
     */
    public static ProcessResult register(String label, Path vhdPath) {
        Path script = scriptPath(vhdPath);
        try {
            Files.write(script, buildAttachScript(vhdPath).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel gravar o script de remontagem automatica em " + script, e);
        }

        ProcessResult result = ProcessRunner.execute(buildCreateTaskArgs(taskName(label), script));
        if (!result.success()) {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
                // melhor esforco: nao mascarar o erro original do schtasks
            }
        }
        return result;
    }

    /**
     * Remove a tarefa agendada e o script de remontagem associado. Melhor
     * esforco: nunca lanca excecao (usado em reversao apos falha).
     */
    public static void unregister(String label, Path vhdPath) {
        try {
            ProcessRunner.execute(buildDeleteTaskArgs(taskName(label)));
        } catch (RuntimeException ignored) {
            // melhor esforco
        }
        try {
            Files.deleteIfExists(scriptPath(vhdPath));
        } catch (IOException ignored) {
            // melhor esforco
        }
    }
}
