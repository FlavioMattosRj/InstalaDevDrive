package br.nom.mattos.flavio.instaladevdrive.core;

import br.nom.mattos.flavio.virtdisk.VhdxMount;
import br.nom.mattos.flavio.virtdisk.VirtualDiskException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Orquestra a criacao de um Dev Drive:
 * <ol>
 *   <li>DISKPART cria apenas o arquivo do disco virtual (VHDX) - nao
 *       anexa, particiona nem formata nada ainda.</li>
 *   <li>{@link VhdxMount#mountPermanently()} anexa o disco virtual chamando
 *       diretamente a Windows Virtual Disk API (virtdisk.dll, via JNA) com
 *       as flags ATTACH_VIRTUAL_DISK_FLAG_PERMANENT_LIFETIME e
 *       ATTACH_VIRTUAL_DISK_FLAG_AT_BOOT. Isso faz o disco sobreviver ao
 *       fechamento deste processo e a reinicializacoes do Windows sem
 *       depender de nenhuma tarefa agendada nem de nenhum script gravado em
 *       disco que pudesse ser adulterado e executado como SYSTEM no proximo
 *       boot.</li>
 *   <li>DISKPART particiona o disco ja anexado e atribui a letra de
 *       unidade - sempre reselecionando o disco pelo caminho do arquivo,
 *       nunca por numero.</li>
 *   <li>O cmdlet {@code Format-Volume -DevDrive} (modulo Storage, presente
 *       em qualquer Windows 11) formata a particao como Dev Drive.</li>
 * </ol>
 *
 * Validacoes de seguranca aplicadas antes de qualquer alteracao no sistema:
 * a letra de unidade nao pode ser a unidade do sistema, nem A/B, nem uma
 * letra ja ocupada; o arquivo VHDX de destino nao pode ja existir; o
 * caminho nao pode conter aspas (usadas para delimitar o caminho no script
 * do DISKPART). Apos cada etapa, o resultado e confirmado por uma evidencia
 * real do sistema de arquivos (a letra passou a existir), em vez de confiar
 * apenas no texto (que pode estar em outro idioma) impresso pelo DISKPART.
 * Em qualquer falha, e feita uma tentativa de reversao (desanexar o disco
 * virtual e apagar o arquivo).
 *
 * @author flavio mattos
 */
public final class DevDriveCreator {

    private static final long MEGABYTE = 1024L * 1024;

    public static final class Plan {

        private final Path vhdPath;
        private final char driveLetter;
        private final long sizeBytes;
        private final String label;

        public Plan(Path vhdPath, char driveLetter, long sizeBytes, String label) {
            this.vhdPath = vhdPath;
            this.driveLetter = driveLetter;
            this.sizeBytes = sizeBytes;
            this.label = label;
        }

        public Path vhdPath() {
            return vhdPath;
        }

        public char driveLetter() {
            return driveLetter;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public String label() {
            return label;
        }
    }

    public Plan resolvePlan(String name, String sizeText, Character requestedLetter, Path requestedDirectory) {
        long sizeBytes = SizeParser.parseToBytes(sizeText);
        SizeParser.validateMinimum(sizeBytes);

        Path directory = requestedDirectory != null
                ? requestedDirectory
                : Paths.get("C:\\DevDrive");
        Path vhdPath = directory.resolve(name + ".vhdx");

        char letter = requestedLetter != null ? requestedLetter : DriveLetterFinder.findFreeLetter();

        return new Plan(vhdPath, letter, sizeBytes, name);
    }

    /**
     * Valida o plano em si (letra de unidade, arquivo de destino), sem
     * exigir privilegios de Administrador. Pode ser chamado a qualquer
     * momento, inclusive em modo --dry-run, para dar feedback imediato de
     * erros de configuracao.
     */
    public void validatePlan(Plan plan) {
        char letter = plan.driveLetter();

        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("Letra de unidade invalida: " + letter);
        }
        if (letter == 'A' || letter == 'B') {
            throw new IllegalArgumentException(
                    "A letra " + letter + ": e reservada historicamente para unidades de disquete; escolha outra.");
        }

        String systemDrive = System.getenv("SystemDrive");
        if (systemDrive != null && !systemDrive.isEmpty()
                && Character.toUpperCase(systemDrive.charAt(0)) == letter) {
            throw new IllegalArgumentException(
                    "A letra " + letter + ": e a unidade do sistema operacional e nao pode ser usada.");
        }

        if (!DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": ja esta em uso por outra unidade. Escolha uma letra livre com --letter.");
        }

        if (Files.exists(plan.vhdPath())) {
            throw new IllegalStateException(
                    "Ja existe um arquivo em " + plan.vhdPath() + ". Escolha outro --name ou --path.");
        }

        if (plan.vhdPath().toString().indexOf('"') >= 0) {
            throw new IllegalArgumentException("O caminho do arquivo VHDX nao pode conter aspas.");
        }
    }

    /**
     * Verifica que o processo esta elevado (Administrador), requisito
     * obrigatorio para criar/formatar volumes. Chamar apenas imediatamente
     * antes de {@link #execute(Plan)}.
     */
    public void checkElevation() {
        if (!ElevationChecker.isElevated()) {
            throw new IllegalStateException(
                    "Este programa precisa ser executado como Administrador para criar/formatar volumes. "
                            + "Feche esta janela, abra um terminal como Administrador e execute novamente.");
        }
    }

    /**
     * Executa a criacao do Dev Drive. Assume que {@link #validatePlan(Plan)}
     * e {@link #checkElevation()} ja foram chamados com sucesso.
     */
    public ProcessResult execute(Plan plan) {
        try {
            Files.createDirectories(plan.vhdPath().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel criar o diretorio de destino", e);
        }

        // Revalida a letra imediatamente antes de agir, reduzindo ao maximo a
        // janela de corrida entre a checagem e a execucao real.
        if (!DriveLetterFinder.isLetterFree(plan.driveLetter())) {
            throw new IllegalStateException(
                    "A letra " + plan.driveLetter() + ": passou a estar em uso. Tente novamente com outra letra.");
        }

        ProcessResult createResult = DiskpartRunner.runScript(buildCreateScript(plan));

        if (!createResult.success() || !Files.exists(plan.vhdPath())) {
            rollback(plan);
            throw new IllegalStateException(
                    "Falha ao criar o disco virtual com o DISKPART (codigo "
                            + createResult.exitCode() + ").\nSaida do DISKPART:\n"
                            + createResult.stdout() + createResult.stderr());
        }

        try {
            new VhdxMount(plan.vhdPath().toAbsolutePath().toString()).mountPermanently();
        } catch (VirtualDiskException e) {
            rollback(plan);
            throw new IllegalStateException(
                    "Falha ao anexar o disco virtual de forma permanente: " + e.getMessage(), e);
        }

        ProcessResult partitionResult = DiskpartRunner.runScript(buildPartitionScript(plan));
        boolean letterAppeared = !DriveLetterFinder.isLetterFree(plan.driveLetter());

        if (!partitionResult.success() || !letterAppeared) {
            rollback(plan);
            throw new IllegalStateException(
                    "Falha ao particionar o disco virtual ou atribuir a letra de unidade (codigo "
                            + partitionResult.exitCode() + ").\nSaida do DISKPART:\n"
                            + partitionResult.stdout() + partitionResult.stderr());
        }

        ProcessResult formatResult = PowerShellRunner.runCommand(buildFormatCommand(plan));

        if (!formatResult.success() || !formatResult.stdout().contains("FORMAT_OK")) {
            rollback(plan);
            throw new IllegalStateException(
                    "Falha ao formatar a unidade como Dev Drive (codigo " + formatResult.exitCode() + ").\nSaida:\n"
                            + formatResult.stdout() + formatResult.stderr());
        }

        return formatResult;
    }

    /**
     * Constroi o script do DISKPART que so cria o arquivo do disco virtual
     * (VHDX) - nao anexa, particiona nem formata nada. A anexacao e feita
     * separadamente, pela API nativa ({@link VhdxMount#mountPermanently()}),
     * nao pelo DISKPART.
     */
    String buildCreateScript(Plan plan) {
        String path = plan.vhdPath().toAbsolutePath().toString();
        long sizeMb = (plan.sizeBytes() + MEGABYTE - 1) / MEGABYTE;

        return "create vdisk file=\"" + path + "\" maximum=" + sizeMb + " type=expandable" + System.lineSeparator()
                + "exit" + System.lineSeparator();
    }

    /**
     * Constroi o script do DISKPART que particiona e atribui a letra de
     * unidade a um disco virtual ja anexado (via {@link VhdxMount}). O disco
     * e sempre reselecionado por caminho de arquivo ("select vdisk
     * file=..."), nunca por numero, o que evita o principal risco classico
     * do DISKPART: atingir por engano um disco fisico existente.
     */
    String buildPartitionScript(Plan plan) {
        String path = plan.vhdPath().toAbsolutePath().toString();

        StringBuilder script = new StringBuilder();
        script.append("select vdisk file=\"").append(path).append("\"").append(System.lineSeparator());
        script.append("create partition primary").append(System.lineSeparator());
        script.append("assign letter=").append(plan.driveLetter()).append(System.lineSeparator());
        script.append("exit").append(System.lineSeparator());
        return script.toString();
    }

    String buildFormatCommand(Plan plan) {
        String escapedLabel = plan.label().replace("'", "''");
        return "$ErrorActionPreference = 'Stop'" + System.lineSeparator()
                + "try {" + System.lineSeparator()
                + "    Format-Volume -DriveLetter '" + plan.driveLetter() + "' -FileSystem ReFS -DevDrive "
                + "-NewFileSystemLabel '" + escapedLabel + "' -Confirm:$false | Out-Null" + System.lineSeparator()
                + "    Write-Output 'FORMAT_OK'" + System.lineSeparator()
                + "    exit 0" + System.lineSeparator()
                + "} catch {" + System.lineSeparator()
                + "    Write-Error $_.Exception.Message" + System.lineSeparator()
                + "    exit 1" + System.lineSeparator()
                + "}" + System.lineSeparator();
    }

    /**
     * Melhor esforco de reversao: desanexa o disco virtual (via
     * {@link VhdxMount#dismount()}, que funciona mesmo que a anexacao nunca
     * tenha chegado a acontecer) e apaga o arquivo incompleto. Nunca lanca
     * excecao, para nao mascarar o erro original que motivou a chamada.
     */
    private void rollback(Plan plan) {
        if (Files.exists(plan.vhdPath())) {
            try {
                new VhdxMount(plan.vhdPath().toAbsolutePath().toString()).dismount();
            } catch (RuntimeException ignored) {
                // melhor esforco: pode nunca ter chegado a ser anexado
            }
        }
        try {
            Files.deleteIfExists(plan.vhdPath());
        } catch (IOException ignored) {
            // melhor esforco
        }
    }
}

