package br.nom.mattos.flavio.instaladevdrive.core;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Estado atual do volume por tras de uma letra de unidade: o arquivo VHDX
 * que o lastreia, o tipo de barramento, o sistema de arquivos e os tamanhos
 * (do disco virtual e do volume). Usado pelo {@link DevDriveResizer} para
 * decidir se e seguro redimensionar e, depois, para confirmar por evidencia
 * real que o tamanho aumentou.
 *
 * <p>Os dados vem das propriedades <em>tipadas</em> dos cmdlets do modulo
 * Storage ({@code Get-Partition}/{@code Get-Disk}/{@code Get-Volume}) - nao
 * do texto formatado que eles imprimem para humanos. O script PowerShell
 * emite cada valor como uma linha {@code @IDD@ CHAVE=valor}, em que a chave
 * e um token ASCII escolhido por este programa e o valor e um numero, um
 * caminho ou um nome de enum ({@code ReFS}) - nada disso muda com o idioma
 * do Windows. E o equivalente enxuto de um {@code ConvertTo-Json} sem
 * trazer uma dependencia de parser JSON so para seis campos planos.
 *
 * @author flavio mattos
 */
public final class VirtualDiskInfo {

    /** STORAGE_BUS_TYPE.BusTypeFileBackedVirtual - constante estavel do Windows. */
    public static final int BUS_TYPE_FILE_BACKED_VIRTUAL = 15;

    private static final String LINE_PREFIX = "@IDD@ ";
    private static final String OK_MARKER = "OK";

    private final int diskNumber;
    private final Path vhdxPath;
    private final int busType;
    private final long diskSizeBytes;
    private final String fileSystemType;
    private final long volumeSizeBytes;

    private VirtualDiskInfo(int diskNumber, Path vhdxPath, int busType,
            long diskSizeBytes, String fileSystemType, long volumeSizeBytes) {
        this.diskNumber = diskNumber;
        this.vhdxPath = vhdxPath;
        this.busType = busType;
        this.diskSizeBytes = diskSizeBytes;
        this.fileSystemType = fileSystemType;
        this.volumeSizeBytes = volumeSizeBytes;
    }

    public int diskNumber() {
        return diskNumber;
    }

    public Path vhdxPath() {
        return vhdxPath;
    }

    public int busType() {
        return busType;
    }

    public boolean isFileBackedVirtual() {
        return busType == BUS_TYPE_FILE_BACKED_VIRTUAL;
    }

    public long diskSizeBytes() {
        return diskSizeBytes;
    }

    public String fileSystemType() {
        return fileSystemType;
    }

    public long volumeSizeBytes() {
        return volumeSizeBytes;
    }

    /**
     * Consulta o volume montado em {@code letter}: em falha (letra sem
     * particao, cmdlet indisponivel etc.) lanca {@link IllegalStateException}
     * com a saida de erro do PowerShell.
     */
    public static VirtualDiskInfo query(char letter) {
        ProcessResult result = PowerShellRunner.runCommand(buildQueryCommand(letter));
        if (!result.success()) {
            throw new IllegalStateException(
                    "Nao foi possivel inspecionar a unidade " + letter + ": (codigo " + result.exitCode() + ").\nSaida:\n"
                            + result.stdout() + result.stderr());
        }
        return parse(result.stdout());
    }

    /**
     * Monta a consulta PowerShell. Extraido (visivel para testes) para
     * permitir verificar o texto gerado sem executar processo nenhum.
     */
    static String buildQueryCommand(char letter) {
        String nl = System.lineSeparator();
        return "$ErrorActionPreference = 'Stop'" + nl
                + "try {" + nl
                + "    $p = Get-Partition -DriveLetter '" + letter + "' -ErrorAction Stop" + nl
                + "    $d = Get-Disk -Number $p.DiskNumber -ErrorAction Stop" + nl
                + "    $v = Get-Volume -DriveLetter '" + letter + "' -ErrorAction Stop" + nl
                + "    Write-Output ('" + LINE_PREFIX + "DISKNUMBER=' + [int]$d.Number)" + nl
                + "    Write-Output ('" + LINE_PREFIX + "LOCATION=' + [string]$d.Location)" + nl
                + "    Write-Output ('" + LINE_PREFIX + "BUSTYPE=' + [int]$d.BusType)" + nl
                + "    Write-Output ('" + LINE_PREFIX + "DISKSIZE=' + [long]$d.Size)" + nl
                + "    Write-Output ('" + LINE_PREFIX + "FSTYPE=' + [string]$v.FileSystemType)" + nl
                + "    Write-Output ('" + LINE_PREFIX + "VOLUMESIZE=' + [long]$v.Size)" + nl
                + "    Write-Output '" + LINE_PREFIX + OK_MARKER + "'" + nl
                + "    exit 0" + nl
                + "} catch {" + nl
                + "    Write-Error $_.Exception.Message" + nl
                + "    exit 1" + nl
                + "}" + nl;
    }

    /**
     * Interpreta a saida do {@link #buildQueryCommand(char)}. Aceita ruido
     * (linhas sem o prefixo {@code @IDD@ }) antes/depois dos dados, mas exige
     * o marcador final {@code OK} e todos os campos presentes - assim uma
     * saida truncada no meio nao passa por completa.
     */
    static VirtualDiskInfo parse(String stdout) {
        Map<String, String> fields = new HashMap<>();
        boolean ok = false;

        for (String rawLine : stdout.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith(LINE_PREFIX)) {
                continue;
            }
            String body = line.substring(LINE_PREFIX.length());
            if (body.equals(OK_MARKER)) {
                ok = true;
                continue;
            }
            int eq = body.indexOf('=');
            if (eq < 0) {
                continue;
            }
            fields.put(body.substring(0, eq), body.substring(eq + 1));
        }

        if (!ok) {
            throw new IllegalStateException(
                    "Saida da inspecao do volume incompleta (marcador final ausente): " + stdout);
        }

        int diskNumber = requireInt(fields, "DISKNUMBER");
        int busType = requireInt(fields, "BUSTYPE");
        long diskSize = requireLong(fields, "DISKSIZE");
        long volumeSize = requireLong(fields, "VOLUMESIZE");
        String fsType = require(fields, "FSTYPE");
        String location = require(fields, "LOCATION");

        Path vhdxPath;
        try {
            vhdxPath = Paths.get(location);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Caminho de VHDX invalido reportado pelo Windows: '" + location + "'", e);
        }

        return new VirtualDiskInfo(diskNumber, vhdxPath, busType, diskSize, fsType, volumeSize);
    }

    private static String require(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalStateException("Campo ausente na inspecao do volume: " + key);
        }
        return value;
    }

    private static int requireInt(Map<String, String> fields, String key) {
        try {
            return Integer.parseInt(require(fields, key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Campo nao numerico na inspecao do volume: " + key + "=" + fields.get(key), e);
        }
    }

    private static long requireLong(Map<String, String> fields, String key) {
        try {
            return Long.parseLong(require(fields, key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Campo nao numerico na inspecao do volume: " + key + "=" + fields.get(key), e);
        }
    }
}
