package br.nom.mattos.flavio.instaladevdrive;

import br.nom.mattos.flavio.instaladevdrive.cli.CommandLineArgs;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveCreator;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveDeleter;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveEnvironment;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveResizer;
import br.nom.mattos.flavio.instaladevdrive.core.ProcessResult;
import br.nom.mattos.flavio.instaladevdrive.core.SizeParser;
import br.nom.mattos.flavio.instaladevdrive.core.VerboseLog;
import br.nom.mattos.flavio.instaladevdrive.core.VirtualDiskInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/**
 * Ponto de entrada do InstalaDevDrive. Três subcomandos:
 *
 * <ul>
 *   <li>{@code create} (padrao): cria um Dev Drive do Windows a partir de um
 *       novo disco virtual (VHDX). Usa o DISKPART apenas para criar o
 *       arquivo e depois particionar/atribuir a letra; a anexacao e feita
 *       pela Windows Virtual Disk API nativa, de forma permanente. A
 *       formatacao final usa {@code Format-Volume -DevDrive}.</li>
 *   <li>{@code resize}: aumenta um Dev Drive ja existente - expande o VHDX
 *       ({@code ExpandVirtualDisk} nativo, com o disco brevemente
 *       desanexado) e estende a particao ReFS ({@code Resize-Partition},
 *       online). Nao usa DISKPART.</li>
 *   <li>{@code delete}: exclui permanentemente um Dev Drive ja existente -
 *       desanexa o disco virtual e apaga o arquivo VHDX do disco fisico.
 *       Irreversivel; exige confirmacao explicita a menos que
 *       {@code --yes} seja passado.</li>
 * </ul>
 *
 * Todos validam a letra de unidade antes e depois de cada etapa, por
 * evidencia real do sistema de arquivos, e nunca confiam no texto
 * (localizavel) impresso pelas ferramentas do Windows.
 *
 * @author flavio mattos
 */
public class InstalaDevDrive {

