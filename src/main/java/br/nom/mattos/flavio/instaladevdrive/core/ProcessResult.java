package br.nom.mattos.flavio.instaladevdrive.core;

/**
 * Resultado da execucao de um processo externo: codigo de saida, saida padrao
 * e saida de erro, ja capturadas como texto.
 *
 * @author flavio mattos
 */
public final class ProcessResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public ProcessResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public boolean success() {
        return exitCode == 0;
    }
}
