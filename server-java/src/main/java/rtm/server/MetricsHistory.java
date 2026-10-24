package rtm.server;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import RemoteTaskManager.Metrics;

final class MetricsHistory {

    private static final long RETENTION_SECONDS = 24L * 60L * 60L;

    private final Supplier<Metrics> provider;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable
            -> {
        Thread thread = new Thread(runnable, "metrics-sampler");
        thread.setDaemon(true);
        return thread;
    });

    MetricsHistory(Supplier<Metrics> provider) {
        this.provider = provider;
        sampler.scheduleAtFixedRate(this::sample, 0, 2, TimeUnit.SECONDS);
    }

    synchronized Metrics GetAverage(int seconds) {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, seconds));
        List<Metrics> selected = new ArrayList<>();
        for (Sample sample : samples) {
            if (!sample.timestamp.isBefore(cutoff)) {
                selected.add(sample.metrics);
            }
        }

        if (selected.isEmpty()) {
            return provider.get();
        }

        double cpu = 0;
        double memoryUsage = 0;
        double memoryTotal = 0;
        double diskUsage = 0;
        double diskTotal = 0;
        for (Metrics metrics : selected) {
            cpu += metrics.cpuUsage;
            memoryUsage += metrics.memoryUsage;
            memoryTotal += metrics.memoryTotal;
            diskUsage += metrics.diskUsage;
            diskTotal += metrics.diskTotal;
        }

        int count = selected.size();
        return new Metrics(
                (float) (cpu / count),
                (float) (memoryUsage / count),
                (float) (memoryTotal / count),
                (float) (diskUsage / count),
                (float) (diskTotal / count));
    }

    private void sample() {
        try {
            Metrics metrics = provider.get();
            Instant now = Instant.now();
            Instant cutoff = now.minusSeconds(RETENTION_SECONDS);
            synchronized (this) {
                samples.addLast(new Sample(now, metrics));
                while (!samples.isEmpty() && samples.peekFirst().timestamp.isBefore(cutoff)) {
                    samples.removeFirst();
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to collect metrics sample: " + e.getMessage());
        }
    }

    private record Sample(Instant timestamp, Metrics metrics) {

    }
}
