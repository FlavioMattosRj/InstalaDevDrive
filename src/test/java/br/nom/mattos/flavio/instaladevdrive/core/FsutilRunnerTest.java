package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testa apenas a montagem da linha de comando do fsutil.exe, sem executar
 * processo nenhum (roda em qualquer maquina/CI). Mesma abordagem de
 * {@link PowerShellRunnerTest}.
 *
 * @author flavio mattos
 */
class FsutilRunnerTest {

    private static final String FAKE_EXECUTABLE = "C:\\Fake\\fsutil.exe";

    @Test
    void primeiroElementoEhOExecutavelEOsDemaisSaoOsArgumentosNaOrdem() {
        List<String> command = FsutilRunner.buildCommand(FAKE_EXECUTABLE, "devdrv", "query", "E:");

        assertEquals(List.of(FAKE_EXECUTABLE, "devdrv", "query", "E:"), command);
    }

    @Test
    void semArgumentosRetornaSoOExecutavel() {
        assertEquals(List.of(FAKE_EXECUTABLE), FsutilRunner.buildCommand(FAKE_EXECUTABLE));
    }
}
