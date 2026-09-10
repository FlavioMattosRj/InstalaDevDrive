package br.nom.mattos.flavio.instaladevdrive.core;

import br.nom.mattos.flavio.virtdisk.VhdxMount;

import java.nio.file.Files;

/**
 * Orquestra o aumento de um Dev Drive ja existente:
 * <ol>
 *   <li>{@link VirtualDiskInfo#query(char)} descobre, a partir da letra de
 *       unidade, qual arquivo VHDX lastreia o volume, o tipo de barramento,
 *       o sistema de arquivos e os tamanhos atuais - lendo propriedades
 *       tipadas do modulo Storage, nunca texto localizado.</li>
 *   <li>Se o novo tamanho e maior que a capacidade atual do VHDX: o disco e
 *       DESANEXADO (a Windows Virtual Disk API exige isso para expandir),
 *       {@link VhdxMount#expandTo(long)} aumenta o VHDX e
 *       {@link VhdxMount#mountPermanently()} reanexa de forma permanente
 *       (mesma politica da criacao: sobrevive a reboots).</li>
 *   <li>{@code Resize-Partition} estende a particao/volume ReFS - online,
 *       sem desanexar - para preencher o espaco livre. Este passo roda
 *       sempre, mesmo quando o VHDX ja tinha capacidade de sobra, e e
 *       idempotente: rodar de novo apos uma falha parcial conclui o
 *       trabalho.</li>
 *   <li>Confirmacao final por evidencia real: {@link VirtualDiskInfo#query(char)}
 *       de novo mostra que o disco (e/ou o volume) cresceu.</li>
 * </ol>
 *
 * Validacoes antes de qualquer alteracao: {@link #inspect(Plan)} (nao exige
 * elevacao) confere que a letra esta em uso, o disco e um VHDX baseado em
 * arquivo, o volume e ReFS, o arquivo VHDX existe e nao contem aspas, e o
 * novo tamanho nao e menor que o atual (esta ferramenta so aumenta; reducao
 * de ReFS nao e suportada pelo Windows). O gate final "e mesmo um Dev
 * Drive" ({@code fsutil devdrv query}, so o codigo de saida) roda no inicio
 * do {@link #execute}, ja elevado - o fsutil precisa de Administrador.
 *
 * @author flavio mattos
 */
public final class DevDriveResizer {

    /** Marcador impresso pelo script de {@link #buildResizePartitionCommand(Plan)} em caso de sucesso. */
    static final String RESIZE_SUCCESS_MARKER = "Particao ReFS estendida com sucesso";

    /**
     * Folga (64 MiB) ao comparar o tamanho pedido com o tamanho atual do
     * VHDX. A geometria do VHDX arredonda a capacidade para um alinhamento
     * interno, entao pedir "50GB" num disco que o Windows reporta como
     * 50 GiB + alguns MB nao deve ser tratado nem como crescimento (uma
     * desanexacao inutil) nem como reducao (um erro falso).
     */
    static final long SIZE_TOLERANCE_BYTES = 64L * 1024 * 1024;

    public static final class Plan {

        private final char driveLetter;
        private final long newSizeBytes;

        Plan(char driveLetter, long newSizeBytes) {
            this.driveLetter = driveLetter;
            this.newSizeBytes = newSizeBytes;
        }

        public char driveLetter() {
            return driveLetter;
        }

        public long newSizeBytes() {
            return newSizeBytes;
        }
    }

    public Plan resolvePlan(char letter, String newSizeText) {
        long newSizeBytes = SizeParser.parseToBytes(newSizeText);
        SizeParser.validateMinimum(newSizeBytes);
        return new Plan(Character.toUpperCase(letter), newSizeBytes);
    }

