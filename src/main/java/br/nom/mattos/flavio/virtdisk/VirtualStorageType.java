package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;

import java.util.Arrays;
import java.util.List;

/**
 * Espelha VIRTUAL_STORAGE_TYPE (virtdisk.h): DeviceId + GUID do vendor.
 *
 * <p>Publica (nao package-private) por exigencia do JNA: Structure acessa
 * campos via reflection cruzando pacotes, e isso falha com
 * IllegalAccessException se a classe nao for publica, mesmo com campos
 * publicos. Nao e para uso direto fora deste pacote -- so {@link VhdxMount}
 * e {@link VirtualDiskException} sao a API pretendida.
 */
public class VirtualStorageType extends Structure {

    public int deviceId;
    public Guid.GUID vendorId = new Guid.GUID();

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("deviceId", "vendorId");
    }
}
