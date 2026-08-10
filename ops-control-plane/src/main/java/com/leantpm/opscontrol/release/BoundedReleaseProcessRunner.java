package com.leantpm.opscontrol.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BoundedReleaseProcessRunner implements ReleaseProcessRunner {

    @Override
    public ReleaseProcessResult execute(
        List<String> command,
        Duration timeout,
        int maximumOutputBytes
    ) {
        if (command == null || command.isEmpty()) {
            throw new ReleaseWorkflowException("Verifier command is missing");
        }
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to start the pinned package verifier", exception);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdout = executor.submit(
                () -> capture(process.getInputStream(), maximumOutputBytes)
            );
            Future<CapturedOutput> stderr = executor.submit(
                () -> capture(process.getErrorStream(), maximumOutputBytes)
            );
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
            }
            CapturedOutput capturedOut = result(stdout);
            CapturedOutput capturedErr = result(stderr);
            return new ReleaseProcessResult(
                finished ? process.exitValue() : -1,
                capturedOut.text(),
                capturedErr.text(),
                !finished,
                capturedOut.exceeded() || capturedErr.exceeded()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminateTree(process);
            throw new ReleaseWorkflowException("Package verification was interrupted", exception);
        }
    }

    private static CapturedOutput capture(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        boolean exceeded = false;
        int count;
        while ((count = input.read(buffer)) != -1) {
            int remaining = maximumBytes - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(remaining, count));
            }
            if (count > remaining) {
                exceeded = true;
            }
        }
        return new CapturedOutput(output.toString(StandardCharsets.UTF_8), exceeded);
    }

    private static CapturedOutput result(Future<CapturedOutput> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReleaseWorkflowException("Verifier output collection was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new ReleaseWorkflowException("Verifier output could not be collected", exception);
        }
    }

    private static void terminateTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // The parent is still terminated below and the caller receives a timeout failure.
            }
        });
        process.destroyForcibly();
    }

    private record CapturedOutput(String text, boolean exceeded) {
    }
}
