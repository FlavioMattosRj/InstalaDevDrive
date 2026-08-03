package br.nom.mattos.flavio.instaladevdrive;

import br.nom.mattos.flavio.instaladevdrive.cli.CommandLineArgs;
import br.nom.mattos.flavio.instaladevdrive.core.AutoMountScheduler;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveCreator;
import br.nom.mattos.flavio.instaladevdrive.core.ElevationChecker;
import br.nom.mattos.flavio.instaladevdrive.core.PowerShellRunner;
import br.nom.mattos.flavio.instaladevdrive.core.ProcessResult;
import br.nom.mattos.flavio.instaladevdrive.core.SizeParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Ponto de entrada do InstalaDevDrive: cria um Dev Drive do Windows (unidade
 * de desenvolvedor, com ganhos de desempenho via ReFS) a partir de um novo
 * disco virtual (VHDX). Usa o DISKPART apenas para criar/anexar o disco
 * virtual e a particao (nao requer o modulo Hyper-V), sempre selecionando o
 * disco pelo caminho do arquivo - nunca por numero - e valida a letra de
 * unidade antes e depois de cada etapa, com reversao automatica em caso de
 * falha. A formatacao final como Dev Drive usa o cmdlet estruturado
 * Format-Volume -DevDrive (modulo Storage, padrao do Windows).
 *
 * @author flavio mattos
 */
