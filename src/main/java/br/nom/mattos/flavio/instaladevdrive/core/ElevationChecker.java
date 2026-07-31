package br.nom.mattos.flavio.instaladevdrive.core;

/**
 * Verifica se o processo Java atual esta sendo executado com privilegios de
 * Administrador no Windows, requisito obrigatorio para criar/formatar
 * volumes.
 *
 * @author flavio mattos
 */
public final class ElevationChecker {

    private ElevationChecker() {
    }

    public static boolean isElevated() {
        ProcessResult result = PowerShellRunner.runCommand(
                "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())"
                        + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
        );
        if (!result.success()) {
            throw new IllegalStateException("Nao foi possivel verificar o nivel de privilegio: " + result.stderr());
        }
        return result.stdout().trim().equalsIgnoreCase("True");
    }
}
