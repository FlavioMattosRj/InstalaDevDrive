package br.nom.mattos.flavio.instaladevdrive.core;

/**
 * Verifica se o processo Java atual esta sendo executado com privilegios de
 * Administrador no Windows, requisito obrigatorio para criar/formatar
 * volumes.
 *
 * @author flavio mattos
 */
public final class ElevationChecker {

    /**
     * Extraido como constante (visivel para testes) para permitir validar o
     * texto do comando sem precisar executar um processo PowerShell real.
     */
    static final String CHECK_COMMAND =
            "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())"
                    + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)";

    private ElevationChecker() {
    }

    public static boolean isElevated() {
        ProcessResult result = PowerShellRunner.runCommand(CHECK_COMMAND);
        if (!result.success()) {
            throw new IllegalStateException("Nao foi possivel verificar o nivel de privilegio: " + result.stderr());
        }
        return result.stdout().trim().equalsIgnoreCase("True");
    }
}
