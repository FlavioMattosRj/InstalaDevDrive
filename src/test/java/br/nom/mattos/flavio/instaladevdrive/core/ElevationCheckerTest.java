package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valida apenas o TEXTO do comando PowerShell usado para checar elevacao,
 * sem executar nenhum processo real (um erro de digitacao aqui faria a
 * checagem de Administrador falhar silenciosamente em producao).
 *
 * @author flavio mattos
 */
class ElevationCheckerTest {

    @Test
    void comandoReferenciaAsClassesEMembrosCorretos() {
        String command = ElevationChecker.CHECK_COMMAND;

        assertTrue(command.contains("Security.Principal.WindowsPrincipal"));
        assertTrue(command.contains("Security.Principal.WindowsIdentity"));
        assertTrue(command.contains("::GetCurrent()"));
        assertTrue(command.contains(".IsInRole("));
        assertTrue(command.contains("Security.Principal.WindowsBuiltInRole"));
        assertTrue(command.contains("::Administrator"));
    }

    @Test
    void parentesesEColchetesEstaoBalanceados() {
        String command = ElevationChecker.CHECK_COMMAND;

        assertEquals(countChar(command, '('), countChar(command, ')'), "Parenteses desbalanceados no comando de elevacao");
        assertEquals(countChar(command, '['), countChar(command, ']'), "Colchetes desbalanceados no comando de elevacao");
    }

    @Test
    void comandoNaoContemQuebrasDeLinha() {
        // E usado como uma unica expressao inline via -Command; uma quebra de
        // linha inesperada poderia mudar o comportamento do parser PowerShell.
        assertFalse(ElevationChecker.CHECK_COMMAND.contains("\n"));
        assertFalse(ElevationChecker.CHECK_COMMAND.contains("\r"));
    }

    private static long countChar(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
