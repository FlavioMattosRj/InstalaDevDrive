package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Testes focados na geracao dos comandos (script do DISKPART e comando do
 * PowerShell de formatacao) e nas validacoes que os antecedem, tentando
 * cobrir o maior numero possivel de casos de erro: escaping incorreto,
 * tentativas de "injecao" via nome/rotulo, arredondamento de tamanho,
 * caminhos com espacos/aspas, letras invalidas, etc.
 *
 * @author flavio mattos
 */
class DevDriveCreatorTest {

    private final DevDriveCreator creator = new DevDriveCreator();

    // ---------------------------------------------------------------
    // buildFormatCommand (comando PowerShell de formatacao Dev Drive)
    // ---------------------------------------------------------------

    @Test
    void comandoDeFormatacaoContemEstruturaEsperada() {
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), 'D', 50L * 1024 * 1024 * 1024, "DevDrive");

        String command = creator.buildFormatCommand(plan);

        assertTrue(command.startsWith("$ErrorActionPreference = 'Stop'"), "Deve travar em qualquer erro nao tratado");
        assertTrue(command.contains("Format-Volume -DriveLetter 'D'"), "Letra de unidade ausente/incorreta");
        assertTrue(command.contains("-FileSystem ReFS"), "Sistema de arquivos deve ser ReFS");
        assertTrue(command.contains("-DevDrive"), "Flag -DevDrive ausente");
        assertTrue(command.contains("-NewFileSystemLabel 'DevDrive'"), "Rotulo ausente/incorreto");
        assertTrue(command.contains("-Confirm:$false"), "Sem -Confirm:$false o comando pode ficar preso esperando confirmacao interativa");
        assertTrue(command.contains("Write-Output '" + DevDriveCreator.FORMAT_SUCCESS_MARKER + "'"),
                "Marcador de sucesso e usado pelo chamador para confirmar a formatacao");
        assertTrue(command.contains("} catch {"), "Deve haver tratamento de erro");
        assertTrue(command.contains("exit 1"), "Caminho de erro deve sinalizar exit code != 0");
        assertEquals(1, countOccurrences(command, "Format-Volume"), "Deve haver exatamente um comando Format-Volume");
    }

    @Test
    void rotuloComAspaSimplesEhEscapadoCorretamente() {
        DevDriveCreator.Plan plan = plan("O'Brien");

        String command = creator.buildFormatCommand(plan);

        assertTrue(command.contains("-NewFileSystemLabel 'O''Brien'"),
                "Aspa simples deve virar aspa dupla ('') dentro da string PowerShell entre aspas simples");
        assertEquals("O'Brien", unescapeLabel(extractLabelLiteral(command)),
                "O rotulo original deve ser recuperavel apos desfazer o escaping (round-trip)");
    }

    @Test
    void rotuloComMultiplasAspasSimplesConsecutivas() {
        DevDriveCreator.Plan plan = plan("''''");

        String command = creator.buildFormatCommand(plan);

        assertEquals("''''", unescapeLabel(extractLabelLiteral(command)));
    }

    @Test
    void tentativaDeInjecaoNoRotuloNaoQuebraForaDaStringEntreAspas() {
        // Tentativa classica de "escapar" da string literal PowerShell.
        String malicious = "x'; Remove-Item -Recurse -Force C:\\ ; Write-Output '";
        DevDriveCreator.Plan plan = plan(malicious);

        String command = creator.buildFormatCommand(plan);

        // Deve permanecer como UM UNICO comando Format-Volume; nada deve ter
        // "escapado" para virar uma instrucao PowerShell adicional.
        assertEquals(1, countOccurrences(command, "Format-Volume"));
        assertEquals(1, countOccurrences(command, "-Confirm:$false"));
        assertEquals(1, countOccurrences(command, "} catch {"));
        assertEquals(1, countOccurrences(command, "exit 1"));
        assertEquals(malicious, unescapeLabel(extractLabelLiteral(command)),
                "O texto malicioso deve permanecer literal (nao interpretado) dentro da string entre aspas simples");
    }

    @Test
    void rotuloComCaracteresEspeciaisDoPowerShellPermaneceLiteral() {
        // Backtick, cifrao e aspas duplas nao tem significado especial dentro
        // de uma string PowerShell entre ASPAS SIMPLES - nao devem ser
        // escapados nem alterados.
        String label = "Test`$(Get-Date)\"x\"";
        DevDriveCreator.Plan plan = plan(label);

        String command = creator.buildFormatCommand(plan);

        assertEquals(label, extractLabelLiteral(command), "Nao ha aspas simples no rotulo; nada deveria ser alterado");
    }

    @Test
    void rotuloVazioGeraStringVazia() {
        DevDriveCreator.Plan plan = plan("");

        String command = creator.buildFormatCommand(plan);

        assertTrue(command.contains("-NewFileSystemLabel ''"));
    }

    @Test
    void letraDeUnidadeNaoEhAlteradaPeloMontadorDeComando() {
        // buildFormatCommand nao normaliza a letra; quem garante maiuscula e
        // o parser de CLI. Fixa esse contrato para nao ser quebrado sem querer.
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), 'd', 50L * 1024 * 1024 * 1024, "DevDrive");

        String command = creator.buildFormatCommand(plan);

        assertTrue(command.contains("Format-Volume -DriveLetter 'd'"));
    }

    // ---------------------------------------------------------------
    // buildCreateScript (script do DISKPART que so cria o arquivo VHDX)
    // ---------------------------------------------------------------

    @Test
    void scriptDeCriacaoContemApenasCreateVdiskEExit() {
        Path vhd = Paths.get("C:\\DevDrive\\DevDrive.vhdx");
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(vhd, 'D', 50L * 1024 * 1024 * 1024, "DevDrive");

        String script = creator.buildCreateScript(plan);
        String[] lines = script.split(System.lineSeparator());

        String path = vhd.toAbsolutePath().toString();
        assertEquals("create vdisk file=\"" + path + "\" maximum=51200 type=expandable", lines[0]);
        assertEquals("exit", lines[1]);
        assertEquals(2, lines.length, "Script de criacao nao deve anexar, particionar nem atribuir letra - isso e feito depois, separadamente");
    }

    @Test
    void scriptDeCriacaoNaoAnexaNemParticionaNemAtribuiLetra() {
        // A anexacao agora e feita pela API nativa (VhdxMount.mountPermanently()),
        // nao mais pelo DISKPART - documenta esse contrato para nao regredir
        // sem querer para o mecanismo antigo (attach vdisk + tarefa agendada).
        DevDriveCreator.Plan plan = plan("DevDrive");

        String script = creator.buildCreateScript(plan);

        assertTrue(!script.contains("attach vdisk"));
        assertTrue(!script.contains("create partition"));
        assertTrue(!script.contains("assign letter"));
    }

    @Test
    void tamanhoEmMegabytesEhArredondadoParaCima() {
        long umByteAcimaDe50Gb = 50L * 1024 * 1024 * 1024 + 1;
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), 'D', umByteAcimaDe50Gb, "DevDrive");

        String script = creator.buildCreateScript(plan);

        assertTrue(script.contains("maximum=51201 "), "1 byte acima de 50GB deve arredondar para 51201 MB (ceil), nao truncar para 51200");
    }

    @Test
    void discoDeCriacaoNuncaESelecionadoPorNumero() {
        DevDriveCreator.Plan plan = plan("DevDrive");

        String script = creator.buildCreateScript(plan);

        assertTrue(!script.matches("(?s).*select disk \\d+.*"));
    }

    @Test
    void caminhoComEspacosPermaneceEntreAspasNoScriptDeCriacao() {
        Path vhd = Paths.get("C:\\Dev Drive Com Espacos\\DevDrive.vhdx");
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(vhd, 'D', 50L * 1024 * 1024 * 1024, "DevDrive");

        String script = creator.buildCreateScript(plan);

        String path = vhd.toAbsolutePath().toString();
        assertTrue(script.contains("\"" + path + "\""), "Caminho com espacos deve permanecer totalmente entre aspas para o DISKPART nao dividi-lo");
    }

    // ---------------------------------------------------------------
    // buildPartitionScript (script do DISKPART que particiona um disco
    // ja anexado via VhdxMount e atribui a letra de unidade)
    // ---------------------------------------------------------------

    @Test
    void scriptDeParticaoContemComandosNaOrdemCorreta() {
        Path vhd = Paths.get("C:\\DevDrive\\DevDrive.vhdx");
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(vhd, 'D', 50L * 1024 * 1024 * 1024, "DevDrive");

        String script = creator.buildPartitionScript(plan);
        String[] lines = script.split(System.lineSeparator());

        String path = vhd.toAbsolutePath().toString();
        assertEquals("select vdisk file=\"" + path + "\"", lines[0]);
        assertEquals("create partition primary", lines[1]);
        assertEquals("assign letter=D", lines[2]);
        assertEquals("exit", lines[3]);
        assertEquals(4, lines.length);
    }

    @Test
    void scriptDeParticaoNaoRecriaOArquivoVhdx() {
        DevDriveCreator.Plan plan = plan("DevDrive");

        String script = creator.buildPartitionScript(plan);

        assertTrue(!script.contains("create vdisk"), "Script de particao opera sobre um disco ja criado/anexado; nao deve recriar o arquivo");
    }

    @Test
    void discoDeParticaoESempreSelecionadoPorCaminhoDeArquivoNuncaPorNumero() {
        DevDriveCreator.Plan plan = plan("DevDrive");

        String script = creator.buildPartitionScript(plan);

        assertTrue(script.contains("select vdisk file=\""), "Selecao deve ser sempre por arquivo, nunca por 'select disk N'");
        assertTrue(!script.matches("(?s).*select disk \\d+.*"));
    }

    @Test
    void caminhoComEspacosPermaneceEntreAspasNoScriptDeParticao() {
        Path vhd = Paths.get("C:\\Dev Drive Com Espacos\\DevDrive.vhdx");
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(vhd, 'D', 50L * 1024 * 1024 * 1024, "DevDrive");

        String script = creator.buildPartitionScript(plan);

        String path = vhd.toAbsolutePath().toString();
        assertTrue(script.contains("\"" + path + "\""), "Caminho com espacos deve permanecer totalmente entre aspas para o DISKPART nao dividi-lo");
    }

    // ---------------------------------------------------------------
    // resolvePlan
    // ---------------------------------------------------------------

    @Test
    void resolvePlanUsaDiretorioPadraoQuandoNenhumInformado() {
        DevDriveCreator.Plan plan = creator.resolvePlan("DevDrive", "50GB", 'D', null);

        assertEquals(Paths.get("C:\\DevDrive", "DevDrive.vhdx"), plan.vhdPath());
    }

    @Test
    void resolvePlanRespeitaDiretorioInformado() {
        Path custom = Paths.get("E:\\Custom");
        DevDriveCreator.Plan plan = creator.resolvePlan("Foo", "50GB", 'D', custom);

        assertEquals(custom.resolve("Foo.vhdx"), plan.vhdPath());
    }

    @Test
    void resolvePlanRejeitaTamanhoAbaixoDoMinimo() {
        assertThrows(IllegalArgumentException.class, () -> creator.resolvePlan("DevDrive", "49GB", 'D', null));
    }

    @Test
    void resolvePlanRejeitaTextoDeTamanhoInvalido() {
        assertThrows(IllegalArgumentException.class, () -> creator.resolvePlan("DevDrive", "abc", 'D', null));
    }

    @Test
    void resolvePlanEncontraLetraLivreQuandoNaoInformada() {
        DevDriveCreator.Plan plan = creator.resolvePlan("DevDrive", "50GB", null, null);

        assertTrue(plan.driveLetter() >= 'D' && plan.driveLetter() <= 'Z');
        assertTrue(DriveLetterFinder.isLetterFree(plan.driveLetter()));
    }

    // ---------------------------------------------------------------
    // validatePlan
    // ---------------------------------------------------------------

    @Test
    void validatePlanRejeitaLetraForaDoIntervaloAZ() {
        DevDriveCreator.Plan plan = plan('5', "DevDrive");

        assertThrows(IllegalArgumentException.class, () -> creator.validatePlan(plan));
    }

    @Test
    void validatePlanRejeitaLetrasDeDisqueteAeB() {
        assertThrows(IllegalArgumentException.class, () -> creator.validatePlan(plan('A', "DevDrive")));
        assertThrows(IllegalArgumentException.class, () -> creator.validatePlan(plan('B', "DevDrive")));
    }

    @Test
    void validatePlanRejeitaLetraDaUnidadeDeSistema() {
        String systemDrive = System.getenv("SystemDrive");
        assumeTrue(systemDrive != null && !systemDrive.isEmpty(), "SystemDrive nao definido neste ambiente");

        char letter = Character.toUpperCase(systemDrive.charAt(0));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> creator.validatePlan(plan(letter, "DevDrive")));
        assertTrue(ex.getMessage().contains("unidade do sistema operacional"));
    }

    @Test
    void validatePlanRejeitaLetraJaEmUso() {
        char inUse = findLetraEmUsoQueNaoSejaSistemaOuDisquete();
        assumeTrue(inUse != '\0', "Nao ha uma segunda unidade montada para testar este caso neste ambiente");

        assertThrows(IllegalStateException.class, () -> creator.validatePlan(plan(inUse, "DevDrive")));
    }

    @Test
    void validatePlanRejeitaArquivoVhdxJaExistente(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("DevDrive.vhdx");
        Files.createFile(existing);

        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(existing, DriveLetterFinder.findFreeLetter(), 50L * 1024 * 1024 * 1024, "DevDrive");

        assertThrows(IllegalStateException.class, () -> creator.validatePlan(plan));
    }

    @Test
    void nomeComAspaEhRejeitadoAntesDeChegarAoScript() {
        // No Windows, java.nio.file.Path ja rejeita aspas no proprio
        // construtor do caminho (InvalidPathException, que estende
        // IllegalArgumentException) - a checagem extra em validatePlan() e
        // uma segunda camada de defesa que nunca chega a ser exercitada
        // nesta plataforma. Paths.get(...) com aspas nao pode nem ser
        // construido diretamente em um teste; passamos pelo fluxo real
        // (resolvePlan) para comprovar que a aspa e barrada de um jeito ou de outro.
        assertThrows(IllegalArgumentException.class,
                () -> creator.resolvePlan("Dev\"Drive", "50GB", DriveLetterFinder.findFreeLetter(), null));
    }

    @Test
    void validatePlanAceitaPlanoValido(@TempDir Path tempDir) {
        Path vhd = tempDir.resolve("NaoExiste.vhdx");
        DevDriveCreator.Plan plan = new DevDriveCreator.Plan(vhd, DriveLetterFinder.findFreeLetter(), 50L * 1024 * 1024 * 1024, "DevDrive");

        creator.validatePlan(plan);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private DevDriveCreator.Plan plan(String label) {
        return new DevDriveCreator.Plan(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), 'D', 50L * 1024 * 1024 * 1024, label);
    }

    private DevDriveCreator.Plan plan(char letter, String label) {
        return new DevDriveCreator.Plan(Paths.get("C:\\DevDrive\\DevDrive.vhdx"), letter, 50L * 1024 * 1024 * 1024, label);
    }

    private static char findLetraEmUsoQueNaoSejaSistemaOuDisquete() {
        String systemDrive = System.getenv("SystemDrive");
        Character systemLetter = (systemDrive != null && !systemDrive.isEmpty())
                ? Character.toUpperCase(systemDrive.charAt(0))
                : null;

        for (java.io.File root : java.io.File.listRoots()) {
            char letter = Character.toUpperCase(root.getPath().charAt(0));
            if (letter != 'A' && letter != 'B' && !Character.valueOf(letter).equals(systemLetter)) {
                return letter;
            }
        }
        return '\0';
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final Pattern LABEL_PATTERN = Pattern.compile("-NewFileSystemLabel '(.*)' -Confirm:\\$false");

    private static String extractLabelLiteral(String command) {
        Matcher matcher = LABEL_PATTERN.matcher(command);
        assertTrue(matcher.find(), "Nao foi possivel localizar o segmento -NewFileSystemLabel no comando gerado");
        return matcher.group(1);
    }

    private static String unescapeLabel(String literal) {
        return literal.replace("''", "'");
    }
}