public class InstalaDevDrive {

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
            printHelp();
            return;
        }

        PowerShellRunner.setVerbose(cli.verbose());

        if (cli.registerAutoMount()) {
            registerAutoMountForExisting(cli);
            return;
        }

        DevDriveCreator creator = new DevDriveCreator();
        DevDriveCreator.Plan plan = creator.resolvePlan(cli.name(), cli.size(), cli.letter(), cli.directory());
        creator.validatePlan(plan);

        System.out.println("=== InstalaDevDrive ===");
        System.out.println("Nome/rotulo ......: " + plan.label());
        System.out.println("Arquivo VHDX .....: " + plan.vhdPath());
        System.out.println("Letra de unidade .: " + plan.driveLetter() + ":");
        System.out.println("Tamanho ..........: " + SizeParser.toHumanReadable(plan.sizeBytes()));
        System.out.println();

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Nenhuma alteracao sera feita. As etapas seriam:");
            System.out.println(" 1. Validar pre-requisitos (Administrador, letra livre, arquivo inexistente)");
            System.out.println(" 2. Criar e anexar o disco virtual VHDX via DISKPART");
            System.out.println(" 3. Criar particao e atribuir a letra " + plan.driveLetter() + ": via DISKPART");
            System.out.println(" 4. Formatar como Dev Drive (ReFS) via Format-Volume -DevDrive");
            System.out.println(" 5. Registrar tarefa agendada para remontar o VHDX automaticamente a cada boot");
            return;
        }

        if (!cli.assumeYes() && !confirm()) {
            System.out.println("Operacao cancelada pelo usuario.");
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        creator.checkElevation();

        System.out.println("Criando o Dev Drive, aguarde...");
        ProcessResult result = creator.execute(plan);

        if (!result.stdout().trim().isEmpty()) {
            System.out.print(result.stdout());
        }
        if (!result.stderr().trim().isEmpty()) {
            System.err.print(result.stderr());
        }

        if (!result.success()) {
            throw new IllegalStateException("A criacao do Dev Drive falhou (codigo " + result.exitCode() + ").");
        }

        System.out.println("Dev Drive criado com sucesso, incluindo remontagem automatica no boot.");
    }

    /**
     * Registra (ou atualiza) apenas a tarefa agendada de remontagem
     * automatica para um Dev Drive ja existente, sem tocar em
     * disco/particao/formatacao - util para aplicar essa protecao a um Dev
     * Drive criado antes desse recurso existir, sem colocar os dados dele em
     * risco.
     */
    private static void registerAutoMountForExisting(CommandLineArgs cli) {
        Path directory = cli.directory() != null ? cli.directory() : Paths.get("C:\\DevDrive");
        Path vhdPath = directory.resolve(cli.name() + ".vhdx");

        System.out.println("=== InstalaDevDrive (--register-auto-mount) ===");
        System.out.println("Arquivo VHDX .....: " + vhdPath);

        if (!Files.exists(vhdPath)) {
            throw new IllegalStateException(
                    "Arquivo VHDX nao encontrado em " + vhdPath
                            + ". Informe --name e/ou --path apontando para o Dev Drive ja existente.");
        }

        if (cli.dryRun()) {
            System.out.println("(--dry-run) Registraria a tarefa agendada '" + AutoMountScheduler.taskName(cli.name())
                    + "' para reanexar este VHDX a cada boot.");
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        if (!ElevationChecker.isElevated()) {
            throw new IllegalStateException(
                    "E necessario executar como Administrador para registrar a tarefa agendada.");
        }

        System.out.println("Registrando remontagem automatica no boot...");
        ProcessResult result = AutoMountScheduler.register(cli.name(), vhdPath);

        if (!result.success()) {
            throw new IllegalStateException(
                    "Falha ao registrar a tarefa agendada (codigo " + result.exitCode() + ").\n"
                            + result.stdout() + result.stderr());
        }

        System.out.println("Pronto: a unidade sera remontada automaticamente a cada inicializacao do Windows.");
    }

    private static boolean confirm() {
        System.out.print("Esta operacao ira criar e FORMATAR uma nova unidade. Continuar? [s/N]: ");
        Scanner scanner = new Scanner(System.in);
        String answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return answer.equalsIgnoreCase("s") || answer.equalsIgnoreCase("sim") || answer.equalsIgnoreCase("y");
    }

    private static void printHelp() {
        System.out.println(
                "InstalaDevDrive - cria um Dev Drive do Windows (unidade de desenvolvedor)\n"
                + "\n"
                + "Uso:\n"
                + "  java -jar InstalaDevDrive.jar [opcoes]\n"
                + "\n"
                + "Opcoes:\n"
                + "  --name NOME           Nome do arquivo VHDX e rotulo da unidade (padrao: DevDrive)\n"
                + "  --size TAMANHO        Tamanho da unidade, ex.: 50GB, 100GB, 1TB (padrao: 50GB, minimo: 50GB)\n"
                + "  --letter LETRA        Letra de unidade a usar, ex.: D (padrao: primeira letra livre)\n"
                + "  --path DIRETORIO      Diretorio onde o arquivo .vhdx sera criado (padrao: C:\\DevDrive)\n"
                + "  --yes                 Nao pedir confirmacao antes de formatar\n"
                + "  --dry-run             Mostra o que seria feito, sem executar nenhuma alteracao\n"
                + "  --verbose             Mostra (em outra cor) cada comando PowerShell executado\n"
                + "  --register-auto-mount Registra so a remontagem automatica no boot de um Dev\n"
                + "                        Drive ja existente (usa --name/--path para localiza-lo);\n"
                + "                        nao mexe em disco/particao/formatacao\n"
                + "  --help                Mostra esta ajuda\n"
                + "\n"
                + "Remontagem automatica:\n"
                + "  Um VHDX anexado via DISKPART nao volta sozinho apos reiniciar o Windows.\n"
                + "  Por isso, ao criar um Dev Drive, este programa tambem registra uma tarefa\n"
                + "  agendada (Agendador de Tarefas, rodando como SYSTEM) que reanexa o VHDX a\n"
                + "  cada boot. Use --register-auto-mount para aplicar isso em um Dev Drive\n"
                + "  criado antes desse recurso existir.\n"
                + "\n"
                + "Requisitos:\n"
                + "  - Windows 11 com suporte a Dev Drive\n"
                + "  - Execucao como Administrador\n"
                + "  - DISKPART (padrao em qualquer Windows); nao requer o modulo Hyper-V\n"
        );
    }
}

