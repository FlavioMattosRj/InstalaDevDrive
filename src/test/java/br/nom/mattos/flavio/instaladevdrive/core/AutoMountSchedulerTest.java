package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa apenas a geracao (montagem) do script de reanexacao do DISKPART e do
 * comando schtasks.exe usados para a remontagem automatica no boot, sem
 * executar nenhum processo real nem tocar no Agendador de Tarefas de fato.
 *
 * @author flavio mattos
 */
class AutoMountSchedulerTest {

    private static final Path VHD = Paths.get("C:\\DevDrive\\DevDrive.vhdx");

    @Test
    void nomeDaTarefaUsaPrefixoERotulo() {
        assertEquals("InstalaDevDrive - AutoMount - DevDrive", AutoMountScheduler.taskName("DevDrive"));
    }

    @Test
    void nomeDaTarefaSanitizaCaracteresReservadosDeNomeDeArquivo() {
        String taskName = AutoMountScheduler.taskName("Meu:Dev/Drive?\"Teste\"");

        assertEquals("InstalaDevDrive - AutoMount - Meu_Dev_Drive__Teste_", taskName);
        for (char reserved : new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'}) {
            assertTrue(taskName.indexOf(reserved) < 0, "Caractere reservado '" + reserved + "' vazou para o nome da tarefa");
        }
    }

    @Test
    void nomeDaTarefaComRotuloVazioUsaFallback() {
        assertEquals("InstalaDevDrive - AutoMount - DevDrive", AutoMountScheduler.taskName(""));
    }

    @Test
    void caminhoDoScriptFicaAoLadoDoVhdxComSufixoProprio() {
        Path expected = Paths.get("C:\\DevDrive\\DevDrive.vhdx.automount.diskpart.txt");

        assertEquals(expected, AutoMountScheduler.scriptPath(VHD));
    }

    @Test
    void scriptDeReanexacaoApenasSelecionaEAnexaNuncaCriaOuFormata() {
        String script = AutoMountScheduler.buildAttachScript(VHD);
        String path = VHD.toAbsolutePath().toString();

        assertEquals("select vdisk file=\"" + path + "\"" + System.lineSeparator()
                + "attach vdisk" + System.lineSeparator()
                + "exit" + System.lineSeparator(), script);

        assertTrue(!script.toLowerCase().contains("create"), "Script de reanexacao nao deve criar disco/particao");
        assertTrue(!script.toLowerCase().contains("format"), "Script de reanexacao nao deve formatar nada");
        assertTrue(!script.toLowerCase().contains("assign"), "Nao deve reatribuir letra; o Windows ja lembra a letra do volume");
    }

    @Test
    void scriptDeReanexacaoSelecionaSemprePorCaminhoDeArquivoNuncaPorNumero() {
        String script = AutoMountScheduler.buildAttachScript(VHD);

        assertTrue(script.contains("select vdisk file=\""));
        assertTrue(!script.matches("(?s).*select disk \\d+.*"));
    }

    @Test
    void argumentosDoSchtasksContemFlagsEsperadasNaOrdemCorreta() {
        Path script = Paths.get("C:\\DevDrive\\DevDrive.vhdx.automount.diskpart.txt");
        List<String> args = AutoMountScheduler.buildCreateTaskArgs("MinhaTarefa", script);

        assertEquals("schtasks.exe", args.get(0));
        assertEquals("/Create", args.get(1));
        assertEquals("/TN", args.get(2));
        assertEquals("MinhaTarefa", args.get(3));
        assertEquals("/TR", args.get(4));
        assertEquals("diskpart.exe /s \"" + script.toAbsolutePath() + "\"", args.get(5));
        assertEquals("/SC", args.get(6));
        assertEquals("ONSTART", args.get(7), "Deve disparar no boot, sem depender de nenhum usuario logado");
        assertEquals("/RU", args.get(8));
        assertEquals("SYSTEM", args.get(9), "SYSTEM nao exige nenhuma sessao/senha de usuario armazenada");
        assertEquals("/RL", args.get(10));
        assertEquals("HIGHEST", args.get(11));
        assertEquals("/F", args.get(12), "Deve sobrescrever uma tarefa de mesmo nome ja existente");
        assertEquals(13, args.size());
    }

    @Test
    void comandoDeExecucaoEmbutidoNoTrTemAspasBalanceadasMesmoComCaminhoComEspacos() {
        Path scriptComEspacos = Paths.get("C:\\Dev Drive Com Espacos\\DevDrive.vhdx.automount.diskpart.txt");
        List<String> args = AutoMountScheduler.buildCreateTaskArgs("MinhaTarefa", scriptComEspacos);

        String trValue = args.get(5);
        assertEquals(2, countOccurrences(trValue, '"'), "O caminho embutido no /TR deve estar entre exatamente um par de aspas");
        assertTrue(trValue.startsWith("diskpart.exe /s \""));
        assertTrue(trValue.endsWith("\""));
    }

    @Test
    void argumentosDeExclusaoDaTarefaReferenciamMesmoNome() {
        List<String> args = AutoMountScheduler.buildDeleteTaskArgs("MinhaTarefa");

        assertEquals(java.util.Arrays.asList("schtasks.exe", "/Delete", "/TN", "MinhaTarefa", "/F"), args);
    }

    private static int countOccurrences(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
