package br.nom.mattos.flavio.instaladevdrive;

import br.nom.mattos.flavio.instaladevdrive.cli.CommandLineArgs;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveCreator;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveResizer;
import br.nom.mattos.flavio.instaladevdrive.core.ProcessResult;
import br.nom.mattos.flavio.instaladevdrive.core.SizeParser;
import br.nom.mattos.flavio.instaladevdrive.core.VerboseLog;
import br.nom.mattos.flavio.instaladevdrive.core.VirtualDiskInfo;

import java.util.Scanner;

/**
 * Ponto de entrada do InstalaDevDrive. Dois subcomandos:
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
 * </ul>
 *
 * Ambos validam a letra de unidade antes e depois de cada etapa, por
 * evidencia real do sistema de arquivos, e nunca confiam no texto
 * (localizavel) impresso pelas ferramentas do Windows.
 *
 * @author flavio mattos
 */
public class InstalaDevDrive {

    private static final String ANSI_BRIGHT_GREEN = "[92m";
    private static final String ANSI_RESET = "[0m";

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

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            System.out.println(" 1. Validar pre-requisitos (Administrador, letra livre, arquivo inexistente)");
            System.out.println(" 2. Criar o arquivo VHDX via DISKPART");
            System.out.println(" 3. Anexar o disco de forma permanente via Windows Virtual Disk API (sobrevive a reboots)");
            System.out.println(" 4. Criar particao e atribuir a letra " + plan.driveLetter() + ": via DISKPART");
            System.out.println(" 5. Formatar como Dev Drive (ReFS) via Format-Volume -DevDrive");
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

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            if (growVhdx) {
                System.out.println(" 1. Desanexar " + plan.driveLetter() + ": (a unidade fica offline por alguns segundos)");
                System.out.println(" 2. Expandir o VHDX para " + SizeParser.toHumanReadable(plan.newSizeBytes())
                        + " via ExpandVirtualDisk (API nativa)");
                System.out.println(" 3. Reanexar " + plan.driveLetter() + ": de forma permanente (sobrevive a reboots)");
                System.out.println(" 4. Estender a particao ReFS para preencher o espaco novo (Resize-Partition)");
                System.out.println(" 5. Confirmar via Get-Disk/Get-Volume que o tamanho aumentou");
            } else {
                System.out.println(" 1. O VHDX ja tem " + SizeParser.toHumanReadable(info.diskSizeBytes())
                        + "; nao sera expandido nem desanexado.");
                System.out.println(" 2. Estender a particao ReFS (online) para preencher qualquer espaco nao alocado");
                System.out.println(" 3. Confirmar via Get-Volume que o tamanho nao regrediu");
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

        System.out.println(ANSI_BRIGHT_GREEN + "Unidade " + plan.driveLetter()
                + ": redimensionada com sucesso." + ANSI_RESET);
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
        } else {
            printGeneralHelp();
        }
    }

    private static void printGeneralHelp() {
        System.out.println(
                "InstalaDevDrive - cria e redimensiona um Dev Drive do Windows (unidade de desenvolvedor)\n"
                + "\n"
                + "Uso:\n"
                + "  java -jar InstalaDevDrive.jar [create] [opcoes]\n"
                + "  java -jar InstalaDevDrive.jar resize --letter LETRA --size TAMANHO [opcoes]\n"
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
                + "Opcoes comuns:\n"
                + "  --yes            Nao pedir confirmacao antes de alterar a unidade\n"
                + "  --dry-run        Mostra o que seria feito, sem executar nenhuma alteracao\n"
                + "  --verbose        Mostra (em outra cor) cada comando PowerShell / script DISKPART / fsutil executado\n"
                + "  --help           Mostra esta ajuda ('resize --help' mostra os detalhes do resize)\n"
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
}
