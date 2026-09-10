package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;

import java.io.File;

/**
 * Monta e desmonta um arquivo VHDX via virtdisk.dll (Windows Virtual Disk
 * API). Requer elevacao (admin).
 *
 * <p>Dois modos de montagem:
 * <ul>
 *   <li>{@link #mount()}: attach comum, preso ao ciclo de vida desta
 *       instancia -- fechar o processo sem chamar {@link #dismount()}
 *       desanexa o disco sozinho.</li>
 *   <li>{@link #mountPermanently()}: attach com
 *       ATTACH_VIRTUAL_DISK_FLAG_PERMANENT_LIFETIME|ATTACH_VIRTUAL_DISK_FLAG_AT_BOOT
 *       -- sobrevive ao fechamento do processo e a reboots do Windows
 *       (validado empiricamente). Para desfazer, chame
 *       {@link #dismount()}, nesta instancia ou numa nova apontando para
 *       o mesmo arquivo, mesmo em outra execucao do programa.</li>
 * </ul>
 *
 * <p>{@link #expandTo(long)} aumenta a capacidade do VHDX. A Windows
 * Virtual Disk API exige o disco DESANEXADO para expandir, entao o fluxo
 * tipico e {@link #dismount()} -> {@link #expandTo(long)} ->
 * {@link #mountPermanently()}.
 *
 * <p>Pacote autocontido: para reusar em outro projeto Maven/Gradle, basta
 * copiar esta pasta de pacote e declarar as dependencias
 * net.java.dev.jna:jna e net.java.dev.jna:jna-platform.
 */
public final class VhdxMount {

    private static final int DEVICE_ID_VHDX = 3;
    private static final String VENDOR_MICROSOFT = "{EC984AEC-A0F9-47E9-901F-71415A66345B}";

    private static final int ATTACH_FLAG_NONE = 0x00000000;
    private static final int ATTACH_FLAG_PERMANENT_LIFETIME = 0x00000004;
    private static final int ATTACH_FLAG_AT_BOOT = 0x00000400;
    private static final int ATTACH_FLAGS_PERMANENT_AT_BOOT = ATTACH_FLAG_PERMANENT_LIFETIME | ATTACH_FLAG_AT_BOOT;

    private static final int EXPAND_FLAG_NONE = 0x00000000;

    private final String vhdxPath;
    private HANDLE mountedHandle;

    public VhdxMount(String vhdxPath) {
        if (!new File(vhdxPath).isFile()) {
            throw new IllegalArgumentException("arquivo nao encontrado: " + vhdxPath);
        }
        this.vhdxPath = vhdxPath;
    }

    /** Anexa o VHDX sem persistencia. */
    public synchronized void mount() {
        if (mountedHandle != null) {
            throw new IllegalStateException("ja montado por esta instancia");
        }
        HANDLE handle = open();
        attach(handle, ATTACH_FLAG_NONE);
        mountedHandle = handle;
    }

    /** Anexa o VHDX de forma permanente: sobrevive ao processo e a reboots do Windows. */
    public synchronized void mountPermanently() {
        HANDLE handle = open();
        attach(handle, ATTACH_FLAGS_PERMANENT_AT_BOOT);
        // So chega aqui se attach() teve sucesso - em caso de falha, attach()
        // ja fecha o handle antes de lancar (ver abaixo). Fechar de novo aqui
        // seria um CloseHandle duplicado: a Microsoft documenta isso como
        // perigoso, podendo fechar um handle nao relacionado que teve o
        // mesmo valor reciclado nesse meio-tempo.
        // PERMANENT_LIFETIME desacopla o disco do handle -- fechar aqui nao desanexa.
        Kernel32.INSTANCE.CloseHandle(handle);
    }

    /**
     * Aumenta a capacidade do VHDX para {@code newSizeBytes} chamando
     * ExpandVirtualDisk (virtdisk.dll). Para um VHDX expansivel ("type=
     * expandable"), so mexe no tamanho logico -- o arquivo em disco cresce
     * de fato apenas conforme e usado.
     *
     * <p>Pre-condicao: o disco NAO pode estar anexado (nem por esta
     * instancia, nem permanentemente por outra execucao). A Windows Virtual
     * Disk API rejeita a expansao de um disco anexado em leitura/escrita.
     * Cabe ao chamador garantir isso, tipicamente chamando {@link #dismount()}
     * antes. Nao ha reducao: passar um valor menor que a capacidade atual
     * faz a API retornar erro.
     */
    public synchronized void expandTo(long newSizeBytes) {
        if (mountedHandle != null) {
            throw new IllegalStateException("nao e possivel expandir: ainda anexado por esta instancia (chame dismount())");
        }
        HANDLE handle = open();
        try {
            ExpandVirtualDiskParameters params = new ExpandVirtualDiskParameters();
            params.newSize = newSizeBytes;
            params.write();
            int rc = VirtDisk.INSTANCE.ExpandVirtualDisk(handle, EXPAND_FLAG_NONE, params, null);
            if (rc != WinError.ERROR_SUCCESS) {
                throw new VirtualDiskException("ExpandVirtualDisk", rc);
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /** Desanexa o VHDX -- funciona mesmo se o attach foi feito por outra instancia/execucao (ex.: mountPermanently). */
    public synchronized void dismount() {
        HANDLE handle = mountedHandle != null ? mountedHandle : open();
        try {
            int rc = VirtDisk.INSTANCE.DetachVirtualDisk(handle, 0, 0);
            if (rc != WinError.ERROR_SUCCESS) {
                throw new VirtualDiskException("DetachVirtualDisk", rc);
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
            mountedHandle = null;
        }
    }

    private HANDLE open() {
        VirtualStorageType storageType = new VirtualStorageType();
        storageType.deviceId = DEVICE_ID_VHDX;
        storageType.vendorId = new Guid.GUID(VENDOR_MICROSOFT);
        storageType.write();

        OpenVirtualDiskParameters openParams = new OpenVirtualDiskParameters();
        openParams.write();

        HANDLEByReference handleRef = new HANDLEByReference();
        int rc = VirtDisk.INSTANCE.OpenVirtualDisk(
                storageType, new WString(vhdxPath), 0, 0, openParams, handleRef);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new VirtualDiskException("OpenVirtualDisk", rc);
        }
        return handleRef.getValue();
    }

    private void attach(HANDLE handle, int flags) {
        AttachVirtualDiskParameters attachParams = new AttachVirtualDiskParameters();
        attachParams.write();
        int rc = VirtDisk.INSTANCE.AttachVirtualDisk(handle, null, flags, 0, attachParams, null);
        if (rc != WinError.ERROR_SUCCESS) {
            Kernel32.INSTANCE.CloseHandle(handle);
            throw new VirtualDiskException("AttachVirtualDisk", rc);
        }
    }
}
