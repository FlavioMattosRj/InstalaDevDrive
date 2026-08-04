package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * Espelha ATTACH_VIRTUAL_DISK_PARAMETERS (virtdisk.h), braco Version1
 * (unico documentado).
 *
 * <p>Publica por exigencia do JNA (reflection cruzando pacotes -- ver
 * {@link VirtualStorageType}). Nao e para uso direto fora deste pacote.
 */
public class AttachVirtualDiskParameters extends Structure {

    private static final int VERSION_1 = 1;

    public int version = VERSION_1;
    public int reserved = 0;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("version", "reserved");
    }
}
