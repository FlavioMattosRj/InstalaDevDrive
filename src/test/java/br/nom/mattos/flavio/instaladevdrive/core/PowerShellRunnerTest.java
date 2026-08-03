package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Testa apenas a geracao (montagem) da linha de comando do processo
 * powershell.exe, sem executar nenhum processo real - o que permitiria rodar
 * estes testes em qualquer maquina/CI, inclusive fora do Windows.
 *
 * @author flavio mattos
 */
class PowerShellRunnerTest {

    @Test
    void montaFlagsFixasNaOrdemEsperada() {
        List<String> args = PowerShellRunner.buildArgs("Write-Output 1");

        assertEquals(7, args.size(), "Numero de argumentos mudou; cheque se alguma flag foi perdida/duplicada");
        assertEquals("powershell.exe", args.get(0));
        assertEquals("-NoProfile", args.get(1));
        assertEquals("-NonInteractive", args.get(2));
        assertEquals("-ExecutionPolicy", args.get(3));
        assertEquals("Bypass", args.get(4));
        assertEquals("-Command", args.get(5));
        assertEquals("Write-Output 1", args.get(6));
    }

    @Test
    void comandoEhPassadoComoUmUnicoArgumentoMesmoComEspacos() {
        String command = "Get-ChildItem -Path 'C:\\Program Files'";
        List<String> args = PowerShellRunner.buildArgs(command);

        // Erro classico: se o comando fosse dividido por espaco em multiplos
        // argumentos (em vez de um unico elemento da lista), o ProcessBuilder
        // passaria parametros extras e invalidos para o powershell.exe.
        assertEquals(command, args.get(args.size() - 1));
        assertEquals(7, args.size());
    }

    @Test
    void comandoComAspasDuplasNaoEhAlterado() {
        String command = "Write-Output \"ola mundo\"";
        List<String> args = PowerShellRunner.buildArgs(command);

        assertSame(command, args.get(args.size() - 1), "buildArgs nao deve reescrever/escapar o comando recebido");
    }

    @Test
    void comandoMultilinhaPermaneceIntactoComoUmUnicoArgumento() {
        String command = "$ErrorActionPreference = 'Stop'" + System.lineSeparator()
                + "try {" + System.lineSeparator()
                + "    Write-Output 'FORMAT_OK'" + System.lineSeparator()
                + "}";
        List<String> args = PowerShellRunner.buildArgs(command);

        assertEquals(command, args.get(args.size() - 1));
        assertEquals(1, args.stream().filter(a -> a.contains("FORMAT_OK")).count(),
                "O script inteiro deve chegar como um unico argumento, nao fatiado");
    }

    @Test
    void comandoVazioNaoQuebraAMontagemDosArgumentos() {
        List<String> args = PowerShellRunner.buildArgs("");

        assertEquals(7, args.size());
        assertEquals("", args.get(6));
    }

    @Test
    void comandoComPontoEVirgulaEPipeContinuaLiteral() {
        String command = "Get-Process | Where-Object { $_.Id -eq 1 }; exit 0";
        List<String> args = PowerShellRunner.buildArgs(command);

        assertEquals(command, args.get(args.size() - 1));
    }
}
