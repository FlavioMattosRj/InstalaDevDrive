package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Binding JNA para virtdisk.dll -- so as funcoes que VhdxMount usa. Todos
 * os metodos retornam o codigo de erro Win32 diretamente como DWORD (0 =
 * ERROR_SUCCESS), nao HRESULT nem BOOL+GetLastError.
 *
 * <p>Publica por exigencia do JNA (mesmo motivo de {@link VirtualStorageType}).
 * Nao e para uso direto fora deste pacote.
 */
public interface VirtDisk extends StdCallLibrary {

    VirtDisk INSTANCE = Native.load("virtdisk", VirtDisk.class, W32APIOptions.DEFAULT_OPTIONS);

    int OpenVirtualDisk(
            VirtualStorageType virtualStorageType,
            WString path,
            int virtualDiskAccessMask,
            int flags,
            OpenVirtualDiskParameters parameters,
            HANDLEByReference handle);

    int AttachVirtualDisk(
            HANDLE virtualDiskHandle,
            Pointer securityDescriptor,
            int flags,
            int providerSpecificFlags,
            AttachVirtualDiskParameters parameters,
            Pointer overlapped);

    int DetachVirtualDisk(
            HANDLE virtualDiskHandle,
            int flags,
            int providerSpecificFlags);
}