    /**
     * Inspeciona a unidade e valida que ela pode ser redimensionada, sem
     * exigir privilegios de Administrador (pode rodar em {@code --dry-run}).
     * Retorna o {@link VirtualDiskInfo} coletado para o {@link #execute} nao
     * ter que consultar de novo.
     */
    public VirtualDiskInfo inspect(Plan plan) {
        char letter = plan.driveLetter();

        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("Letra de unidade invalida: " + letter);
        }
        if (DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": nao esta em uso; nao ha Dev Drive para redimensionar.");
        }

        VirtualDiskInfo info = VirtualDiskInfo.query(letter);

        if (!info.isFileBackedVirtual()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao e um disco virtual baseado em arquivo (VHDX); "
                            + "esta ferramenta so redimensiona Dev Drives em VHDX.");
        }
        if (!"ReFS".equalsIgnoreCase(info.fileSystemType())) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": usa " + info.fileSystemType() + ", nao ReFS; nao e um Dev Drive.");
        }
        if (info.vhdxPath().toString().indexOf('"') >= 0) {
            throw new IllegalArgumentException("O caminho do arquivo VHDX nao pode conter aspas: " + info.vhdxPath());
        }
        if (!Files.isRegularFile(info.vhdxPath())) {
            throw new IllegalStateException(
                    "O arquivo VHDX que lastreia " + letter + ": nao foi encontrado: " + info.vhdxPath());
        }
        // A confirmacao final de "e um Dev Drive" (fsutil devdrv query) NAO
        // e feita aqui: ela exige elevacao e este metodo roda antes do
        // checkElevation() (inclusive em --dry-run). Fica no inicio do
        // execute(), ja elevado - ver requireDevDrive(char).

        if (plan.newSizeBytes() < info.diskSizeBytes() - SIZE_TOLERANCE_BYTES) {
            throw new IllegalArgumentException(
                    "Reducao nao suportada: a unidade " + letter + ": tem "
                            + SizeParser.toHumanReadable(info.diskSizeBytes()) + " e o novo tamanho pedido e "
                            + SizeParser.toHumanReadable(plan.newSizeBytes())
                            + ". Esta ferramenta (e o ReFS) so aumentam.");
        }

        return info;
    }

    /**
     * Verifica que o processo esta elevado (Administrador). Ver
     * {@code DevDriveCreator#checkElevation()}: nao ha janela de corrida em
     * chamar isso antes do {@link #execute}, pois o nivel de elevacao e
     * fixado na criacao do processo.
     */
    public void checkElevation() {
        if (!ElevationChecker.isElevated()) {
            throw new IllegalStateException(
                    "Este programa precisa ser executado como Administrador para redimensionar volumes. "
                            + "Feche esta janela, abra um terminal como Administrador e execute novamente.");
        }
    }

    /**
     * {@code true} se o novo tamanho e grande o suficiente (acima da folga)
     * para justificar expandir o VHDX - o unico caso em que a unidade
     * precisa ficar offline. Caso contrario, so a particao e estendida,
     * online.
     */
    public boolean requiresVhdxGrowth(Plan plan, VirtualDiskInfo info) {
        return plan.newSizeBytes() > info.diskSizeBytes() + SIZE_TOLERANCE_BYTES;
    }

    /**
     * Executa o redimensionamento. Assume {@link #inspect(Plan)} e
     * {@link #checkElevation()} ja chamados com sucesso; {@code info} e o
     * retorno de {@link #inspect(Plan)}.
     */
    public ProcessResult execute(Plan plan, VirtualDiskInfo info) {
        char letter = plan.driveLetter();
        String vhdxPath = info.vhdxPath().toAbsolutePath().toString();

        // Gate final: a unidade tem que ser mesmo um Dev Drive. Exige
        // elevacao (ja garantida por checkElevation() antes deste metodo),
        // por isso nao roda no inspect()/--dry-run.
        requireDevDrive(letter);

        // Revalida imediatamente antes de agir, reduzindo a janela de corrida
        // entre a inspecao/confirmacao e a execucao real.
        if (DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": deixou de estar em uso entre a validacao e a execucao. Abortado.");
        }

        boolean growVhdx = requiresVhdxGrowth(plan, info);

        if (growVhdx) {
            detach(letter, vhdxPath);

            try {
                new VhdxMount(vhdxPath).expandTo(plan.newSizeBytes());
            } catch (RuntimeException e) {
                reattachBestEffort(vhdxPath);
                throw new IllegalStateException(
                        "Falha ao expandir o VHDX. A unidade foi reanexada no tamanho original. Detalhe: "
                                + e.getMessage(), e);
            }

            try {
                new VhdxMount(vhdxPath).mountPermanently();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "O VHDX foi expandido, mas NAO foi possivel reanexa-lo - a unidade " + letter
                                + ": esta temporariamente indisponivel. Execute este mesmo comando de novo "
                                + "para reanexar (a operacao e idempotente) ou reinicie o Windows. Detalhe: "
                                + e.getMessage(), e);
            }
            if (DriveLetterFinder.isLetterFree(letter)) {
                throw new IllegalStateException(
                        "O VHDX foi reanexado, mas a letra " + letter + ": nao reapareceu. "
                                + "Verifique no Gerenciamento de Disco; pode ser necessario reatribuir a letra.");
            }
        }

        ProcessResult resizeResult = PowerShellRunner.runCommand(buildResizePartitionCommand(plan));
        if (!resizeResult.success() || !resizeResult.stdout().contains(RESIZE_SUCCESS_MARKER)) {
            String hint = growVhdx
                    ? "\nO VHDX ja foi expandido e reanexado; execute o comando de novo para concluir a "
                            + "extensao da particao (idempotente)."
                    : "";
            throw new IllegalStateException(
                    "Falha ao estender a particao ReFS (codigo " + resizeResult.exitCode() + ")." + hint
                            + "\nSaida:\n" + resizeResult.stdout() + resizeResult.stderr());
        }

        VirtualDiskInfo after = VirtualDiskInfo.query(letter);
        if (growVhdx && after.diskSizeBytes() <= info.diskSizeBytes()) {
            throw new IllegalStateException(
                    "A operacao terminou sem erro, mas a capacidade do disco virtual nao aumentou ("
                            + SizeParser.toHumanReadable(after.diskSizeBytes()) + "). Verifique manualmente.");
        }
        if (after.volumeSizeBytes() < info.volumeSizeBytes()) {
            throw new IllegalStateException(
                    "A operacao terminou sem erro, mas o volume " + letter + ": encolheu inesperadamente ("
                            + SizeParser.toHumanReadable(after.volumeSizeBytes()) + "). Verifique manualmente.");
        }

        return resizeResult;
    }

    private static void detach(char letter, String vhdxPath) {
        try {
            new VhdxMount(vhdxPath).dismount();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Nao foi possivel desanexar a unidade " + letter + ": para expandir o VHDX. "
                            + "Feche todos os programas e terminais que estejam usando " + letter
                            + ": e tente novamente. Detalhe: " + e.getMessage(), e);
        }
        if (!DriveLetterFinder.isLetterFree(letter)) {
            reattachBestEffort(vhdxPath);
            throw new IllegalStateException(
                    "A letra " + letter + ": continuou visivel apos a desanexacao; nada foi alterado.");
        }
    }

    private static void reattachBestEffort(String vhdxPath) {
        try {
            new VhdxMount(vhdxPath).mountPermanently();
        } catch (RuntimeException ignored) {
            // melhor esforco: a mensagem de erro que motivou esta chamada ja
            // orienta o usuario a reexecutar o comando para reanexar.
        }
    }

    /**
     * Falha se {@code letter} nao for um Dev Drive. Decide pelo <em>codigo
     * de saida</em> de {@code fsutil devdrv query} (0 = e Dev Drive) - nunca
     * pelo texto, que e localizado. Exige elevacao: sem ela o fsutil
     * responde "acesso negado" e sai != 0, entao so chame depois do
     * {@link #checkElevation()}.
     */
    private static void requireDevDrive(char letter) {
        ProcessResult result = FsutilRunner.run(devDriveQueryArgs(letter));
        if (!result.success()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao esta marcada como Dev Drive - 'fsutil devdrv query " + letter
                            + ":' saiu com codigo " + result.exitCode() + ". Esta ferramenta so redimensiona "
                            + "Dev Drives.\nSaida:\n" + result.stdout() + result.stderr());
        }
    }

    /**
     * Argumentos de {@code fsutil devdrv query <letra>:}. Extraido (visivel
     * para testes) para verificar a montagem sem executar o processo.
     */
    static String[] devDriveQueryArgs(char letter) {
        return new String[] {"devdrv", "query", Character.toUpperCase(letter) + ":"};
    }

    /**
     * Script que estende a particao ReFS ate o maximo suportado. So chama
     * {@code Resize-Partition} se houver espaco a ganhar, entao rodar com o
     * disco ja no tamanho certo e um no-op silencioso (mantem a idempotencia
     * apos falhas parciais). A letra ja vem validada como um unico caractere
     * A-Z por {@link #inspect(Plan)} - nao ha superficie de injecao aqui.
     */
    String buildResizePartitionCommand(Plan plan) {
        char l = plan.driveLetter();
        String nl = System.lineSeparator();
        return "$ErrorActionPreference = 'Stop'" + nl
                + "try {" + nl
                + "    $max = (Get-PartitionSupportedSize -DriveLetter '" + l + "').SizeMax" + nl
                + "    $cur = (Get-Partition -DriveLetter '" + l + "').Size" + nl
                + "    if ($max -gt $cur) {" + nl
                + "        Resize-Partition -DriveLetter '" + l + "' -Size $max" + nl
                + "    }" + nl
                + "    Write-Output '" + RESIZE_SUCCESS_MARKER + "'" + nl
                + "    exit 0" + nl
                + "} catch {" + nl
                + "    Write-Error $_.Exception.Message" + nl
                + "    exit 1" + nl
                + "}" + nl;
    }
}