    /** Caractere de escape ANSI (ASCII 27, 0x1B); prefixo de toda sequencia de cor. */
    private static final char ESC = 27;
    private static final String ANSI_BRIGHT_GREEN = ESC + "[92m";
    private static final String ANSI_BRIGHT_YELLOW = ESC + "[93m";
    private static final String ANSI_RESET = ESC + "[0m";

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(2);
        } catch (IllegalStateException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Erro inesperado: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        CommandLineArgs cli = CommandLineArgs.parse(args);

        if (cli.help()) {
            printHelp(cli.command());
            return;
        }

        VerboseLog.setEnabled(cli.verbose());

        switch (cli.command()) {
            case RESIZE:
                runResize(cli);
                break;
            case DELETE:
                runDelete(cli);
                break;
            case CREATE:
            default:
                runCreate(cli);
                break;
        }
    }

    private static void runCreate(CommandLineArgs cli) {
        DevDriveCreator creator = new DevDriveCreator();
        DevDriveCreator.Plan plan = creator.resolvePlan(cli.name(), cli.size(), cli.letter(), cli.directory());
        creator.validatePlan(plan);

        System.out.println("=== InstalaDevDrive (create) ===");
        System.out.println("Nome/rotulo ......: " + plan.label());
        System.out.println("Arquivo VHDX .....: " + plan.vhdPath());
        System.out.println("Letra de unidade .: " + plan.driveLetter() + ":");
        System.out.println("Tamanho ..........: " + SizeParser.toHumanReadable(plan.sizeBytes()));
        System.out.println();
        EnvPrediction envPrediction = predictEnvironmentAfterCreateOrResize(plan.driveLetter());
        printEnvPrediction(envPrediction);
        System.out.println();

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            System.out.println(" 1. Validar pre-requisitos (Administrador, letra livre, arquivo inexistente)");
            System.out.println(" 2. Criar o arquivo VHDX via DISKPART");
            System.out.println(" 3. Anexar o disco de forma permanente via Windows Virtual Disk API (sobrevive a reboots)");
            System.out.println(" 4. Criar particao e atribuir a letra " + plan.driveLetter() + ": via DISKPART");
            System.out.println(" 5. Formatar como Dev Drive (ReFS) via Format-Volume -DevDrive");
            System.out.println(" 6. " + envPrediction.fieldLines().get(0));
            System.out.println(" 7. " + envPrediction.fieldLines().get(1));
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        creator.checkElevation();

        if (!cli.assumeYes() && !confirm("Esta operacao ira criar e FORMATAR uma nova unidade. Continuar? [s/N]: ")) {
            System.out.println("Operacao cancelada pelo usuario.");
            return;
        }

        System.out.println("Criando o Dev Drive, aguarde...");
        ProcessResult result = creator.execute(plan);

        printProcessOutput(result);

        if (!result.success()) {
            throw new IllegalStateException("A criacao do Dev Drive falhou (codigo " + result.exitCode() + ").");
        }

        updateEnvironmentAfterCreateOrResize(plan.driveLetter());

        System.out.println();
        System.out.println(ANSI_BRIGHT_GREEN + "Dev Drive criado com sucesso." + ANSI_RESET);
    }

    private static void runResize(CommandLineArgs cli) {
        DevDriveResizer resizer = new DevDriveResizer();
        DevDriveResizer.Plan plan = resizer.resolvePlan(cli.letter(), cli.size());
        VirtualDiskInfo info = resizer.inspect(plan);
        boolean growVhdx = resizer.requiresVhdxGrowth(plan, info);

        System.out.println("=== InstalaDevDrive (resize) ===");
        System.out.println("Unidade ..........: " + plan.driveLetter() + ":");
        System.out.println("Arquivo VHDX .....: " + info.vhdxPath());
        System.out.println("Capacidade atual .: " + SizeParser.toHumanReadable(info.diskSizeBytes()));
        System.out.println("Nova capacidade ..: " + SizeParser.toHumanReadable(plan.newSizeBytes()));
        System.out.println();
        EnvPrediction envPrediction = predictEnvironmentAfterCreateOrResize(plan.driveLetter());
        printEnvPrediction(envPrediction);
        System.out.println();

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            System.out.println(" 0. Confirmar que " + plan.driveLetter()
                    + ": e um Dev Drive (fsutil devdrv query - exige Administrador)");
            if (growVhdx) {
                System.out.println(" 1. Desanexar " + plan.driveLetter() + ": (a unidade fica offline por alguns segundos)");
                System.out.println(" 2. Expandir o VHDX para " + SizeParser.toHumanReadable(plan.newSizeBytes())
                        + " via ExpandVirtualDisk (API nativa)");
                System.out.println(" 3. Reanexar " + plan.driveLetter() + ": de forma permanente (sobrevive a reboots)");
                System.out.println(" 4. Estender a particao ReFS para preencher o espaco novo (Resize-Partition)");
                System.out.println(" 5. Confirmar via MSFT_Disk/MSFT_Volume que o tamanho aumentou");
                System.out.println(" 6. " + envPrediction.fieldLines().get(0));
                System.out.println(" 7. " + envPrediction.fieldLines().get(1));
            } else {
                System.out.println(" 1. O VHDX ja tem " + SizeParser.toHumanReadable(info.diskSizeBytes())
                        + "; nao sera expandido nem desanexado.");
                System.out.println(" 2. Estender a particao ReFS (online) para preencher qualquer espaco nao alocado");
                System.out.println(" 3. Confirmar via MSFT_Volume que o tamanho nao regrediu");
                System.out.println(" 4. " + envPrediction.fieldLines().get(0));
                System.out.println(" 5. " + envPrediction.fieldLines().get(1));
            }
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        resizer.checkElevation();

        if (growVhdx && !cli.assumeYes()
                && !confirm("Esta operacao ira DESANEXAR " + plan.driveLetter() + ": por alguns segundos para expandir "
                        + "o disco virtual. Nenhum dado e apagado, mas nada pode estar usando " + plan.driveLetter()
                        + ": durante a operacao. Continuar? [s/N]: ")) {
            System.out.println("Operacao cancelada pelo usuario.");
            return;
        }

        System.out.println(growVhdx
                ? "Redimensionando (a unidade ficara offline brevemente), aguarde..."
                : "Estendendo a particao ReFS, aguarde...");
        ProcessResult result = resizer.execute(plan, info);

        printProcessOutput(result);

        updateEnvironmentAfterCreateOrResize(plan.driveLetter());

        System.out.println();
        System.out.println(ANSI_BRIGHT_GREEN + "Unidade " + plan.driveLetter()
                + ": redimensionada com sucesso." + ANSI_RESET);
    }

    private static void runDelete(CommandLineArgs cli) {
        DevDriveDeleter deleter = new DevDriveDeleter();
        DevDriveDeleter.Plan plan = deleter.resolvePlan(cli.letter());
        VirtualDiskInfo info = deleter.inspect(plan);

        System.out.println("=== InstalaDevDrive (delete) ===");
        System.out.println("Unidade ..........: " + plan.driveLetter() + ":");
        System.out.println("Arquivo VHDX .....: " + info.vhdxPath());
        System.out.println("Tamanho ..........: " + SizeParser.toHumanReadable(info.diskSizeBytes()));
        System.out.println();
        EnvPrediction envPrediction = predictEnvironmentAfterDelete(plan.driveLetter());
        printEnvPrediction(envPrediction);
        System.out.println();

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            System.out.println(" 0. Confirmar que " + plan.driveLetter()
                    + ": e um Dev Drive (fsutil devdrv query - exige Administrador)");
            System.out.println(" 1. Desanexar " + plan.driveLetter() + ": (a letra de unidade deixa de existir)");
            System.out.println(" 2. Apagar o arquivo VHDX: " + info.vhdxPath());
            System.out.println(" 3. " + envPrediction.fieldLines().get(0));
            System.out.println(" 4. " + envPrediction.fieldLines().get(1));
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        deleter.checkElevation();

        if (!cli.assumeYes() && !confirm(ANSI_BRIGHT_YELLOW + "A unidade " + plan.driveLetter() + ": e o arquivo VHDX serao "
                + "PERMANENTEMENTE APAGADOS, com todos os dados. Esta acao NAO PODE ser desfeita. "
                + "Continuar? [s/N]: " + ANSI_RESET)) {
            System.out.println("Operacao cancelada pelo usuario.");
            return;
        }

        System.out.println("Excluindo o Dev Drive, aguarde...");
        deleter.execute(plan, info);

        updateEnvironmentAfterDelete();

        System.out.println();
        System.out.println(ANSI_BRIGHT_GREEN + "Unidade " + plan.driveLetter()
                + ": excluida com sucesso." + ANSI_RESET);
    }

    /**
     * Etapa final (melhor esforco) de create/resize: a letra do comando ja
     * foi confirmada como Dev Drive pela propria operacao (formatacao no
     * create, {@code requireDevDrive()} dentro do {@code execute()} no
     * resize) - nao precisa reconfirmar via fsutil. So falta descobrir as
     * OUTRAS unidades ativas na maquina para recalcular
     * {@code DEVDRIVE_ROOTS} por completo.
     */
    private static void updateEnvironmentAfterCreateOrResize(char letter) {
        List<DevDriveEnvironment.Candidate> outras = DevDriveEnvironment.confirmed(
                DevDriveEnvironment.candidates(letter));

        List<Character> roots = new ArrayList<>();
        roots.add(letter);
        for (DevDriveEnvironment.Candidate candidate : outras) {
            roots.add(candidate.letter());
        }

        reportEnvironmentApply(DevDriveEnvironment.apply(letter + ":", roots));
    }

    /**
     * Etapa final (melhor esforco) do delete: a unidade apagada ja nao esta
     * montada, entao a varredura das remanescentes ja a exclui
     * naturalmente. Sem "a unidade do comando" para virar HOME de graca (ela
     * acabou de ser apagada), o desempate usa a unidade confirmada mais
     * recentemente criada. Se nao sobrar nenhuma, as duas variaveis sao
     * removidas.
     */
    private static void updateEnvironmentAfterDelete() {
        List<DevDriveEnvironment.Candidate> confirmadas = DevDriveEnvironment.confirmed(
                DevDriveEnvironment.candidates());

        List<Character> roots = new ArrayList<>();
        for (DevDriveEnvironment.Candidate candidate : confirmadas) {
            roots.add(candidate.letter());
        }

        Optional<Character> novoHome = DevDriveEnvironment.mostRecentlyCreated(confirmadas);
        String home = novoHome.map(letter -> letter + ":").orElse(null);
        reportEnvironmentApply(DevDriveEnvironment.apply(home, roots));
    }

    /** Previsao de {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS}, ja formatada para exibicao no plano. */
    private static final class EnvPrediction {

        private final List<String> fieldLines;
        private final String note;

        EnvPrediction(List<String> fieldLines, String note) {
            this.fieldLines = fieldLines;
            this.note = note;
        }

        /** Exatamente duas linhas, ja no formato "NOME .....: valor" - uma por variavel. */
        List<String> fieldLines() {
            return fieldLines;
        }

        /** Ressalva adicional (ex.: pendente de confirmacao apos elevacao); {@code null} se nao houver. */
        String note() {
            return note;
        }
    }

    /**
     * Previsao (sem elevacao) de {@code DEVDRIVE_HOME}/{@code DEVDRIVE_ROOTS}
     * apos um {@code create}/{@code resize} bem sucedido: a letra do comando
     * e certa (se a operacao falhar, nada disto e aplicado); as OUTRAS
     * unidades encontradas via {@link DevDriveEnvironment#candidates(char...)}
     * (CIM, sem elevacao) entram em {@code DEVDRIVE_ROOTS} com a ressalva de
     * que so serao confirmadas de verdade (via {@code fsutil}) na execucao
     * real, ja elevada.
     */
    private static EnvPrediction predictEnvironmentAfterCreateOrResize(char letra) {
        List<DevDriveEnvironment.Candidate> outras = DevDriveEnvironment.candidates(letra);

        List<Character> raizes = new ArrayList<>();
        raizes.add(letra);
        for (DevDriveEnvironment.Candidate candidate : outras) {
            raizes.add(candidate.letter());
        }
        String rootsValue = DevDriveEnvironment.buildRootsValue(raizes);

        List<String> fieldLines = List.of(
                formatPlanField(DevDriveEnvironment.HOME_VARIABLE, letra + ":"),
                formatPlanField(DevDriveEnvironment.ROOTS_VARIABLE, rootsValue));
        String note = outras.isEmpty() ? null : "(as demais unidades serao confirmadas apos elevacao)";
        return new EnvPrediction(fieldLines, note);
    }

    /**
     * Previsao (sem elevacao) mostrada no plano/dry-run do delete: usa so o
     * filtro barato (VirtualDiskInfo/CIM), ja que {@code fsutil} exige
     * elevacao e {@code --dry-run} nunca eleva. Com 0 ou 1 candidato
     * remanescente a previsao e exata; com 2 ou mais, o desempate por
     * "criado mais recentemente" depende da confirmacao via {@code fsutil},
     * que so acontece na execucao real.
     */
    private static EnvPrediction predictEnvironmentAfterDelete(char letraApagada) {
        List<DevDriveEnvironment.Candidate> restantes = DevDriveEnvironment.candidates(letraApagada);

        if (restantes.isEmpty()) {
            List<String> fieldLines = List.of(
                    formatPlanField(DevDriveEnvironment.HOME_VARIABLE, "(sera removida)"),
                    formatPlanField(DevDriveEnvironment.ROOTS_VARIABLE, "(sera removida)"));
            return new EnvPrediction(fieldLines, null);
        }

        List<Character> raizes = new ArrayList<>();
        for (DevDriveEnvironment.Candidate candidate : restantes) {
            raizes.add(candidate.letter());
        }
        String rootsValue = DevDriveEnvironment.buildRootsValue(raizes);

        if (restantes.size() == 1) {
            char unica = restantes.get(0).letter();
            List<String> fieldLines = List.of(
                    formatPlanField(DevDriveEnvironment.HOME_VARIABLE, unica + ":"),
                    formatPlanField(DevDriveEnvironment.ROOTS_VARIABLE, rootsValue));
            return new EnvPrediction(fieldLines, null);
        }

        List<String> fieldLines = List.of(
                formatPlanField(DevDriveEnvironment.HOME_VARIABLE, "o mais novo entre " + joinLetters(raizes)),
                formatPlanField(DevDriveEnvironment.ROOTS_VARIABLE, rootsValue));
        return new EnvPrediction(fieldLines, "(sujeitas a confirmacao apos elevacao)");
    }

    /** Junta letras de unidade em uma lista legivel: "E:", "E: e K:" ou "E:, K: e M:". */
    private static String joinLetters(List<Character> letras) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < letras.size(); i++) {
            if (i > 0) {
                texto.append(i == letras.size() - 1 ? " e " : ", ");
            }
            texto.append(letras.get(i)).append(':');
        }
        return texto.toString();
    }

    /**
     * Formata um campo do plano no mesmo estilo das linhas fixas ("Nome/rotulo
     * ......: valor"): rotulo, espaco, pontos ate a coluna 18 e ": valor" -
     * sempre 20 colunas de prefixo, alinhando com o resto do bloco.
     */
    private static String formatPlanField(String label, String value) {
        StringBuilder linha = new StringBuilder(label);
        linha.append(' ');
        while (linha.length() < 18) {
            linha.append('.');
        }
        linha.append(": ").append(value);
        return linha.toString();
    }

    /** Imprime o titulo "=== Variaveis de ambiente a modificar ===", os campos e a ressalva (se houver). */
    private static void printEnvPrediction(EnvPrediction prediction) {
        System.out.println("=== Variaveis de ambiente a modificar ===");
        for (String linha : prediction.fieldLines()) {
            System.out.println(linha);
        }
        if (prediction.note() != null) {
            System.out.println(prediction.note());
        }
    }

    /**
     * Reporta o resultado da etapa final de {@code DEVDRIVE_HOME}/{@code
     * DEVDRIVE_ROOTS}. Nunca lanca excecao nem falha o comando: a operacao
     * principal no disco ja teve sucesso antes de chegar aqui - uma falha
     * nesta etapa e so um aviso. Em sucesso, o bloco vem precedido de linha
     * em branco e titulo verde, para separar claramente das mensagens da
     * operacao principal que vieram antes.
     */
    private static void reportEnvironmentApply(DevDriveEnvironment.ApplyResult result) {
        if (!result.success()) {
            System.err.println("Aviso: nao foi possivel atualizar " + DevDriveEnvironment.HOME_VARIABLE
                    + " automaticamente.");
            System.err.println("Aviso: nao foi possivel atualizar " + DevDriveEnvironment.ROOTS_VARIABLE
                    + " automaticamente.");
            printProcessOutput(result.processResult());
            return;
        }
        System.out.println();
        System.out.println(ANSI_BRIGHT_GREEN + "Variaveis de ambiente ajustadas" + ANSI_RESET);
        System.out.println(DevDriveEnvironment.HOME_VARIABLE + "="
                + (result.home() == null ? "(removida)" : result.home()));
        System.out.println(DevDriveEnvironment.ROOTS_VARIABLE + "="
                + (result.rootsValue().isEmpty() ? "(removida)" : result.rootsValue()));
    }

    private static void printProcessOutput(ProcessResult result) {
        if (!result.stdout().trim().isEmpty()) {
            System.out.print(result.stdout());
        }
        if (!result.stderr().trim().isEmpty()) {
            System.err.print(result.stderr());
        }
    }

    private static boolean confirm(String prompt) {
        System.out.print(prompt);
        Scanner scanner = new Scanner(System.in);
        String answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return answer.equalsIgnoreCase("s") || answer.equalsIgnoreCase("sim") || answer.equalsIgnoreCase("y");
    }

    private static void printHelp(CommandLineArgs.Command command) {
        if (command == CommandLineArgs.Command.RESIZE) {
            printResizeHelp();
        } else if (command == CommandLineArgs.Command.DELETE) {
            printDeleteHelp();
        } else {
            printGeneralHelp();
        }
    }

    private static void printGeneralHelp() {
        System.out.println(
                "InstalaDevDrive - cria, redimensiona e exclui um Dev Drive do Windows (unidade de desenvolvedor)\n"
                + "\n"
                + "Uso:\n"
                + "  java -jar InstalaDevDrive.jar [create] [opcoes]\n"
                + "  java -jar InstalaDevDrive.jar resize --letter LETRA --size TAMANHO [opcoes]\n"
                + "  java -jar InstalaDevDrive.jar delete --letter LETRA [opcoes]\n"
                + "\n"
                + "Sem verbo, assume 'create'.\n"
                + "\n"
                + "Opcoes de 'create':\n"
                + "  --name NOME      Nome do arquivo VHDX e rotulo da unidade (padrao: DevDrive)\n"
                + "  --size TAMANHO   Tamanho da unidade, ex.: 50GB, 100GB, 1TB (padrao: 50GB, minimo: 50GB)\n"
                + "  --letter LETRA   Letra de unidade a usar, ex.: E (padrao: primeira letra livre a partir de E:)\n"
                + "  --path DIRETORIO Diretorio onde o arquivo .vhdx sera criado (padrao: C:\\DevDrive)\n"
                + "\n"
                + "Opcoes de 'resize':\n"
                + "  --letter LETRA   Letra do Dev Drive a aumentar (obrigatorio)\n"
                + "  --size TAMANHO   Nova capacidade total, ex.: 100GB (obrigatorio; nao pode ser menor que a atual)\n"
                + "\n"
                + "Opcoes de 'delete':\n"
                + "  --letter LETRA   Letra do Dev Drive a excluir (obrigatorio). APAGA o arquivo VHDX e todos os dados.\n"
                + "\n"
                + "Opcoes comuns:\n"
                + "  --yes            Nao pedir confirmacao antes de alterar/excluir a unidade\n"
                + "  --dry-run        Mostra o que seria feito, sem executar nenhuma alteracao\n"
                + "  --verbose        Mostra (em outra cor) cada comando PowerShell / script DISKPART / fsutil executado\n"
                + "  --help           Mostra esta ajuda ('resize --help'/'delete --help' mostram os detalhes de cada um)\n"
                + "\n"
                + "Remontagem apos reiniciar: o disco e anexado de forma permanente via a\n"
                + "Windows Virtual Disk API nativa (nao pelo DISKPART), entao ele volta\n"
                + "sozinho a cada boot - sem tarefa agendada nem script gravado em disco.\n"
                + "\n"
                + "Requisitos:\n"
                + "  - Windows 11 com suporte a Dev Drive\n"
                + "  - Execucao como Administrador\n"
                + "  - DISKPART (padrao em qualquer Windows); nao requer o modulo Hyper-V\n"
        );
    }

    private static void printResizeHelp() {
        System.out.println(
                "InstalaDevDrive resize - aumenta um Dev Drive ja existente\n"
                + "\n"
                + "Uso:\n"
                + "  java -jar InstalaDevDrive.jar resize --letter LETRA --size TAMANHO [--yes] [--dry-run] [--verbose]\n"
                + "\n"
                + "  --letter LETRA   Letra do Dev Drive a aumentar (ex.: E). A unidade tem que estar montada,\n"
                + "                   ser um VHDX baseado em arquivo, usar ReFS e estar marcada como Dev Drive.\n"
                + "  --size TAMANHO   Nova capacidade total, ex.: 100GB, 1TB. Nao pode ser menor que a atual\n"
                + "                   (o ReFS nao encolhe).\n"
                + "\n"
                + "O que acontece:\n"
                + "  1. Descobre o arquivo VHDX por tras da letra (Get-Partition/Get-Disk, saida tipada).\n"
                + "  2. Se o novo tamanho e maior que a capacidade atual: desanexa a unidade (fica offline\n"
                + "     por alguns segundos - nada pode estar usando ela), expande o VHDX via ExpandVirtualDisk\n"
                + "     (API nativa) e reanexa de forma permanente.\n"
                + "  3. Estende a particao ReFS ate o maximo (Resize-Partition) - online, sem apagar dados.\n"
                + "  4. Confirma via Get-Disk/Get-Volume que o tamanho aumentou.\n"
                + "\n"
                + "A operacao e idempotente: se algo falhar depois de o VHDX ja ter sido expandido,\n"
                + "basta rodar o mesmo comando de novo para concluir.\n"
        );
    }

    private static void printDeleteHelp() {
        System.out.println(
                "InstalaDevDrive delete - exclui permanentemente um Dev Drive ja existente\n"
                + "\n"
                + "Uso:\n"
                + "  java -jar InstalaDevDrive.jar delete --letter LETRA [--yes] [--dry-run] [--verbose]\n"
                + "\n"
                + "  --letter LETRA   Letra do Dev Drive a excluir (ex.: E). A unidade tem que estar montada,\n"
                + "                   ser um VHDX baseado em arquivo, usar ReFS e estar marcada como Dev Drive.\n"
                + "\n"
                + "ATENCAO: esta operacao e IRREVERSIVEL. O arquivo VHDX e apagado do disco junto com\n"
                + "todos os dados da unidade. Sem --yes, e exigida confirmacao interativa antes de agir.\n"
                + "\n"
                + "O que acontece:\n"
                + "  1. Descobre o arquivo VHDX por tras da letra (Get-Partition/Get-Disk, saida tipada).\n"
                + "  2. Confirma que a unidade e mesmo um Dev Drive (fsutil devdrv query - exige Administrador).\n"
                + "  3. Desanexa o disco virtual (a letra de unidade deixa de existir).\n"
                + "  4. Apaga o arquivo VHDX do disco fisico.\n"
        );
    }
}
