package br.nom.mattos.flavio.instaladevdrive.core;

import java.nio.file.Path;

/**
 * Descobre, a partir do caminho de um arquivo VHDX, se ele esta atualmente
 * anexado sob alguma letra de unidade. Nao introduz nenhuma consulta CIM
 * nova: reaproveita {@link VirtualDiskInfo#query(char)} (a mesma fonte de
 * dados usada por {@link DevDriveResizer}/{@link DevDriveDeleter}) para cada
 * letra atualmente em uso ({@link DriveLetterFinder#usedLetters()}),
 * comparando o caminho do VHDX por tras dela.
 *
 * <p>Usado por {@link DevDriveMounter} (para detectar que um VHDX ja esta
 * montado antes de tentar anexa-lo de novo - o
 * {@code AttachVirtualDisk} nativo nao e idempotente) e por
 * {@link DevDriveDismounter} (para resolver {@code --path} para a letra que
 * o comando precisa desanexar).
 *
 * @author flavio mattos
 */
final class VhdxLocator {

    private VhdxLocator() {
    }

    /**
     * Letra atualmente montada que e lastreada pelo VHDX em {@code
     * vhdxPath}, ou {@code null} se nenhuma letra em uso corresponde a ele.
     * Um {@code null} nao distingue "desanexado" de "anexado sem letra
     * atribuida" - so diz que nenhuma letra visivel esta associada a este
     * arquivo agora.
     */
    static Character findMountedLetter(Path vhdxPath) {
        Path normalized = vhdxPath.toAbsolutePath().normalize();
        for (char letter : DriveLetterFinder.usedLetters()) {
            try {
                VirtualDiskInfo info = VirtualDiskInfo.query(letter);
                if (info.isFileBackedVirtual()
                        && info.vhdxPath().toAbsolutePath().normalize().equals(normalized)) {
                    return letter;
                }
            } catch (RuntimeException ignored) {
                // Letra sem particao consultavel via CIM (disco fisico, unidade de rede etc.) - nao e candidata.
            }
        }
        return null;
    }
}
