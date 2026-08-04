package br.nom.mattos.flavio.instaladevdrive.cli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Representa os argumentos de linha de comando ja interpretados, com os
 * valores padrao aplicados.
 *
 * @author flavio mattos
 */
public final class CommandLineArgs {

    public static final String DEFAULT_NAME = "DevDrive";
    public static final String DEFAULT_SIZE = "50GB";

    private String name = DEFAULT_NAME;
    private String size = DEFAULT_SIZE;
    private Character letter;
    private Path directory;
    private boolean dryRun;
    private boolean assumeYes;
    private boolean help;
    private boolean verbose;

    public static CommandLineArgs parse(String[] args) {
        CommandLineArgs result = new CommandLineArgs();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key;
            String inlineValue = null;

            if (arg.startsWith("--")) {
                key = arg.substring(2);
            } else if (arg.startsWith("-")) {
                key = arg.substring(1);
            } else {
                throw new IllegalArgumentException("Argumento nao reconhecido: " + arg);
            }

            int eq = key.indexOf('=');
            if (eq >= 0) {
                inlineValue = key.substring(eq + 1);
                key = key.substring(0, eq);
            }

            switch (key.toLowerCase()) {
                case "help":
                case "h":
                    result.help = true;
                    break;
                case "dry-run":
                    result.dryRun = true;
                    break;
                case "yes":
                case "y":
                    result.assumeYes = true;
                    break;
                case "verbose":
                case "v":
                    result.verbose = true;
                    break;
                case "name":
                    requireValuePresent(args, i, inlineValue, "name");
                    result.name = inlineValue != null ? inlineValue : args[++i];
                    break;
                case "size":
                    requireValuePresent(args, i, inlineValue, "size");
                    result.size = inlineValue != null ? inlineValue : args[++i];
                    break;
                case "letter": {
                    requireValuePresent(args, i, inlineValue, "letter");
                    String value = inlineValue != null ? inlineValue : args[++i];
                    if (value.length() != 1) {
                        throw new IllegalArgumentException(
                                "--letter deve ter exatamente um caractere: '" + value + "'.");
                    }
                    result.letter = Character.toUpperCase(value.charAt(0));
                    break;
                }
                case "path":
                    requireValuePresent(args, i, inlineValue, "path");
                    result.directory = Paths.get(inlineValue != null ? inlineValue : args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("Argumento nao reconhecido: --" + key);
            }
        }

        return result;
    }

    /**
     * Flags como --name esperam um valor logo em seguida (a nao ser que
     * venha embutido via --name=valor). Sem essa checagem, uma flag no
     * ultimo argumento sem valor estourava ArrayIndexOutOfBoundsException
     * em vez de um erro de uso claro.
     */
    private static void requireValuePresent(String[] args, int currentIndex, String inlineValue, String flagName) {
        if (inlineValue == null && currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Falta o valor de --" + flagName + ".");
        }
    }

    public String name() {
        return name;
    }

    public String size() {
        return size;
    }

    public Character letter() {
        return letter;
    }

    public Path directory() {
        return directory;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean assumeYes() {
        return assumeYes;
    }

    public boolean help() {
        return help;
    }

    public boolean verbose() {
        return verbose;
    }
}
