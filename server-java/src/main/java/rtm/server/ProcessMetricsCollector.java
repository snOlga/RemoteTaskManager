package rtm.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import RemoteTaskManager.Metrics;
import RemoteTaskManager.RemoteError;

final class ProcessMetricsCollector {

    private static final float MiB = 1024f * 1024f;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private final Map<Long, MetricsHistory> histories = new ConcurrentHashMap<>();

    Metrics Get(long pid) throws RemoteError {
        Metrics metrics = read(pid);
        history(pid);
        return metrics;
    }

    Metrics GetAverage(long pid, int seconds) throws RemoteError {
        try {
            return history(pid).GetAverage(seconds);
        } catch (RuntimeException e) {
            throw new RemoteError("Unable to calculate process metrics for PID " + pid + ": " + message(e));
        }
    }

    private MetricsHistory history(long pid) {
        return histories.computeIfAbsent(pid, processId -> new MetricsHistory(() -> {
            try {
                return read(processId);
            } catch (RemoteError e) {
                throw new IllegalStateException(e.message);
            }
        }));
    }

    private Metrics read(long pid) throws RemoteError {
        ProcessHandle handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new RemoteError("No live OS process found for PID " + pid));
        ProcessStats stats = readProcessStats(pid);

        double elapsedSeconds = handle.info().startInstant()
                .map(start -> Math.max(0.001, Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0))
                .orElse(0.0);
        double cpuPercent = elapsedSeconds == 0
                ? stats.cpuPercent
                : stats.cpuPercentFromLifetime(elapsedSeconds);

        return new Metrics((float) cpuPercent,
                (float) stats.residentBytes / MiB,
                (float) stats.virtualBytes / MiB,
                0f,
                0f);
    }

    private ProcessStats readProcessStats(long pid) throws RemoteError {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder builder;
        if (windows) {
            String script = "$p = Get-Process -Id " + pid + " -ErrorAction Stop; "
                    + "$cpu = if ($null -eq $p.CPU) { 0 } else { $p.CPU }; "
                    + "[Console]::WriteLine($cpu.ToString([Globalization.CultureInfo]::InvariantCulture) "
                    + "+ '|' + $p.WorkingSet64 + '|' + $p.VirtualMemorySize64)";
            builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        } else {
            builder = new ProcessBuilder("ps", "-p", Long.toString(pid), "-o", "%cpu=", "-o", "rss=", "-o", "vsz=");
        }

        try {
            java.lang.Process command = builder.redirectErrorStream(true).start();
            if (!command.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                command.destroyForcibly();
                throw new RemoteError("Timed out reading OS metrics for PID " + pid);
            }

            String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (command.exitValue() != 0 || output.isEmpty()) {
                throw new RemoteError("Unable to read OS metrics for PID " + pid + ": " + output);
            }

            String[] values = output.split(windows ? "\\|" : "\\s+");
            if (values.length < 3) {
                throw new RemoteError("Unexpected OS metrics response for PID " + pid + ": " + output);
            }

            double cpu = parseDouble(values[0]);
            long residentBytes = parseLong(values[1]) * (windows ? 1L : 1024L);
            long virtualBytes = parseLong(values[2]) * (windows ? 1L : 1024L);
            return new ProcessStats(cpu, residentBytes, virtualBytes, windows);
        } catch (RemoteError e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteError("Unable to query OS metrics for PID " + pid + ": " + message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteError("Interrupted while reading OS metrics for PID " + pid);
        }
    }

    private static double parseDouble(String value) throws RemoteError {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new RemoteError("Invalid CPU metric returned by the OS: " + value);
        }
    }

    private static long parseLong(String value) throws RemoteError {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new RemoteError("Invalid memory metric returned by the OS: " + value);
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private record ProcessStats(double cpuPercent, long residentBytes, long virtualBytes, boolean cpuIsLifetimeSeconds) {

        private double cpuPercentFromLifetime(double elapsedSeconds) {
            return cpuIsLifetimeSeconds ? cpuPercent / elapsedSeconds * 100.0 : cpuPercent;
        }
    }
}
