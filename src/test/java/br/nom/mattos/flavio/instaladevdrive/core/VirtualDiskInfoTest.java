package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a geracao da consulta e, principalmente, o parsing da saida - que
 * precisa ser exato e indiferente ao idioma do Windows. A saida vem em
 * linhas {@code @IDD@ CHAVE=valor}: chaves ASCII fixas, valores numericos ou
 * de enum ({@code ReFS}), nada localizado.
 *
 * @author flavio mattos
 */
class VirtualDiskInfoTest {

    // ---------------------------------------------------------------
    // buildQueryCommand
    // ---------------------------------------------------------------

    @Test
    void consultaUsaCmdletsTipadosDoModuloStorage() {
        String cmd = VirtualDiskInfo.buildQueryCommand('E');

        assertTrue(cmd.startsWith("$ErrorActionPreference = 'Stop'"));
        assertTrue(cmd.contains("Get-Partition -DriveLetter 'E'"));
        assertTrue(cmd.contains("Get-Disk -Number $p.DiskNumber"));
        assertTrue(cmd.contains("Get-Volume -DriveLetter 'E'"));
        assertTrue(cmd.contains("} catch {"));
        assertTrue(cmd.contains("exit 1"));
    }

    @Test
    void consultaNaoDependeDeTextoFormatadoParaHumanos() {
        String cmd = VirtualDiskInfo.buildQueryCommand('E');

        // Le propriedades tipadas ([int]/[long]/[string]), nunca Format-* nem
        // Out-String sobre a saida de exibicao.
        assertTrue(!cmd.contains("Format-Table"));
        assertTrue(!cmd.contains("Format-List"));
        assertTrue(!cmd.contains("Out-String"));
        assertTrue(cmd.contains("[long]$d.Size"));
        assertTrue(cmd.contains("[int]$d.BusType"));
    }

    // ---------------------------------------------------------------
    // parse
    // ---------------------------------------------------------------

    @Test
    void parseSaidaCompleta() {
        String stdout = "@IDD@ DISKNUMBER=3\n"
                + "@IDD@ LOCATION=C:\\DevDrive\\DevDrive.vhdx\n"
                + "@IDD@ BUSTYPE=15\n"
                + "@IDD@ DISKSIZE=53687091200\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=53485764608\n"
                + "@IDD@ OK\n";

        VirtualDiskInfo info = VirtualDiskInfo.parse(stdout);

        assertEquals(3, info.diskNumber());
        assertEquals(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), info.vhdxPath());
        assertEquals(15, info.busType());
        assertTrue(info.isFileBackedVirtual());
        assertEquals(53687091200L, info.diskSizeBytes());
        assertEquals("ReFS", info.fileSystemType());
        assertEquals(53485764608L, info.volumeSizeBytes());
    }

    @Test
    void parseIgnoraRuidoAntesEDepoisDasLinhasComPrefixo() {
        String stdout = "WARNING: algum aviso do PowerShell\n"
                + "\n"
                + "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ LOCATION=D:\\x.vhdx\n"
                + "@IDD@ BUSTYPE=15\n"
                + "@IDD@ DISKSIZE=100\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=90\n"
                + "@IDD@ OK\n"
                + "linha solta no final\n";

        VirtualDiskInfo info = VirtualDiskInfo.parse(stdout);

        assertEquals(1, info.diskNumber());
        assertEquals(90L, info.volumeSizeBytes());
    }

    @Test
    void parseFalhaSemMarcadorFinal() {
        // saida truncada no meio: sem o "OK" final, nao deve ser aceita
        String stdout = "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ LOCATION=D:\\x.vhdx\n"
                + "@IDD@ BUSTYPE=15\n";

        assertThrows(IllegalStateException.class, () -> VirtualDiskInfo.parse(stdout));
    }

    @Test
    void parseFalhaComCampoFaltando() {
        String stdout = "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ BUSTYPE=15\n"
                + "@IDD@ DISKSIZE=100\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=90\n"
                + "@IDD@ OK\n";

        // falta LOCATION
        assertThrows(IllegalStateException.class, () -> VirtualDiskInfo.parse(stdout));
    }

    @Test
    void parseFalhaComTamanhoNaoNumerico() {
        String stdout = "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ LOCATION=D:\\x.vhdx\n"
                + "@IDD@ BUSTYPE=15\n"
                + "@IDD@ DISKSIZE=muito-grande\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=90\n"
                + "@IDD@ OK\n";

        assertThrows(IllegalStateException.class, () -> VirtualDiskInfo.parse(stdout));
    }

    @Test
    void parsePreservaCaminhoComEspacosEComSinalDeIgual() {
        String stdout = "@IDD@ DISKNUMBER=1\n"
                + "@IDD@ LOCATION=C:\\Dev Drive (2)=novo\\d.vhdx\n"
                + "@IDD@ BUSTYPE=15\n"
                + "@IDD@ DISKSIZE=100\n"
                + "@IDD@ FSTYPE=ReFS\n"
                + "@IDD@ VOLUMESIZE=90\n"
                + "@IDD@ OK\n";

        VirtualDiskInfo info = VirtualDiskInfo.parse(stdout);

        assertEquals(Paths.get("C:\\Dev Drive (2)=novo\\d.vhdx"), info.vhdxPath(),
                "O split deve ser no primeiro '=' apenas, para nao mutilar caminhos");
    }

    @Test
    void parseReconheceDiscoNaoVirtualPeloBusType() {
        String stdout = "@IDD@ DISKNUMBER=0\n"
                + "@IDD@ LOCATION=\n"
                + "@IDD@ BUSTYPE=17\n"
                + "@IDD@ DISKSIZE=100\n"
                + "@IDD@ FSTYPE=NTFS\n"
                + "@IDD@ VOLUMESIZE=90\n"
                + "@IDD@ OK\n";

        VirtualDiskInfo info = VirtualDiskInfo.parse(stdout);

        assertTrue(!info.isFileBackedVirtual(), "BusType 17 (NVMe) nao e disco virtual baseado em arquivo");
    }
}
