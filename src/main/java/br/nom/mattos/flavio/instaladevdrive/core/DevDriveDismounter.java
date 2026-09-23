package br.nom.mattos.flavio.instaladevdrive.core;

import br.nom.mattos.flavio.virtdisk.VhdxMount;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orquestra a desanexacao (nao-destrutiva) de um Dev Drive ja existente,
 * identificado por letra de unidade ({@code --letter}) ou diretamente pelo
 * caminho do arquivo VHDX ({@code --path}) - exatamente um dos dois.
 *
 * <p>Ao contrario do {@link DevDriveDeleter}, o arquivo VHDX nunca e
 * apagado: {@link VhdxMount#dismount()} so remove a letra de unidade
 * (reversivel a qualquer momento com {@link DevDriveMounter}).
 *
 * <p>Quando a identificacao e por {@code --path}, o VHDX precisa estar
 * montado sob alguma letra no momento ({@link VhdxLocator#findMountedLetter}):
 * o gate "e mesmo um Dev Drive" ({@code fsutil devdrv query}) so consegue
 * consultar por letra, entao um VHDX anexado sem nenhuma letra (ou
 * totalmente desanexado) nao pode ter sua condicao de Dev Drive confirmada
 * por este comando - ele recusa em vez de desanexar as cegas.
 *
 * @author flavio mattos
 */
public final class DevDriveDismounter {

    public static final class Plan {

        private final Character letter;
        private final Path vhdxPath;

        Plan(Character letter, Path vhdxPath) {
            this.letter = letter;
            this.vhdxPath = vhdxPath;
        }

        public Character letter() {
            return letter;
        }

        public Path vhdxPath() {
            return vhdxPath;
        }
    }

    /** Unidade resolvida por {@link #inspect(Plan)}: a letra confirmada e o {@link VirtualDiskInfo} por tras dela. */
    public static final class Target {

        private final char letter;
        private final VirtualDiskInfo info;

        Target(char letter, VirtualDiskInfo info) {
            this.letter = letter;
            this.info = info;
        }

        public char letter() {
            return letter;
        }

        public VirtualDiskInfo info() {
            return info;
        }
    }

    public Plan resolvePlan(Character letter, Path vhdxPath) {
        if (letter == null && vhdxPath == null) {
            throw new IllegalArgumentException("Informe --letter ou --path para identificar a unidade a desmontar.");
        }
        if (letter != null && vhdxPath != null) {
            throw new IllegalArgumentException("Informe --letter OU --path, nao os dois.");
        }
        return new Plan(letter == null ? null : Character.toUpperCase(letter), vhdxPath);
    }

    /**
     * Inspeciona e resolve a unidade alvo, sem exigir privilegios de
     * Administrador (pode rodar em {@code --dry-run}).
     */
    public Target inspect(Plan plan) {
        if (plan.letter() != null) {
            return inspectByLetter(plan.letter());
        }
        return inspectByPath(plan.vhdxPath());
    }

    private static Target inspectByLetter(char letter) {
        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("Letra de unidade invalida: " + letter);
        }
        if (DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": nao esta em uso; nao ha nada montado para desanexar.");
        }
        return new Target(letter, validateDevDriveCandidate(letter));
    }

    private static Target inspectByPath(Path vhdxPath) {
        if (vhdxPath.toString().indexOf('"') >= 0) {
            throw new IllegalArgumentException("O caminho do arquivo VHDX nao pode conter aspas: " + vhdxPath);
        }
        if (!Files.isRegularFile(vhdxPath)) {
            throw new IllegalStateException("Arquivo VHDX nao encontrado: " + vhdxPath);
        }

        Character letter = VhdxLocator.findMountedLetter(vhdxPath);
        if (letter == null) {
            throw new IllegalStateException(
                    "O arquivo " + vhdxPath + " nao esta montado sob nenhuma letra de unidade no momento; nao "
                            + "e possivel confirmar que e um Dev Drive para desmonta-lo por este comando. Se ele "
                            + "estiver anexado sem letra, monte-o primeiro com 'mount --path' e depois desmonte "
                            + "usando --letter.");
        }

        return new Target(letter, validateDevDriveCandidate(letter));
    }

    /**
     * Confere, sem elevacao, que a letra parece lastrear um Dev Drive
     * (VHDX baseado em arquivo + ReFS) - mesma checagem estrutural de
     * {@link DevDriveDeleter#inspect}. A confirmacao final ({@code fsutil
     * devdrv query}) exige elevacao e fica em {@link #execute}.
     */
    private static VirtualDiskInfo validateDevDriveCandidate(char letter) {
        VirtualDiskInfo info = VirtualDiskInfo.query(letter);

        if (!info.isFileBackedVirtual()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao e um disco virtual baseado em arquivo (VHDX); "
                            + "esta ferramenta so desmonta Dev Drives em VHDX.");
        }
        if (!"ReFS".equalsIgnoreCase(info.fileSystemType())) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": usa " + info.fileSystemType() + ", nao ReFS; nao e um Dev Drive.");
        }
        if (info.vhdxPath().toString().indexOf('"') >= 0) {
            throw new IllegalArgumentException("O caminho do arquivo VHDX nao pode conter aspas: " + info.vhdxPath());
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
                    "Este programa precisa ser executado como Administrador para desmontar volumes. "
                            + "Feche esta janela, abra um terminal como Administrador e execute novamente.");
        }
    }

    /**
     * Executa a desanexacao. Assume {@link #inspect(Plan)} e
     * {@link #checkElevation()} ja chamados com sucesso.
     */
    public void execute(Target target) {
        char letter = target.letter();
        String vhdxPath = target.info().vhdxPath().toAbsolutePath().toString();

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

        try {
            new VhdxMount(vhdxPath).dismount();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Nao foi possivel desanexar a unidade " + letter + ":. Feche todos os programas e terminais "
                            + "que estejam usando " + letter + ": e tente novamente. Detalhe: " + e.getMessage(), e);
        }

        if (!DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": continuou visivel apos a desanexacao.");
        }
    }

    /**
     * Falha se {@code letter} nao for um Dev Drive. Decide pelo <em>codigo
     * de saida</em> de {@code fsutil devdrv query} (0 = e Dev Drive) - nunca
     * pelo texto, que e localizado.
     */
    private static void requireDevDrive(char letter) {
        ProcessResult result = FsutilRunner.run(devDriveQueryArgs(letter));
        if (!result.success()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao esta marcada como Dev Drive - 'fsutil devdrv query " + letter
                            + ":' saiu com codigo " + result.exitCode() + ". Esta ferramenta so desmonta "
                            + "Dev Drives.\nSaida:\n" + result.stdout() + result.stderr());
        }
    }

    static String[] devDriveQueryArgs(char letter) {
        return new String[] {"devdrv", "query", Character.toUpperCase(letter) + ":"};
    }
}
