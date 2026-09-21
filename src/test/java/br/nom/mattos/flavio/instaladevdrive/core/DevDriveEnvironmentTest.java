package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a montagem do valor de {@code DEVDRIVE_ROOTS}, do script PowerShell
 * que aplica as variaveis e o desempate de {@link
 * DevDriveEnvironment#mostRecentlyCreated(List)} - tudo sem executar nenhum
 * processo real nem exigir um Dev Drive montado, mesma abordagem de {@link
 * VirtualDiskInfoTest}. {@code candidates}/{@code confirmed} nao sao
 * exercitados aqui pelo mesmo motivo documentado em {@link
 * DevDriveResizerTest}: dependem de unidades reais, indisponiveis em CI.
 *
 * @author flavio mattos
 */
class DevDriveEnvironmentTest {

    // ---------------------------------------------------------------
    // buildRootsValue
    // ---------------------------------------------------------------

    @Test
    void buildRootsValueVazioParaListaVazia() {
        assertEquals("", DevDriveEnvironment.buildRootsValue(List.of()));
    }

    @Test
    void buildRootsValueUmaLetraTemBarraFinal() {
        assertEquals("E:\\", DevDriveEnvironment.buildRootsValue(List.of('E')));
    }

    @Test
    void buildRootsValueOrdenaAlfabeticamenteSeparandoComPontoEVirgula() {
        assertEquals("E:\\;K:\\;M:\\", DevDriveEnvironment.buildRootsValue(List.of('M', 'E', 'K')));
    }

    @Test
    void buildRootsValueNormalizaParaMaiuscula() {
        assertEquals("E:\\;K:\\", DevDriveEnvironment.buildRootsValue(List.of('k', 'e')));
    }

    // ---------------------------------------------------------------
    // buildApplyCommand
    // ---------------------------------------------------------------

    @Test
    void comandoDeAplicacaoDefineAsDuasVariaveisEmEscopoDeMaquina() {
        String cmd = DevDriveEnvironment.buildApplyCommand("E:", "E:\\;K:\\");

        assertTrue(cmd.startsWith("$ErrorActionPreference = 'Stop'"));
        assertTrue(cmd.contains("[Environment]::SetEnvironmentVariable('DEVDRIVE_HOME', 'E:', 'Machine')"));
        assertTrue(cmd.contains("[Environment]::SetEnvironmentVariable('DEVDRIVE_ROOTS', 'E:\\;K:\\', 'Machine')"));
        assertTrue(cmd.contains("} catch {"));
        assertTrue(cmd.contains("exit 1"));
    }

    @Test
    void comandoDeAplicacaoRemoveVariaveisQuandoValorNuloOuVazio() {
        String cmd = DevDriveEnvironment.buildApplyCommand(null, "");

        assertTrue(cmd.contains("[Environment]::SetEnvironmentVariable('DEVDRIVE_HOME', $null, 'Machine')"));
        assertTrue(cmd.contains("[Environment]::SetEnvironmentVariable('DEVDRIVE_ROOTS', $null, 'Machine')"));
    }

    @Test
    void comandoDeAplicacaoNaoUsaNenhumaAspaDupla() {
        // Mesma restricao documentada em VirtualDiskInfo: aspas duplas nao
        // sobrevivem a "powershell.exe -Command <script>".
        String cmd = DevDriveEnvironment.buildApplyCommand("E:", "E:\\;K:\\");

        assertEquals(-1, cmd.indexOf('"'));
    }

    // ---------------------------------------------------------------
    // devDriveQueryArgs
    // ---------------------------------------------------------------

    @Test
    void argumentosDeConsultaFsutilUsamLetraMaiuscula() {
        assertArrayEquals(new String[] {"devdrv", "query", "E:"}, DevDriveEnvironment.devDriveQueryArgs('e'));
    }

    // ---------------------------------------------------------------
    // mostRecentlyCreated
    // ---------------------------------------------------------------

    @Test
    void mostRecentlyCreatedRetornaVazioSemCandidatos() {
        assertEquals(Optional.empty(), DevDriveEnvironment.mostRecentlyCreated(List.of()));
    }

    @Test
    void mostRecentlyCreatedEscolheOArquivoVhdxCriadoPorUltimo() throws IOException {
        Path antigo = Files.createTempFile("devdrive-test-antigo", ".vhdx");
        Path novo = Files.createTempFile("devdrive-test-novo", ".vhdx");
        try {
            Files.setAttribute(antigo, "creationTime", FileTime.fromMillis(1_000));
            Files.setAttribute(novo, "creationTime", FileTime.fromMillis(2_000));

            List<DevDriveEnvironment.Candidate> candidatos =
                    List.of(candidateFor('E', antigo), candidateFor('K', novo));

            assertEquals(Optional.of('K'), DevDriveEnvironment.mostRecentlyCreated(candidatos));
        } finally {
            Files.deleteIfExists(antigo);
            Files.deleteIfExists(novo);
        }
    }

    @Test
    void mostRecentlyCreatedTrataArquivoInacessivelComoOMaisAntigoPossivel() throws IOException {
        Path real = Files.createTempFile("devdrive-test-real", ".vhdx");
        try {
            Path inexistente = Paths.get("Z:\\nao-existe-" + System.nanoTime() + ".vhdx");

            List<DevDriveEnvironment.Candidate> candidatos =
                    List.of(candidateFor('Z', inexistente), candidateFor('K', real));

            assertEquals(Optional.of('K'), DevDriveEnvironment.mostRecentlyCreated(candidatos));
        } finally {
            Files.deleteIfExists(real);
        }
    }

    private static DevDriveEnvironment.Candidate candidateFor(char letter, Path vhdxPath) {
        String stdout = "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ LOCATION=" + vhdxPath + "\n"
                + "@IDD@ BUSTYPE=" + VirtualDiskInfo.BUS_TYPE_FILE_BACKED_VIRTUAL + "\n"
                + "@IDD@ DISKSIZE=1\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=1\n"
                + "@IDD@ OK\n";
        return new DevDriveEnvironment.Candidate(letter, VirtualDiskInfo.parse(stdout));
    }
}
