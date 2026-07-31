package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Encontra/valida letras de unidade usando apenas APIs puras do Java
 * (java.io.File), sem necessidade de shell externo. Serve tambem como
 * verificacao de "chao de fabrica" (ground truth) para confirmar, apos a
 * execucao do DISKPART, se uma unidade realmente apareceu ou desapareceu do
 * sistema - em vez de confiar apenas no texto (localizavel) que o DISKPART
 * imprime no console.
 *
 * @author flavio mattos
 */
public final class DriveLetterFinder {

    private DriveLetterFinder() {
    }

    private static Set<Character> usedLetters() {
        Set<Character> used = new HashSet<>();
        for (File root : File.listRoots()) {
            String path = root.getPath();
            if (!path.isEmpty()) {
                used.add(Character.toUpperCase(path.charAt(0)));
            }
        }
        return used;
    }

    public static char findFreeLetter() {
        Set<Character> used = usedLetters();

        // Evita A, B (unidades de disquete historicas) e C (unidade do sistema)
        for (char letter = 'D'; letter <= 'Z'; letter++) {
            if (!used.contains(letter)) {
                return letter;
            }
        }

        throw new IllegalStateException("Nao ha letras de unidade livres disponiveis (D-Z).");
    }

    public static boolean isLetterFree(char letter) {
        return !usedLetters().contains(Character.toUpperCase(letter));
    }
}
