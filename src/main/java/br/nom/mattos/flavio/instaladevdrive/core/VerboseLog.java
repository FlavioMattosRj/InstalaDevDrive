package br.nom.mattos.flavio.instaladevdrive.core;

/**
 * Log opcional (modo --verbose) das acoes efetivamente executadas -
 * comandos PowerShell e scripts DISKPART - impressas em destaque antes de
 * cada execucao. Estado compartilhado entre {@link PowerShellRunner} e
 * {@link DiskpartRunner} para que uma unica chamada a {@link #setEnabled}
 * ligue o modo verboso para as duas fontes de comando.
 *
 * @author flavio mattos
 */
public final class VerboseLog {

    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_RESET = "[0m";

    private static volatile boolean enabled = false;

    private VerboseLog() {
    }

    /**
     * Liga/desliga o modo verboso para ambas as fontes de comando
     * (PowerShell e DISKPART).
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Imprime {@code content} destacado (em ciano) e prefixado com
     * {@code [label]}, indentando linhas subsequentes para ficar claro onde
     * cada bloco comeca e termina. Nao faz nada se o modo verboso estiver
     * desligado.
     */
    static void log(String label, String content) {
        if (!enabled) {
            return;
        }
        String indented = content.replace(System.lineSeparator(), System.lineSeparator() + "  ");
        System.out.println(ANSI_CYAN + "[" + label + "] " + indented + ANSI_RESET);
    }
}
