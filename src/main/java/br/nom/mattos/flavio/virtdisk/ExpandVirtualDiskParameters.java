package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * Espelha EXPAND_VIRTUAL_DISK_PARAMETERS (virtdisk.h), braco Version1
 * (unico documentado): apenas o novo tamanho, em bytes.
 *
 * <p>{@code NewSize} e um ULONGLONG que, na struct C, cai no offset 8 (os 4
 * bytes de {@code Version} mais 4 de padding para alinhar o campo de 8
 * bytes). O JNA aplica o mesmo alinhamento padrao da plataforma, entao a
 * ordem de campos abaixo ja reproduz o layout correto.
 *
 * <p>Publica por exigencia do JNA (reflection cruzando pacotes -- ver
 * {@link VirtualStorageType}). Nao e para uso direto fora deste pacote.
 */
public class ExpandVirtualDiskParameters extends Structure {

    private static final int VERSION_1 = 1;

    public int version = VERSION_1;
    public long newSize;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("version", "newSize");
    }
}
