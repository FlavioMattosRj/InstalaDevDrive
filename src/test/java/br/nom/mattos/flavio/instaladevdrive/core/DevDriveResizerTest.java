package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes da geracao do comando de extensao da particao e das validacoes que
 * o antecedem. Nao exercitam {@code inspect}/{@code execute} de ponta a
 * ponta: eles dependem de um Dev Drive real montado, indisponivel em CI -
 * mesma limitacao (e mesma abordagem) de {@link DevDriveCreatorTest}.
 *
 * @author flavio mattos
 */
class DevDriveResizerTest {

    private final DevDriveResizer resizer = new DevDriveResizer();

    // ---------------------------------------------------------------
    // resolvePlan
    // ---------------------------------------------------------------

    @Test
    void resolvePlanNormalizaLetraParaMaiuscula() {
        DevDriveResizer.Plan plan = resizer.resolvePlan('e', "100GB");

        assertEquals('E', plan.driveLetter());
    }

    @Test
    void resolvePlanConverteTamanhoParaBytes() {
        DevDriveResizer.Plan plan = resizer.resolvePlan('E', "100GB");

        assertEquals(100L * 1024 * 1024 * 1024, plan.newSizeBytes());
    }

    @Test
    void resolvePlanRejeitaTamanhoInvalido() {
        assertThrows(IllegalArgumentException.class, () -> resizer.resolvePlan('E', "grande"));
    }

    @Test
    void resolvePlanRejeitaTamanhoAbaixoDoMinimo() {
        assertThrows(IllegalArgumentException.class, () -> resizer.resolvePlan('E', "49GB"));
    }

    // ---------------------------------------------------------------
    // inspect (parte que nao depende de um volume real)
    // ---------------------------------------------------------------

    @Test
    void inspectRejeitaLetraLivre() {
        char livre = DriveLetterFinder.findFreeLetter();
        DevDriveResizer.Plan plan = resizer.resolvePlan(livre, "100GB");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resizer.inspect(plan));
        assertTrue(ex.getMessage().contains("nao esta em uso"));
    }

    // ---------------------------------------------------------------
    // requiresVhdxGrowth
    // ---------------------------------------------------------------

    @Test
    void naoExpandeVhdxQuandoNovoTamanhoIgualAoAtualDentroDaFolga() {
        long atual = 50L * 1024 * 1024 * 1024;
        VirtualDiskInfo info = VirtualDiskInfo.parse(infoStdout(atual, atual));
        DevDriveResizer.Plan plan = resizer.resolvePlan('E', "50GB");

        assertTrue(!resizer.requiresVhdxGrowth(plan, info),
                "Pedir o mesmo tamanho (a menos de MB de alinhamento) nao deve exigir desanexar/expandir");
    }

    @Test
    void expandeVhdxQuandoNovoTamanhoClaramenteMaior() {
        long atual = 50L * 1024 * 1024 * 1024;
        VirtualDiskInfo info = VirtualDiskInfo.parse(infoStdout(atual, atual));
        DevDriveResizer.Plan plan = resizer.resolvePlan('E', "100GB");

        assertTrue(resizer.requiresVhdxGrowth(plan, info));
    }

    @Test
    void diferencaDentroDaFolgaNaoContaComoCrescimento() {
        long atual = 50L * 1024 * 1024 * 1024;
        VirtualDiskInfo info = VirtualDiskInfo.parse(infoStdout(atual + 10L * 1024 * 1024, atual));
        // pede exatamente 50GiB, disco reporta 50GiB + 10MB: nem crescimento nem reducao
        DevDriveResizer.Plan plan = resizer.resolvePlan('E', "50GB");

        assertTrue(!resizer.requiresVhdxGrowth(plan, info));
    }

    // ---------------------------------------------------------------
    // buildResizePartitionCommand
    // ---------------------------------------------------------------

    @Test
    void comandoDeExtensaoTemEstruturaEsperada() {
        String cmd = resizer.buildResizePartitionCommand(resizer.resolvePlan('E', "100GB"));

        assertTrue(cmd.startsWith("$ErrorActionPreference = 'Stop'"), "Deve travar em qualquer erro nao tratado");
        assertTrue(cmd.contains("Get-PartitionSupportedSize -DriveLetter 'E'"), "Consulta do tamanho maximo ausente");
        assertTrue(cmd.contains("Resize-Partition -DriveLetter 'E' -Size $max"), "Comando de extensao ausente/incorreto");
        assertTrue(cmd.contains("if ($max -gt $cur) {"), "Sem a guarda, rodar no tamanho certo lancaria erro em vez de no-op");
        assertTrue(cmd.contains("Write-Output '" + DevDriveResizer.RESIZE_SUCCESS_MARKER + "'"),
                "Marcador de sucesso e usado pelo chamador para confirmar a extensao");
        assertTrue(cmd.contains("} catch {"), "Deve haver tratamento de erro");
        assertTrue(cmd.contains("exit 1"), "Caminho de erro deve sinalizar exit code != 0");
    }

    @Test
    void comandoDeExtensaoNuncaEncolheAParticao() {
        String cmd = resizer.buildResizePartitionCommand(resizer.resolvePlan('E', "100GB"));

        assertTrue(!cmd.contains("-Size $cur"));
        assertTrue(!cmd.toLowerCase().contains("shrink"));
    }

    @Test
    void comandoDeExtensaoNuncaChamaFormatVolume() {
        // Redimensionar preserva os dados: nao pode haver nenhuma formatacao.
        String cmd = resizer.buildResizePartitionCommand(resizer.resolvePlan('E', "100GB"));

        assertTrue(!cmd.contains("Format-Volume"));
    }

    // ---------------------------------------------------------------
    // devDriveQueryArgs
    // ---------------------------------------------------------------

    @Test
    void argumentosDoFsutilConsultamADevDrivePelaLetra() {
        assertEquals(java.util.List.of("devdrv", "query", "E:"),
                java.util.List.of(DevDriveResizer.devDriveQueryArgs('e')));
    }

    private static String infoStdout(long diskSizeBytes, long volumeSizeBytes) {
        return "@IDD@ DISKNUMBER=7\n"
                + "@IDD@ LOCATION=C:\\DevDrive\\DevDrive.vhdx\n"
                + "@IDD@ BUSTYPE=" + VirtualDiskInfo.BUS_TYPE_FILE_BACKED_VIRTUAL + "\n"
                + "@IDD@ DISKSIZE=" + diskSizeBytes + "\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=" + volumeSizeBytes + "\n"
                + "@IDD@ OK\n";
    }
}
