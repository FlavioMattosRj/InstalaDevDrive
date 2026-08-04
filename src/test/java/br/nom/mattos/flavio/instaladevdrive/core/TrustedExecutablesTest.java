package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * So roda no Windows: a resolucao chama a API nativa do sistema (via JNA),
 * indisponivel em outras plataformas. As demais suites deste projeto evitam
 * essa dependencia de plataforma de proposito (ver PowerShellRunnerTest).
 *
 * @author flavio mattos
 */
@EnabledOnOs(OS.WINDOWS)
class TrustedExecutablesTest {

    @Test
    void powershellPathApontaParaArquivoRealDentroDeSystem32() {
        Path resolved = Paths.get(TrustedExecutables.powershellPath());

        assertTrue(resolved.isAbsolute());
        assertTrue(Files.isRegularFile(resolved), "powershell.exe resolvido nao existe: " + resolved);
        assertTrue(resolved.toString().toLowerCase().endsWith("\\windowspowershell\\v1.0\\powershell.exe"));
        assertTrue(containsSystem32(resolved));
    }

    @Test
    void diskpartPathApontaParaArquivoRealDentroDeSystem32() {
        Path resolved = Paths.get(TrustedExecutables.diskpartPath());

        assertTrue(resolved.isAbsolute());
        assertTrue(Files.isRegularFile(resolved), "diskpart.exe resolvido nao existe: " + resolved);
        assertTrue(resolved.toString().toLowerCase().endsWith("\\diskpart.exe"));
        assertTrue(containsSystem32(resolved));
    }

    private static boolean containsSystem32(Path resolved) {
        for (Path part : resolved) {
            if (part.toString().equalsIgnoreCase("System32")) {
                return true;
            }
        }
        return false;
    }
}
