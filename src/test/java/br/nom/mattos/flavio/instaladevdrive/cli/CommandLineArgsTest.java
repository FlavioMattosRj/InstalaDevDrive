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
    void rejeitaLetraComMaisDeUmCaractere() {
        // Antes, "--letter DX" truncava silenciosamente para 'D', ignorando
        // o resto do valor sem avisar o usuario. Agora e um erro de uso.
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"--letter", "DX"}));
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
    void faltaDeValorAposFlagQueEsperaArgumentoLancaErroDeUsoClaro() {
        // Antes, --name no ultimo argumento sem valor a seguir estourava
        // ArrayIndexOutOfBoundsException em vez de uma mensagem amigavel.
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(new String[]{"--name"}));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(new String[]{"--size"}));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(new String[]{"--letter"}));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(new String[]{"--path"}));
    }

    @Test
    void ultimoValorRepetidoPrevalece() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--name", "A", "--name", "B"});

        assertEquals("B", cli.name());
    }
}
