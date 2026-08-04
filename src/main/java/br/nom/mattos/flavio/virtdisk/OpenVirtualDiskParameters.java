package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef;

import java.util.Arrays;
import java.util.List;

/**
 * Espelha o braco Version2 da union OPEN_VIRTUAL_DISK_PARAMETERS
 * (virtdisk.h). Com essa versao, OpenVirtualDisk exige
 * VIRTUAL_DISK_ACCESS_NONE como access mask (validado empiricamente).
 *
 * <p>Publica por exigencia do JNA (reflection cruzando pacotes -- ver
 * {@link VirtualStorageType}). Nao e para uso direto fora deste pacote.
 */
public class OpenVirtualDiskParameters extends Structure {

    private static final int VERSION_2 = 2;

    public int version = VERSION_2;
    public WinDef.BOOL getInfoOnly = new WinDef.BOOL(false);
    public WinDef.BOOL readOnly = new WinDef.BOOL(false);
    public Guid.GUID resiliencyGuid = new Guid.GUID();

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("version", "getInfoOnly", "readOnly", "resiliencyGuid");
    }
}
