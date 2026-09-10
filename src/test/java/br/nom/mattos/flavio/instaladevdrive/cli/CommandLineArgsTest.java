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

        assertEquals(CommandLineArgs.Command.CREATE, cli.command());
        assertEquals(CommandLineArgs.DEFAULT_NAME, cli.name());
        assertEquals(CommandLineArgs.DEFAULT_SIZE, cli.size());
        assertNull(cli.letter());
        assertNull(cli.directory());
        assertFalse(cli.dryRun());
        assertFalse(cli.assumeYes());
        assertFalse(cli.help());
        assertFalse(cli.verbose());
    }

    // ---------------------------------------------------------------
    // subcomando (verbo)
    // ---------------------------------------------------------------

    @Test
    void semVerboAssumeCreate() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"--size", "60GB"});

        assertEquals(CommandLineArgs.Command.CREATE, cli.command());
        assertEquals("60GB", cli.size());
    }

    @Test
    void verboCreateExplicito() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"create", "--name", "Foo"});

        assertEquals(CommandLineArgs.Command.CREATE, cli.command());
        assertEquals("Foo", cli.name());
    }

    @Test
    void verboResizeExigeLetraETamanho() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"resize", "--letter", "e", "--size", "100GB"});

        assertEquals(CommandLineArgs.Command.RESIZE, cli.command());
        assertEquals(Character.valueOf('E'), cli.letter());
        assertEquals("100GB", cli.size());
    }

    @Test
    void verboEhCaseInsensitive() {
        assertEquals(CommandLineArgs.Command.RESIZE,
                CommandLineArgs.parse(new String[]{"RESIZE", "--letter", "E", "--size", "100GB"}).command());
    }

    @Test
    void verboDesconhecidoEhRejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"destroy", "--letter", "E"}));
    }

    @Test
    void resizeSemLetterEhRejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"resize", "--size", "100GB"}));
    }

    @Test
    void resizeSemSizeEhRejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineArgs.parse(new String[]{"resize", "--letter", "E"}));
    }

    @Test
    void resizeComNameEhRejeitado() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(
                new String[]{"resize", "--letter", "E", "--size", "100GB", "--name", "Foo"}));
    }

    @Test
    void resizeComPathEhRejeitado() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse(
                new String[]{"resize", "--letter", "E", "--size", "100GB", "--path", "C:\\X"}));
    }

    @Test
    void resizeComHelpNaoExigeLetraNemTamanho() {
        CommandLineArgs cli = CommandLineArgs.parse(new String[]{"resize", "--help"});

        assertEquals(CommandLineArgs.Command.RESIZE, cli.command());
        assertTrue(cli.help());
    }

    @Test
    void resizeAceitaFlagsComuns() {
        CommandLineArgs cli = CommandLineArgs.parse(
                new String[]{"resize", "--letter", "E", "--size", "100GB", "--yes", "--dry-run", "--verbose"});

        assertTrue(cli.assumeYes());
        assertTrue(cli.dryRun());
        assertTrue(cli.verbose());
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
