package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mantem as variaveis de ambiente de maquina {@code DEVDRIVE_HOME} (a
 * unidade que o comando atual acabou de tocar - exceto em {@code delete},
 * onde nao ha "a unidade do comando" depois de apagada) e {@code
 * DEVDRIVE_ROOTS} (lista, estilo PATH, com a raiz de toda unidade Dev Drive
 * atualmente ativa na maquina).
 *
 * <p>A deteccao usa dois niveis, na mesma linha do resto do projeto:
 * <ol>
 *   <li>{@link #candidates(char...)} - barato, sem elevacao: {@code
 *       File.listRoots()} (nenhum processo) para achar as letras realmente
 *       montadas, e {@link VirtualDiskInfo#query(char)} (CIM, sem elevacao)
 *       para filtrar as que parecem Dev Drive (VHDX baseado em arquivo +
 *       ReFS). Uma letra "parecida" ainda pode nao ser um Dev Drive de
 *       verdade (por exemplo, um VHDX formatado ReFS na mao, sem {@code
 *       Format-Volume -DevDrive}) - por isso "candidate", nao "confirmed".</li>
 *   <li>{@link #confirmed(List)} - exige elevacao: {@code fsutil devdrv
 *       query} (mesmo gate final usado por {@link DevDriveResizer} e
 *       {@link DevDriveDeleter}) confirma de verdade cada candidato.</li>
 * </ol>
 *
 * {@link #apply(String, String)} e sempre a ultima etapa de um comando bem
 * sucedido (nunca antes) e e melhor esforco: uma falha aqui nao desfaz a
 * criacao/redimensionamento/exclusao que ja aconteceu no disco, so gera um
 * aviso para quem chamou.
 *
 * @author flavio mattos
 */
public final class DevDriveEnvironment {

    public static final String HOME_VARIABLE = "DEVDRIVE_HOME";
    public static final String ROOTS_VARIABLE = "DEVDRIVE_ROOTS";

    static final String APPLY_SUCCESS_MARKER = "Variaveis de ambiente atualizadas com sucesso";

    private DevDriveEnvironment() {
    }

    /** Uma letra montada que passou no filtro barato (sem elevacao) de "parece Dev Drive". */
    public static final class Candidate {

        private final char letter;
        private final VirtualDiskInfo info;

        Candidate(char letter, VirtualDiskInfo info) {
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

    /**
     * Varre as unidades montadas ({@code File.listRoots()}, sem custo de
     * processo) e retorna as que parecem Dev Drive por {@link
     * VirtualDiskInfo} (CIM, sem elevacao). Uma letra cuja consulta falhar
     * (sem particao, CIM incompativel etc.) e simplesmente ignorada - nunca
     * derruba a varredura inteira. Letras em {@code excludedLetters} sao
     * puladas (usado pelo {@code delete}, para prever o estado sem a unidade
     * que esta prestes a ser apagada, mas ainda esta montada no momento do
     * planejamento).
     */
    public static List<Candidate> candidates(char... excludedLetters) {
        List<Candidate> result = new ArrayList<>();

        rootLoop:
        for (File root : File.listRoots()) {
            String path = root.getPath();
            if (path.isEmpty()) {
                continue;
            }
            char letter = Character.toUpperCase(path.charAt(0));
            for (char excluded : excludedLetters) {
                if (letter == Character.toUpperCase(excluded)) {
                    continue rootLoop;
                }
            }
            try {
                VirtualDiskInfo info = VirtualDiskInfo.query(letter);
                if (info.isFileBackedVirtual() && "ReFS".equalsIgnoreCase(info.fileSystemType())) {
                    result.add(new Candidate(letter, info));
                }
            } catch (RuntimeException ignored) {
                // Unidade sem particao, sem suporte a CIM etc. - nao e candidata.
            }
        }

        return result;
    }

    /**
     * Confirma, via {@code fsutil devdrv query} (exige elevacao - so chame
     * depois de {@code checkElevation()}), quais dos candidatos sao Dev
     * Drives de verdade.
     */
    public static List<Candidate> confirmed(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ProcessResult r = FsutilRunner.run(devDriveQueryArgs(candidate.letter()));
            if (r.success()) {
                result.add(candidate);
            }
        }
        return result;
    }

    static String[] devDriveQueryArgs(char letter) {
        return new String[] {"devdrv", "query", Character.toUpperCase(letter) + ":"};
    }

    /**
     * Entre os candidatos confirmados remanescentes (uso do {@code delete}:
     * nao ha mais "a unidade do comando" para virar HOME de graca, como em
     * create/resize), escolhe a letra cujo arquivo VHDX foi criado por
     * ultimo. Falha ao ler o timestamp de um candidato (arquivo inacessivel)
     * o trata como o mais antigo possivel, para nunca vencer o desempate por
     * engano.
     */
    public static Optional<Character> mostRecentlyCreated(List<Candidate> confirmedCandidates) {
        Candidate winner = null;
        FileTime winnerTime = null;
        for (Candidate candidate : confirmedCandidates) {
            FileTime time = creationTime(candidate);
            if (winner == null || time.compareTo(winnerTime) > 0) {
                winner = candidate;
                winnerTime = time;
            }
        }
        return winner == null ? Optional.empty() : Optional.of(winner.letter());
    }

    static FileTime creationTime(Candidate candidate) {
        try {
            return Files.readAttributes(candidate.info().vhdxPath(), BasicFileAttributes.class).creationTime();
        } catch (IOException e) {
            return FileTime.fromMillis(Long.MIN_VALUE);
        }
    }

    /**
     * Monta o valor de {@code DEVDRIVE_ROOTS}: raizes separadas por {@code
     * ;}, cada uma com barra final (estilo PATH), em ordem alfabetica para
     * saida deterministica. Lista vazia produz string vazia - {@link
     * #apply(String, List)} interpreta isso como "remover a variavel". Publico
     * tambem para quem so quer mostrar uma previsao (plano/dry-run), sem
     * gravar nada.
     */
    public static String buildRootsValue(List<Character> letters) {
        List<Character> sorted = new ArrayList<>(letters);
        sorted.sort(null);

        StringBuilder value = new StringBuilder();
        for (char letter : sorted) {
            if (value.length() > 0) {
                value.append(';');
            }
            value.append(Character.toUpperCase(letter)).append(":\\");
        }
        return value.toString();
    }

    /**
     * Constroi o script PowerShell que aplica (ou remove) as duas variaveis
     * de ambiente de MAQUINA de uma vez. {@code home}/{@code roots} nulos ou
     * vazios removem a variavel correspondente ({@code
     * SetEnvironmentVariable} com valor {@code $null} apaga a variavel,
     * comportamento documentado do .NET). Extraido (visivel para testes)
     * para verificar o texto gerado sem executar processo nenhum, mesmo
     * padrao dos outros {@code buildXxxCommand} do projeto.
     */
    static String buildApplyCommand(String home, String roots) {
        String nl = System.lineSeparator();
        String homeArg = (home == null || home.isEmpty()) ? "$null" : "'" + home + "'";
        String rootsArg = (roots == null || roots.isEmpty()) ? "$null" : "'" + roots + "'";

        return "$ErrorActionPreference = 'Stop'" + nl
                + "try {" + nl
                + "    [Environment]::SetEnvironmentVariable('" + HOME_VARIABLE + "', " + homeArg + ", 'Machine')" + nl
                + "    [Environment]::SetEnvironmentVariable('" + ROOTS_VARIABLE + "', " + rootsArg + ", 'Machine')" + nl
                + "    Write-Output '" + APPLY_SUCCESS_MARKER + "'" + nl
                + "    exit 0" + nl
                + "} catch {" + nl
                + "    Write-Error $_.Exception.Message" + nl
                + "    exit 1" + nl
                + "}" + nl;
    }

    /** Resultado de {@link #apply(String, List)}: o que foi (tentado) gravar e se deu certo. */
    public static final class ApplyResult {

        private final boolean success;
        private final String home;
        private final String rootsValue;
        private final ProcessResult processResult;

        private ApplyResult(boolean success, String home, String rootsValue, ProcessResult processResult) {
            this.success = success;
            this.home = home;
            this.rootsValue = rootsValue;
            this.processResult = processResult;
        }

        public boolean success() {
            return success;
        }

        /** Valor gravado (ou tentado) em {@code DEVDRIVE_HOME}; {@code null} se a variavel foi removida. */
        public String home() {
            return home;
        }

        /** Valor gravado (ou tentado) em {@code DEVDRIVE_ROOTS}; vazio se a variavel foi removida. */
        public String rootsValue() {
            return rootsValue;
        }

        public ProcessResult processResult() {
            return processResult;
        }
    }

    /**
     * Escreve de fato {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS} no
     * registro de maquina - {@code home} nulo ou {@code rootLetters} vazia
     * remove a variavel correspondente. Sempre a ultima etapa de um comando
     * ja bem sucedido: nunca lanca excecao por conta propria - devolve um
     * {@link ApplyResult} para quem chamou decidir como avisar o usuario de
     * uma eventual falha, sem desfazer a operacao principal.
     */
    public static ApplyResult apply(String home, List<Character> rootLetters) {
        String rootsValue = buildRootsValue(rootLetters);
        ProcessResult result = PowerShellRunner.runCommand(buildApplyCommand(home, rootsValue));
        boolean success = result.success() && result.stdout().contains(APPLY_SUCCESS_MARKER);
        return new ApplyResult(success, home, rootsValue, result);
    }
}
