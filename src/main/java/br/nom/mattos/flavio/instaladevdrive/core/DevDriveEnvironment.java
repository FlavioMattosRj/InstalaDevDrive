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
 * Dono de tudo relacionado as variaveis de ambiente de maquina {@code
 * DEVDRIVE_HOME} (a unidade que o comando atual acabou de tocar - exceto em
 * {@code delete}, onde nao ha "a unidade do comando" depois de apagada) e
 * {@code DEVDRIVE_ROOTS} (lista, estilo PATH, com a raiz de toda unidade Dev
 * Drive atualmente ativa na maquina): analisa o estado atual, decide os
 * novos valores e grava-os. O programa principal (InstalaDevDrive) so chama
 * os quatro metodos publicos de alto nivel abaixo e usa os dados que eles
 * devolvem ({@link Prediction}/{@link ApplyResult}) para produzir suas
 * proprias mensagens (cores, titulos etc. sao decisao de apresentacao, nao
 * desta classe).
 *
 * <ul>
 *   <li>{@link #predictAfterCreateOrResize(char)}/{@link
 *       #predictAfterDelete(char)} - sem elevacao, usados no plano/dry-run
 *       antes da confirmacao.</li>
 *   <li>{@link #applyAfterCreateOrResize(char)}/{@link
 *       #applyAfterDelete()} - gravam de fato, sempre a ultima etapa de um
 *       comando ja bem sucedido (nunca antes) e sempre elevados.</li>
 * </ul>
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
 * {@link #apply(String, List)} e melhor esforco: uma falha aqui nao desfaz a
 * criacao/redimensionamento/exclusao que ja aconteceu no disco, so gera um
 * aviso para quem chamou (ver {@link ApplyResult#success()}).
 *
 * @author flavio mattos
 */
public final class DevDriveEnvironment {

    public static final String HOME_VARIABLE = "DEVDRIVE_HOME";
    public static final String ROOTS_VARIABLE = "DEVDRIVE_ROOTS";

    static final String APPLY_SUCCESS_MARKER = "Variaveis de ambiente atualizadas com sucesso";

    private DevDriveEnvironment() {
    }

    // =================================================================
    // API de alto nivel - o que o programa principal chama
    // =================================================================

    /**
     * Previsao (sem elevacao) de {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS}
     * apos um {@code create}/{@code resize} bem sucedido: a letra do comando
     * e certa (se a operacao falhar, nada disto e aplicado); as OUTRAS
     * unidades encontradas via {@link #candidates(char...)} (CIM, sem
     * elevacao) entram em {@code DEVDRIVE_ROOTS} com a ressalva de que so
     * serao confirmadas de verdade (via {@code fsutil}) na execucao real, ja
     * elevada.
     */
    public static Prediction predictAfterCreateOrResize(char letter) {
        return buildCreateOrResizePrediction(letter, candidates(letter));
    }

    /**
     * Previsao (sem elevacao) mostrada no plano/dry-run do delete: usa so o
     * filtro barato (CIM), ja que {@code fsutil} exige elevacao e {@code
     * --dry-run} nunca eleva. Com 0 ou 1 candidato remanescente a previsao e
     * exata; com 2 ou mais, o desempate por "criado mais recentemente"
     * depende da confirmacao via {@code fsutil}, que so acontece na
     * execucao real.
     */
    public static Prediction predictAfterDelete(char deletedLetter) {
        return buildDeletePrediction(candidates(deletedLetter));
    }

    /**
     * Grava de fato {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS} apos um
     * {@code create}/{@code resize} bem sucedido: a letra do comando ja foi
     * confirmada como Dev Drive pela propria operacao (formatacao no
     * create, {@code requireDevDrive()} dentro do {@code execute()} no
     * resize) - nao precisa reconfirmar via fsutil. So falta descobrir as
     * OUTRAS unidades ativas na maquina para recalcular {@code
     * DEVDRIVE_ROOTS} por completo. Exige elevacao (fsutil) - so chame apos
     * a operacao principal ja ter tido sucesso.
     */
    public static ApplyResult applyAfterCreateOrResize(char letter) {
        List<Candidate> outras = confirmed(candidates(letter));
        return apply(letter + ":", rootsIncluding(letter, outras));
    }

    /**
     * Grava de fato {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS} apos um
     * {@code delete} bem sucedido: a unidade apagada ja nao esta montada,
     * entao a varredura das remanescentes ja a exclui naturalmente. Sem "a
     * unidade do comando" para virar HOME de graca (ela acabou de ser
     * apagada), o desempate usa a unidade confirmada mais recentemente
     * criada. Se nao sobrar nenhuma, as duas variaveis sao removidas. Exige
     * elevacao (fsutil) - so chame apos a operacao principal ja ter tido
     * sucesso.
     */
    public static ApplyResult applyAfterDelete() {
        List<Candidate> confirmadas = confirmed(candidates());
        Character home = mostRecentlyCreated(confirmadas).orElse(null);
        return apply(home == null ? null : home + ":", lettersOf(confirmadas));
    }

    // =================================================================
    // Previsao - insumos para as mensagens do programa principal
    // =================================================================

    /** Previsao de {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS}, ja formatada para exibicao no plano. */
    public static final class Prediction {

        private final List<String> fieldLines;
        private final String note;

        Prediction(List<String> fieldLines, String note) {
            this.fieldLines = fieldLines;
            this.note = note;
        }

        /** Exatamente duas linhas, ja no formato "NOME .....: valor" - uma por variavel. */
        public List<String> fieldLines() {
            return fieldLines;
        }

        /** Ressalva adicional (ex.: pendente de confirmacao apos elevacao); {@code null} se nao houver. */
        public String note() {
            return note;
        }
    }

    /**
     * Logica pura de {@link #predictAfterCreateOrResize(char)}: recebe as
     * OUTRAS candidatas ja levantadas, sem chamar {@link #candidates} de
     * novo. Extraido (visivel para testes) para verificar a previsao sem
     * depender de unidades reais.
     */
    static Prediction buildCreateOrResizePrediction(char letter, List<Candidate> outras) {
        List<Character> raizes = rootsIncluding(letter, outras);
        String rootsValue = buildRootsValue(raizes);

        List<String> fieldLines = List.of(
                formatPlanField(HOME_VARIABLE, letter + ":"),
                formatPlanField(ROOTS_VARIABLE, rootsValue));
        String note = outras.isEmpty() ? null : "(as demais unidades serao confirmadas apos elevacao)";
        return new Prediction(fieldLines, note);
    }

    /**
     * Logica pura de {@link #predictAfterDelete(char)}: recebe as candidatas
     * remanescentes ja levantadas, sem chamar {@link #candidates} de novo.
     * Extraido (visivel para testes) para verificar a previsao sem depender
     * de unidades reais.
     */
    static Prediction buildDeletePrediction(List<Candidate> restantes) {
        if (restantes.isEmpty()) {
            List<String> fieldLines = List.of(
                    formatPlanField(HOME_VARIABLE, "(sera removida)"),
                    formatPlanField(ROOTS_VARIABLE, "(sera removida)"));
            return new Prediction(fieldLines, null);
        }

        List<Character> raizes = lettersOf(restantes);
        String rootsValue = buildRootsValue(raizes);

        if (restantes.size() == 1) {
            char unica = restantes.get(0).letter();
            List<String> fieldLines = List.of(
                    formatPlanField(HOME_VARIABLE, unica + ":"),
                    formatPlanField(ROOTS_VARIABLE, rootsValue));
            return new Prediction(fieldLines, null);
        }

        List<String> fieldLines = List.of(
                formatPlanField(HOME_VARIABLE, "a definir entre " + joinLetters(raizes)),
                formatPlanField(ROOTS_VARIABLE, rootsValue));
        return new Prediction(fieldLines, "(sujeitas a confirmacao apos elevacao)");
    }

    /**
     * Formata um campo do plano no mesmo estilo das linhas fixas do
     * programa principal ("Nome/rotulo ......: valor"): rotulo, espaco,
     * pontos ate a coluna 18 e ": valor" - sempre 20 colunas de prefixo.
     * Extraido (visivel para testes) para verificar o alinhamento sem
     * depender de nada externo.
     */
    static String formatPlanField(String label, String value) {
        StringBuilder linha = new StringBuilder(label);
        linha.append(' ');
        while (linha.length() < 18) {
            linha.append('.');
        }
        linha.append(": ").append(value);
        return linha.toString();
    }

    /** Junta letras de unidade em uma lista legivel: "E:", "E: e K:" ou "E:, K: e M:". */
    static String joinLetters(List<Character> letras) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < letras.size(); i++) {
            if (i > 0) {
                texto.append(i == letras.size() - 1 ? " e " : ", ");
            }
            texto.append(letras.get(i)).append(':');
        }
        return texto.toString();
    }

    // =================================================================
    // Deteccao - candidatas (sem elevacao) e confirmacao (com elevacao)
    // =================================================================

    /** Uma letra montada que passou no filtro barato (sem elevacao) de "parece Dev Drive". */
    static final class Candidate {

        private final char letter;
        private final VirtualDiskInfo info;

        Candidate(char letter, VirtualDiskInfo info) {
            this.letter = letter;
            this.info = info;
        }

        char letter() {
            return letter;
        }

        VirtualDiskInfo info() {
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
    static List<Candidate> candidates(char... excludedLetters) {
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
    static List<Candidate> confirmed(List<Candidate> candidates) {
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
    static Optional<Character> mostRecentlyCreated(List<Candidate> confirmedCandidates) {
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

    /** Extrai as letras de uma lista de candidatos, na mesma ordem. */
    static List<Character> lettersOf(List<Candidate> candidates) {
        List<Character> letters = new ArrayList<>();
        for (Candidate candidate : candidates) {
            letters.add(candidate.letter());
        }
        return letters;
    }

    /** Como {@link #lettersOf(List)}, mas com {@code letter} sempre na frente. */
    static List<Character> rootsIncluding(char letter, List<Candidate> others) {
        List<Character> roots = new ArrayList<>();
        roots.add(letter);
        roots.addAll(lettersOf(others));
        return roots;
    }

    // =================================================================
    // Gravacao
    // =================================================================

    /**
     * Monta o valor de {@code DEVDRIVE_ROOTS}: raizes separadas por {@code
     * ;}, cada uma com barra final (estilo PATH), em ordem alfabetica para
     * saida deterministica. Lista vazia produz string vazia - {@link
     * #apply(String, List)} interpreta isso como "remover a variavel".
     */
    static String buildRootsValue(List<Character> letters) {
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
     * remove a variavel correspondente. Nunca lanca excecao por conta
     * propria: devolve um {@link ApplyResult} para quem chamou decidir como
     * avisar o usuario de uma eventual falha, sem desfazer a operacao
     * principal.
     */
    static ApplyResult apply(String home, List<Character> rootLetters) {
        String rootsValue = buildRootsValue(rootLetters);
        ProcessResult result = PowerShellRunner.runCommand(buildApplyCommand(home, rootsValue));
        boolean success = result.success() && result.stdout().contains(APPLY_SUCCESS_MARKER);
        return new ApplyResult(success, home, rootsValue, result);
    }
}
