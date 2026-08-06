package br.nom.mattos.flavio.instaladevdrive;

import br.nom.mattos.flavio.instaladevdrive.cli.CommandLineArgs;
import br.nom.mattos.flavio.instaladevdrive.core.DevDriveCreator;
import br.nom.mattos.flavio.instaladevdrive.core.ProcessResult;
import br.nom.mattos.flavio.instaladevdrive.core.SizeParser;
import br.nom.mattos.flavio.instaladevdrive.core.VerboseLog;

import java.util.Scanner;

/**
 * Ponto de entrada do InstalaDevDrive: cria um Dev Drive do Windows (unidade
 * de desenvolvedor, com ganhos de desempenho via ReFS) a partir de um novo
 * disco virtual (VHDX). Usa o DISKPART apenas para criar o arquivo e depois
 * particionar/atribuir a letra (nao requer o modulo Hyper-V), sempre
 * selecionando o disco pelo caminho do arquivo - nunca por numero. A
 * anexacao do disco e feita pela Windows Virtual Disk API nativa (nao pelo
 * DISKPART), de forma permanente: sobrevive a reinicializacoes do Windows
 * sem depender de tarefa agendada nem de script gravado em disco. Valida a
 * letra de unidade antes e depois de cada etapa, com reversao automatica em
 * caso de falha. A formatacao final como Dev Drive usa o cmdlet estruturado
 * Format-Volume -DevDrive (modulo Storage, padrao do Windows).
 *
 * @author flavio mattos
 */
public class InstalaDevDrive {

    private static final String ANSI_BRIGHT_GREEN = "[92m";
    private static final String ANSI_RESET = "[0m";

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

        VerboseLog.setEnabled(cli.verbose());

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
            System.out.println(" 2. Criar o arquivo VHDX via DISKPART");
            System.out.println(" 3. Anexar o disco de forma permanente via Windows Virtual Disk API (sobrevive a reboots)");
            System.out.println(" 4. Criar particao e atribuir a letra " + plan.driveLetter() + ": via DISKPART");
            System.out.println(" 5. Formatar como Dev Drive (ReFS) via Format-Volume -DevDrive");
            return;
        }

        System.out.println("Verificando privilegios de Administrador...");
        creator.checkElevation();

        if (!cli.assumeYes() && !confirm()) {
            System.out.println("Operacao cancelada pelo usuario.");
            return;
        }

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

        System.out.println(ANSI_BRIGHT_GREEN + "Dev Drive criado com sucesso." + ANSI_RESET);
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
                + "  --name NOME      Nome do arquivo VHDX e rotulo da unidade (padrao: DevDrive)\n"
                + "  --size TAMANHO   Tamanho da unidade, ex.: 50GB, 100GB, 1TB (padrao: 50GB, minimo: 50GB)\n"
                + "  --letter LETRA   Letra de unidade a usar, ex.: E (padrao: primeira letra livre a partir de E:)\n"
                + "  --path DIRETORIO Diretorio onde o arquivo .vhdx sera criado (padrao: C:\\DevDrive)\n"
                + "  --yes            Nao pedir confirmacao antes de formatar\n"
                + "  --dry-run        Mostra o que seria feito, sem executar nenhuma alteracao\n"
                + "  --verbose        Mostra (em outra cor) cada comando PowerShell e script DISKPART executado\n"
                + "  --help           Mostra esta ajuda\n"
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
}

