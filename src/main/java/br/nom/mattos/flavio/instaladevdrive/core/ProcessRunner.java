package br.nom.mattos.flavio.instaladevdrive.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executa processos externos (PowerShell, DISKPART) via ProcessBuilder,
 * capturando saida padrao, saida de erro e codigo de saida, com um tempo
 * limite de seguranca para nunca travar indefinidamente.
 *
 * @author flavio mattos
 */
final class ProcessRunner {

    private ProcessRunner() {
    }

    static ProcessResult execute(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process process = builder.start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Tempo limite excedido ao executar: " + String.join(" ", command));
            }
            return new ProcessResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao executar processo: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execucao interrompida: " + String.join(" ", command), e);
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }
}
