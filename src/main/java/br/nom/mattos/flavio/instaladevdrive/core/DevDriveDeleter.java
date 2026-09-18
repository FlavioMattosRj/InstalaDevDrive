package br.nom.mattos.flavio.instaladevdrive.core;

import br.nom.mattos.flavio.virtdisk.VhdxMount;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Orquestra a exclusao permanente de um Dev Drive ja existente:
 * <ol>
 *   <li>{@link VirtualDiskInfo#query(char)} descobre, a partir da letra de
 *       unidade, qual arquivo VHDX lastreia o volume - mesma inspecao usada
 *       por {@link DevDriveResizer}, por evidencia tipada do modulo Storage,
 *       nunca texto localizado.</li>
 *   <li>O disco e DESANEXADO ({@link VhdxMount#dismount()}) - por ser um
 *       disco baseado em arquivo (file backed virtual), desanexar remove a
 *       letra de unidade e a particao junto, nao so o "conteudo".</li>
 *   <li>O arquivo VHDX e apagado do disco fisico. Esta etapa NAO tem
 *       reversao: uma vez apagado, os dados da unidade estao perdidos.</li>
 * </ol>
 *
 * Validacoes antes de qualquer alteracao: {@link #inspect(Plan)} (nao exige
 * elevacao) confere que a letra esta em uso, o disco e um VHDX baseado em
 * arquivo, o volume e ReFS, e o arquivo VHDX existe e nao contem aspas. O
 * gate final "e mesmo um Dev Drive" ({@code fsutil devdrv query}, so o
 * codigo de saida) roda no inicio do {@link #execute}, ja elevado - o fsutil
 * precisa de Administrador.
 *
 * @author flavio mattos
 */
public final class DevDriveDeleter {

    public static final class Plan {

        private final char driveLetter;

        Plan(char driveLetter) {
            this.driveLetter = driveLetter;
        }

        public char driveLetter() {
            return driveLetter;
        }
    }

    public Plan resolvePlan(char letter) {
        return new Plan(Character.toUpperCase(letter));
    }

    /**
     * Inspeciona a unidade e valida que ela pode ser excluida, sem exigir
     * privilegios de Administrador (pode rodar em {@code --dry-run}).
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
                    "A letra " + letter + ": nao esta em uso; nao ha Dev Drive para excluir.");
        }

        VirtualDiskInfo info = VirtualDiskInfo.query(letter);

        if (!info.isFileBackedVirtual()) {
            throw new IllegalStateException(
                    "A unidade " + letter + ": nao e um disco virtual baseado em arquivo (VHDX); "
                            + "esta ferramenta so exclui Dev Drives em VHDX.");
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
                    "Este programa precisa ser executado como Administrador para excluir volumes. "
                            + "Feche esta janela, abra um terminal como Administrador e execute novamente.");
        }
    }

    /**
     * Executa a exclusao. Assume {@link #inspect(Plan)} e
     * {@link #checkElevation()} ja chamados com sucesso; {@code info} e o
     * retorno de {@link #inspect(Plan)}. Irreversivel: o arquivo VHDX e
     * apagado do disco fisico.
     */
    public void execute(Plan plan, VirtualDiskInfo info) {
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

        try {
            new VhdxMount(vhdxPath).dismount();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Nao foi possivel desanexar a unidade " + letter + ": para exclui-la. "
                            + "Feche todos os programas e terminais que estejam usando " + letter
                            + ": e tente novamente. O arquivo VHDX NAO foi apagado. Detalhe: " + e.getMessage(), e);
        }

        if (!DriveLetterFinder.isLetterFree(letter)) {
            throw new IllegalStateException(
                    "A letra " + letter + ": continuou visivel apos a desanexacao; o arquivo VHDX NAO foi apagado.");
        }

        try {
            Files.delete(info.vhdxPath());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "A unidade " + letter + ": foi desanexada, mas o arquivo VHDX nao pode ser apagado: "
                            + vhdxPath + ". Apague-o manualmente. Detalhe: " + e.getMessage(), e);
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
                            + ":' saiu com codigo " + result.exitCode() + ". Esta ferramenta so exclui "
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
}
