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

    /**
     * Subcomando (verbo) da linha de comando. {@code CREATE} e o padrao
     * quando nenhum verbo e informado, para nao quebrar as invocacoes
     * historicas ({@code java -jar InstalaDevDrive.jar --name ...}).
     */
    public enum Command {
        CREATE,
        RESIZE,
        DELETE,
        MOUNT,
        DISMOUNT;

        static Command parse(String token) {
            switch (token.toLowerCase()) {
                case "create":
                    return CREATE;
                case "resize":
                    return RESIZE;
                case "delete":
                    return DELETE;
                case "mount":
                    return MOUNT;
                case "dismount":
                    return DISMOUNT;
                default:
                    throw new IllegalArgumentException(
                            "Comando desconhecido: '" + token + "'. Use 'create', 'resize', 'delete', "
                                    + "'mount' ou 'dismount'.");
            }
        }
    }

    private Command command = Command.CREATE;
    private String name = DEFAULT_NAME;
    private String size = DEFAULT_SIZE;
    private Character letter;
    private Path path;
    private boolean dryRun;
    private boolean assumeYes;
    private boolean help;
    private boolean verbose;
    private boolean nameProvided;
    private boolean sizeProvided;

    public static CommandLineArgs parse(String[] args) {
        CommandLineArgs result = new CommandLineArgs();

        int start = 0;
        if (args.length > 0 && !args[0].startsWith("-")) {
            result.command = Command.parse(args[0]);
            start = 1;
        }

        for (int i = start; i < args.length; i++) {
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
                    result.nameProvided = true;
                    break;
                case "size":
                    requireValuePresent(args, i, inlineValue, "size");
                    result.size = inlineValue != null ? inlineValue : args[++i];
                    result.sizeProvided = true;
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
                    result.path = Paths.get(inlineValue != null ? inlineValue : args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("Argumento nao reconhecido: --" + key);
            }
        }

        result.validateForCommand();
        return result;
    }

    /**
     * Regras especificas de cada subcomando, aplicadas depois do parsing.
     * {@code resize} e {@code delete} identificam a unidade alvo por
     * {@code --letter} (obrigatorio nos dois); {@code resize} tambem exige
     * {@code --size} (o novo tamanho total). {@code mount} identifica o
     * VHDX por {@code --path} (obrigatorio - aqui e o ARQUIVO a montar, nao
     * um diretorio como em {@code create}) e aceita {@code --letter} como
     * pedido opcional. {@code dismount} identifica a unidade por {@code
     * --letter} OU {@code --path} (exatamente um dos dois). {@code --name}
     * e {@code --size} sao insumos so de {@code create}/{@code resize} e
     * nao se aplicam aos demais.
     */
    private void validateForCommand() {
        if (help || command == Command.CREATE) {
            return;
        }
        if (nameProvided) {
            throw new IllegalArgumentException("--name nao se aplica ao comando '" + commandToken() + "'.");
        }
        if (sizeProvided && command != Command.RESIZE) {
            throw new IllegalArgumentException("--size nao se aplica ao comando '" + commandToken() + "'.");
        }

        switch (command) {
            case RESIZE:
                requireLetter("redimensionar");
                if (!sizeProvided) {
                    throw new IllegalArgumentException(
                            "O comando 'resize' exige --size (o novo tamanho total da unidade).");
                }
                requirePathAbsent();
                break;
            case DELETE:
                requireLetter("excluir");
                requirePathAbsent();
                break;
            case MOUNT:
                if (path == null) {
                    throw new IllegalArgumentException(
                            "O comando 'mount' exige --path (o arquivo VHDX a montar).");
                }
                break;
            case DISMOUNT:
                if (letter == null && path == null) {
                    throw new IllegalArgumentException(
                            "O comando 'dismount' exige --letter ou --path (a unidade a desmontar).");
                }
                if (letter != null && path != null) {
                    throw new IllegalArgumentException(
                            "O comando 'dismount' aceita --letter OU --path, nao os dois.");
                }
                break;
            default:
                break;
        }
    }

    private void requireLetter(String acao) {
        if (letter == null) {
            throw new IllegalArgumentException(
                    "O comando '" + commandToken() + "' exige --letter (a unidade a " + acao + ").");
        }
    }

    private void requirePathAbsent() {
        if (path != null) {
            throw new IllegalArgumentException("--path nao se aplica ao comando '" + commandToken() + "'.");
        }
    }

    private String commandToken() {
        switch (command) {
            case RESIZE:
                return "resize";
            case DELETE:
                return "delete";
            case MOUNT:
                return "mount";
            case DISMOUNT:
                return "dismount";
            default:
                return "create";
        }
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

    public Command command() {
        return command;
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

    public Path path() {
        return path;
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
