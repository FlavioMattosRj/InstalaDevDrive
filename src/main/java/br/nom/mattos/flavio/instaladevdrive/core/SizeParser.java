package br.nom.mattos.flavio.instaladevdrive.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte textos como "50GB", "1.5 TB" ou "512MB" em uma quantidade de
 * bytes, e valida o tamanho minimo exigido pelo recurso Dev Drive do
 * Windows (50 GB).
 *
 * @author flavio mattos
 */
public final class SizeParser {

    public static final long MINIMUM_BYTES = 50L * 1024 * 1024 * 1024; // 50 GB

    private static final Pattern PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:[.,]\\d+)?)\\s*(KB|MB|GB|TB)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private SizeParser() {
    }

    public static long parseToBytes(String text) {
        Matcher matcher = PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Tamanho invalido: '" + text + "'. Use um formato como 50GB, 100GB ou 1TB.");
        }

        double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
        String unit = matcher.group(2) == null ? "GB" : matcher.group(2).toUpperCase();

        long multiplier;
        switch (unit) {
            case "KB":
                multiplier = 1024L;
                break;
            case "MB":
                multiplier = 1024L * 1024;
                break;
            case "GB":
                multiplier = 1024L * 1024 * 1024;
                break;
            case "TB":
                multiplier = 1024L * 1024 * 1024 * 1024;
                break;
            default:
                throw new IllegalArgumentException("Unidade desconhecida: " + unit);
        }

        return Math.round(value * multiplier);
    }

    public static void validateMinimum(long bytes) {
        if (bytes < MINIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "O tamanho minimo exigido pelo Windows para um Dev Drive e 50 GB.");
        }
    }

    public static String toHumanReadable(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return String.format("%.2f GB", gb);
    }
}
