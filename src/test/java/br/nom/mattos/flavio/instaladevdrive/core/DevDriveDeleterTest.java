package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes das validacoes de {@link DevDriveDeleter} que nao dependem de um
 * Dev Drive real montado. {@code inspect}/{@code execute} de ponta a ponta
 * ficam de fora, indisponiveis em CI - mesma limitacao (e mesma abordagem)
 * de {@link DevDriveResizerTest}.
 *
 * @author flavio mattos
 */
class DevDriveDeleterTest {

    private final DevDriveDeleter deleter = new DevDriveDeleter();

    // ---------------------------------------------------------------
    // resolvePlan
    // ---------------------------------------------------------------

    @Test
    void resolvePlanNormalizaLetraParaMaiuscula() {
        DevDriveDeleter.Plan plan = deleter.resolvePlan('e');

        assertEquals('E', plan.driveLetter());
    }

    // ---------------------------------------------------------------
    // inspect (parte que nao depende de um volume real)
    // ---------------------------------------------------------------

    @Test
    void inspectRejeitaLetraLivre() {
        char livre = DriveLetterFinder.findFreeLetter();
        DevDriveDeleter.Plan plan = deleter.resolvePlan(livre);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> deleter.inspect(plan));
        assertTrue(ex.getMessage().contains("nao esta em uso"));
    }

    // ---------------------------------------------------------------
    // devDriveQueryArgs
    // ---------------------------------------------------------------

    @Test
    void argumentosDoFsutilConsultamADevDrivePelaLetra() {
        assertEquals(java.util.List.of("devdrv", "query", "E:"),
                java.util.List.of(DevDriveDeleter.devDriveQueryArgs('e')));
    }
}
