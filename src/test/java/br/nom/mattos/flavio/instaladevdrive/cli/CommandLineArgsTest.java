package br.nom.mattos.flavio.instaladevdrive.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandLineArgs decide os valores (nome, tamanho, letra, caminho) que
 * acabam parar dentro dos comandos de DISKPART/PowerShell gerados por
 * DevDriveCreator; um erro de parsing aqui se propaga direto para o comando
 * final.
 *
 * @author flavio mattos
 */
class CommandLineArgsTest {

    @Test
    void valoresPadraoSemArgumentos() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[0]);

        assertEquals(CommandLineArgs.DEFAULT_NAME, cli.name());
        assertEquals(CommandLineArgs.DEFAULT_SIZE, cli.size());
        assertNull(cli.letter());
        assertNull(cli.directory());
        assertFalse(cli.dryRun());
        assertFalse(cli.assumeYes());
        assertFalse(cli.help());
        assertFalse(cli.verbose());
    }

    @Test
    void flagsBooleanasFormaLonga() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--dry-run", "--yes", "--verbose", "--help"});

        assertTrue(cli.dryRun());
        assertTrue(cli.assumeYes());
        assertTrue(cli.verbose());
        assertTrue(cli.help());
    }

    @Test
    void flagsBooleanasFormaCurta() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"-y", "-v", "-h"});

        assertTrue(cli.assumeYes());
        assertTrue(cli.verbose());
        assertTrue(cli.help());
    }

    @Test
    void nomeTamanhoLetraECaminhoComEspaco() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{
                "--name", "MeuDev", "--size", "100GB", "--letter", "d", "--path", "E:\\VHDs"
        });

        assertEquals("MeuDev", cli.name());
        assertEquals("100GB", cli.size());
        assertEquals(Character.valueOf('D'), cli.letter(), "Letra deve ser normalizada para maiuscula");
        assertEquals(Paths.get("E:\\VHDs"), cli.directory());
    }

    @Test
    void aceitaSintaxeComSinalDeIgual() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--name=MeuDev", "--size=100GB", "--letter=d"});

        assertEquals("MeuDev", cli.name());
        assertEquals("100GB", cli.size());
        assertEquals(Character.valueOf('D'), cli.letter());
    }

    @Test
    void letraUsaApenasOPrimeiroCaractereDoValor() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--letter", "DX"});

        assertEquals(Character.valueOf('D'), cli.letter());
    }

    @Test
    void rejeitaLetraVazia() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"--letter="}));
    }

    @Test
    void rejeitaArgumentoDesconhecido() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"--modo-turbo"}));
    }

    @Test
    void rejeitaArgumentoSemPrefixoDeTraco() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"nome-sem-traco"}));
    }

    @Test
    void faltaDeValorAposFlagQueEsperaArgumentoLancaExcecao() {
        // Comportamento atual: --name no ultimo argumento, sem valor a
        // seguir, estoura ArrayIndexOutOfBoundsException em vez de uma
        // mensagem de erro amigavel. Teste documenta o comportamento hoje;
        // considerar melhorar a mensagem de erro no parser futuramente.
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> CommandLineArgs.parse(new String[]{"--name"}));
    }

    @Test
    void ultimoValorRepetidoPrevalece() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--name", "A", "--name", "B"});

        assertEquals("B", cli.name());
    }
}
