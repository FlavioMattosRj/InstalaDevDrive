package br.nom.mattos.flavio.instaladevdrive.core;

import br.nom.mattos.flavio.virtdisk.VhdxMount;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orquestra a (re)montagem de um Dev Drive ja existente a partir do seu
 * arquivo VHDX - o caso tipico e um disco que foi desanexado (por este
 * programa ou manualmente) e precisa voltar a aparecer com uma letra de
 * unidade.
 *
 * <ol>
 *   <li>{@link #inspect(Plan)} descobre, via {@link VhdxLocator}, se este
 *       VHDX ja esta montado sob alguma letra - sem exigir elevacao. Se
 *       estiver, {@link #execute} nao anexa nada de novo (o
 *       {@code AttachVirtualDisk} nativo NAO e idempotente: chama-lo de novo
 *       sobre um disco ja anexado falha), so confirma que a letra existente
 *       e mesmo um Dev Drive.</li>
 *   <li>Se nao estiver montado, {@link VhdxMount#mountPermanently()} anexa o
 *       disco de forma permanente (mesma API nativa e mesma politica do
 *       {@link DevDriveCreator} - sobrevive a reboots).</li>
 *   <li>Depois do attach, o Windows pode restaurar sozinho uma letra que o
 *       disco teve antes NESTA maquina (o Mount Manager lembra o
 *       mapeamento volume-letra no registro); se nao restaurar nenhuma, ou
 *       restaurar uma diferente da pedida em {@code --letter}, o DISKPART
 *       atribui/move a letra explicitamente - selecionando sempre pelo
 *       caminho do arquivo ({@code select vdisk file=...}), nunca por
 *       numero de disco, mesma protecao usada em {@link DevDriveCreator}.
 *       Um VHDX sem nenhuma particao (criado fora do padrao desta
 *       ferramenta, nunca particionado) nao tem como receber uma letra por
 *       esse caminho - o comando desanexa de volta e recusa, apontando para
 *       {@code create}: este comando nunca particiona nem formata um VHDX
 *       bruto.</li>
 *   <li>Gate final, igual ao {@link DevDriveResizer}/{@link
 *       DevDriveDeleter}: {@code fsutil devdrv query} (so o codigo de
 *       saida) mais a checagem de ReFS confirmam que a unidade e mesmo um
 *       Dev Drive genuino. Se a montagem foi feita por esta execucao e essa
 *       checagem falhar, o disco e desanexado de volta - esta ferramenta so
 *       adota Dev Drives, nunca expõe um VHDX qualquer como se fosse um.</li>
 * </ol>
 *
 * Uma falha ao tentar mover para a letra pedida (letra ja em uso por outra
 * unidade, ou o DISKPART nao conseguir) NAO desanexa o disco: ele fica
 * acessivel na letra que o Windows restaurou, so nao na que foi pedida -
 * desfazer o attach nesse caso pioraria o resultado, nao corrigiria.
 *
 * @author flavio mattos
 */
public final class DevDriveMounter {

    public static final class Plan {

        private final Path vhdxPath;
        private final Character requestedLetter;

        Plan(Path vhdxPath, Character requestedLetter) {
            this.vhdxPath = vhdxPath;
            this.requestedLetter = requestedLetter;
        }

        public Path vhdxPath() {
            return vhdxPath;
        }

        public Character requestedLetter() {
            return requestedLetter;
        }
    }

    /** Resultado de {@link #inspect(Plan)}: se o VHDX ja esta montado, a letra atual; senao {@code null}. */
    public static final class Inspection {

        private final Character alreadyMountedLetter;

        Inspection(Character alreadyMountedLetter) {
            this.alreadyMountedLetter = alreadyMountedLetter;
        }

        public Character alreadyMountedLetter() {
            return alreadyMountedLetter;
        }
    }

    public Plan resolvePlan(Path vhdxPath, Character letter) {
        if (vhdxPath == null) {
            throw new IllegalArgumentException("O comando 'mount' exige --path (o arquivo VHDX a montar).");
        }
        return new Plan(vhdxPath, letter == null ? null : Character.toUpperCase(letter));
    }

    /**
     * Inspeciona o plano sem exigir privilegios de Administrador (pode
     * rodar em {@code --dry-run}): valida o arquivo e a letra pedida (se
     * houver), e descobre se o VHDX ja esta montado.
     */
    public Inspection inspect(Plan plan) {
        Path path = plan.vhdxPath();

        if (path.toString().indexOf('"') >= 0) {
            throw new IllegalArgumentException("O caminho do arquivo VHDX nao pode conter aspas: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Arquivo VHDX nao encontrado: " + path);
        }

        Character requested = plan.requestedLetter();
        if (requested != null) {
            validateLetterUsable(requested);
        }

        Character mountedElsewhere = VhdxLocator.findMountedLetter(path);
        if (mountedElsewhere != null) {
            if (requested != null && !requested.equals(mountedElsewhere)) {
                throw new IllegalStateException(
                        "Este VHDX ja esta montado em " + mountedElsewhere + ": - nao e possivel monta-lo "
                                + "tambem em " + requested + ":. Desanexe primeiro com 'dismount' se quiser "
                                + "trocar de letra.");
            }
            return new Inspection(mountedElsewhere);
        }

        if (requested != null && !DriveLetterFinder.isLetterFree(requested)) {
            throw new IllegalStateException(
                    "A letra " + requested + ": ja esta em uso por outra unidade. Escolha outra letra livre com --letter.");
        }

        return new Inspection(null);
    }

    private static void validateLetterUsable(char letter) {
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
                    "Este programa precisa ser executado como Administrador para montar volumes. "
                            + "Feche esta janela, abra um terminal como Administrador e execute novamente.");
        }
    }

    /**
     * Executa a montagem. Assume {@link #inspect(Plan)} e
     * {@link #checkElevation()} ja chamados com sucesso. Devolve a letra de
     * unidade final da montagem.
     */
    public char execute(Plan plan, Inspection inspection) {
        String vhdxPath = plan.vhdxPath().toAbsolutePath().toString();

        if (inspection.alreadyMountedLetter() != null) {
            char letter = inspection.alreadyMountedLetter();
            requireGenuineDevDrive(letter);
            return letter;
        }

        Character requested = plan.requestedLetter();

        // Revalida imediatamente antes de agir, reduzindo a janela de corrida
        // entre a inspecao/confirmacao e a execucao real.
        if (requested != null && !DriveLetterFinder.isLetterFree(requested)) {
            throw new IllegalStateException(
                    "A letra " + requested + ": passou a estar em uso. Tente novamente com outra letra.");
        }
        if (!Files.isRegularFile(plan.vhdxPath())) {
            throw new IllegalStateException(
                    "O arquivo " + plan.vhdxPath() + " deixou de existir entre a validacao e a execucao.");
        }

        try {
            new VhdxMount(vhdxPath).mountPermanently();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Falha ao anexar o disco virtual de forma permanente: " + e.getMessage(), e);
        }

        // A partir daqui o disco esta anexado por causa desta chamada -
        // falhas que deixem o VHDX sem nenhuma letra utilizavel desanexam de
        // volta (ver bestEffortDismount), para nao deixar um disco anexado e
        // inacessivel para tras. Uma falha so ao MOVER para a letra pedida
        // (reassignLetter) e tratada a parte: nao desanexa, ver javadoc da
        // classe.
        Character currentLetter = VhdxLocator.findMountedLetter(plan.vhdxPath());

        if (currentLetter != null && requested != null && !requested.equals(currentLetter)) {
            currentLetter = reassignLetter(vhdxPath, currentLetter, requested);
        }

        char letterToValidate;
        if (currentLetter != null) {
            letterToValidate = currentLetter;
        } else {
            try {
                letterToValidate = assignFreshLetter(vhdxPath, requested);
            } catch (RuntimeException e) {
                bestEffortDismount(vhdxPath);
                throw e;
            }
        }

        try {
            requireGenuineDevDrive(letterToValidate);
        } catch (RuntimeException e) {
            bestEffortDismount(vhdxPath);
            throw e;
        }

        return letterToValidate;
    }

    /**
     * Tenta mover a letra restaurada automaticamente pelo Windows
     * ({@code currentLetter}) para a letra pedida pelo usuario
     * ({@code requested}). Em falha, lanca sem desanexar - a unidade
     * continua montada e acessivel em {@code currentLetter}.
     */
    private static char reassignLetter(String vhdxPath, char currentLetter, char requested) {
        if (!DriveLetterFinder.isLetterFree(requested)) {
            throw new IllegalStateException(
                    "O VHDX foi anexado em " + currentLetter + ": (letra restaurada automaticamente pelo "
                            + "Windows), mas a letra pedida " + requested + ": ja esta em uso por outra unidade. "
                            + "A unidade permanece montada em " + currentLetter + ":.");
        }

        ProcessResult result = DiskpartRunner.runScript(buildAssignLetterScript(vhdxPath, requested, currentLetter));
        boolean moved = result.success()
                && DriveLetterFinder.isLetterFree(currentLetter)
                && !DriveLetterFinder.isLetterFree(requested);

        if (!moved) {
            throw new IllegalStateException(
                    "O VHDX foi anexado em " + currentLetter + ": (letra restaurada automaticamente pelo "
                            + "Windows), mas nao foi possivel move-lo para a letra pedida " + requested + ": "
                            + "(codigo " + result.exitCode() + "). A unidade permanece montada em " + currentLetter
                            + ":.\nSaida do DISKPART:\n" + result.stdout() + result.stderr());
        }

        return requested;
    }

    /**
     * Atribui uma letra a um VHDX recem-anexado que nao teve nenhuma letra
     * restaurada automaticamente. Se o VHDX nao tiver nenhuma particao (caso
     * de um disco bruto, nunca inicializado), o {@code select partition 1}
     * do DISKPART falha e a letra nunca aparece - a evidencia real
     * ({@code DriveLetterFinder.isLetterFree}), nao o texto do DISKPART, e
     * o que decide isso.
     */
    private static char assignFreshLetter(String vhdxPath, Character requested) {
        char target = requested != null ? requested : DriveLetterFinder.findFreeLetter();

        if (!DriveLetterFinder.isLetterFree(target)) {
            throw new IllegalStateException("A letra " + target + ": passou a estar em uso. Tente novamente.");
        }

        ProcessResult result = DiskpartRunner.runScript(buildAssignLetterScript(vhdxPath, target, null));

        if (!result.success() || DriveLetterFinder.isLetterFree(target)) {
            throw new IllegalStateException(
                    "Nao foi possivel atribuir a letra " + target + ": ao VHDX (codigo " + result.exitCode()
                            + "). Verifique se o arquivo tem uma particao valida - esta ferramenta nao cria "
                            + "particao em VHDX bruto; use 'create' para inicializar um Dev Drive novo.\n"
                            + "Saida do DISKPART:\n" + result.stdout() + result.stderr());
        }

        return target;
    }

    /**
     * Constroi o script do DISKPART que atribui (e, se {@code
     * letterToRemove} nao for nulo, antes remove) a letra de uma particao ja
     * existente de um disco virtual ja anexado. O disco e sempre
     * reselecionado por caminho de arquivo ("select vdisk file=..."), nunca
     * por numero - mesma protecao de {@link DevDriveCreator#buildPartitionScript}.
     * Assume uma unica particao primaria (partition 1), o mesmo layout que
     * {@link DevDriveCreator} sempre cria.
     */
    static String buildAssignLetterScript(String vhdxPath, char newLetter, Character letterToRemove) {
        StringBuilder script = new StringBuilder();
        script.append("select vdisk file=\"").append(vhdxPath).append("\"").append(System.lineSeparator());
        script.append("select partition 1").append(System.lineSeparator());
        if (letterToRemove != null) {
            script.append("remove letter=").append(letterToRemove).append(" noerr").append(System.lineSeparator());
        }
        script.append("assign letter=").append(newLetter).append(System.lineSeparator());
        script.append("exit").append(System.lineSeparator());
        return script.toString();
    }

    /**
     * Gate final: a unidade tem que ser mesmo um Dev Drive - mesma logica de
     * {@link DevDriveResizer}/{@link DevDriveDeleter} (fsutil devdrv query,
     * so o codigo de saida) mais a checagem de ReFS via {@link
     * VirtualDiskInfo}. Exige elevacao (fsutil).
     */
    private static void requireGenuineDevDrive(char letter) {
        ProcessResult result = FsutilRunner.run(devDriveQueryArgs(letter));
        if (!result.success()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao esta marcada como Dev Drive - 'fsutil devdrv query " + letter
                            + ":' saiu com codigo " + result.exitCode() + ". Esta ferramenta so monta Dev Drives "
                            + "ja formatados; use 'create' para inicializar um Dev Drive novo.\nSaida:\n"
                            + result.stdout() + result.stderr());
        }

        VirtualDiskInfo info = VirtualDiskInfo.query(letter);
        if (!info.isFileBackedVirtual() || !"ReFS".equalsIgnoreCase(info.fileSystemType())) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao e um Dev Drive genuino (sistema de arquivos "
                            + info.fileSystemType() + "); esta ferramenta so monta Dev Drives ja formatados.");
        }
    }

    static String[] devDriveQueryArgs(char letter) {
        return new String[] {"devdrv", "query", Character.toUpperCase(letter) + ":"};
    }

    private static void bestEffortDismount(String vhdxPath) {
        try {
            new VhdxMount(vhdxPath).dismount();
        } catch (RuntimeException ignored) {
            // melhor esforco: nao mascara o erro original que motivou a chamada.
        }
    }
}
