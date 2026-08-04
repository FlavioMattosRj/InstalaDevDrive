package br.nom.mattos.flavio.virtdisk;

import com.sun.jna.platform.win32.Kernel32Util;

/** Erro de uma chamada a virtdisk.dll, com o codigo de erro Win32 original. */
public final class VirtualDiskException extends RuntimeException {

    private final int errorCode;

    VirtualDiskException(String operation, int errorCode) {
        super(operation + " falhou: " + errorCode + " (" + describe(errorCode) + ")");
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    private static String describe(int errorCode) {
        try {
            return Kernel32Util.formatMessage(errorCode).trim();
        } catch (RuntimeException e) {
            return "erro desconhecido";
        }
    }
}
