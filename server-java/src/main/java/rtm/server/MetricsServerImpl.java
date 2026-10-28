package rtm.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import RemoteTaskManager.Duration;
import RemoteTaskManager.Metrics;
import RemoteTaskManager.MetricsServerPOA;
import RemoteTaskManager.Process;
import RemoteTaskManager.ProcessStatus;
import RemoteTaskManager.RemoteError;
import RemoteTaskManager.ServerInfo;
import RemoteTaskManager.StackTrace;
import rtm.server.tests.TestManager;

public final class MetricsServerImpl extends MetricsServerPOA {

    private static final float MiB = 1024f * 1024f;
    private static final float GiB = MiB * 1024f;

    private final com.sun.management.OperatingSystemMXBean osBean
            = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final String serverId;
    private final TestManager testManager;
    private final MetricsHistory metricsHistory;
    private final ProcessMetricsCollector processMetricsCollector = new ProcessMetricsCollector();

    public MetricsServerImpl() {
        this(defaultServerId());
    }

    public MetricsServerImpl(String serverId) {
        this.serverId = serverId;
        osBean.getSystemCpuLoad();
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        osBean.getSystemCpuLoad();

        testManager = new TestManager();
        metricsHistory = new MetricsHistory(this::GetMetrics);
    }

    private static String defaultServerId() {
        String name = System.getenv("COMPUTERNAME");
        if (name != null && !name.isEmpty()) {
            return name;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return System.getProperty("user.name", "unknown");
        }
    }

    @Override
    public synchronized Metrics GetMetrics() {
        long[] memory = getPhysicalMemory();
        return new Metrics(GetCpuUsage(),
                (float) (memory[0] - memory[1]) / MiB,
                (float) memory[0] / MiB,
                GetDiskUsageGiB(),
                GetTotalDiskGiB());
    }

    @Override
    public boolean GetHeartbeat() {
        return true;
    }

    @Override
    public ServerInfo GetServerInfo() {
        return new ServerInfo(serverId,
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                Runtime.getRuntime().availableProcessors(),
                (float) getPhysicalMemory()[0] / MiB,
                GetTotalDiskGiB());
    }

    @Override
    public String GetServerId() {
        return serverId;
    }

    @Override
    public RemoteTaskManager.Process[] GetTests() {
        return testManager.GetTests();
    }

    @Override
    public RemoteTaskManager.Process[] GetProcesses() throws RemoteTaskManager.RemoteError {
        return testManager.GetProcesses();
    }

    @Override
    public Process Get(Process processInfo) throws RemoteError {
        return testManager.getProcess(processInfo);
    }

    @Override
    public ProcessStatus Run(RemoteTaskManager.Process processInfo) throws RemoteError {
        return testManager.Run(processInfo);
    }

    @Override
    public ProcessStatus Stop(RemoteTaskManager.Process processInfo) throws RemoteError {
        return testManager.Stop(processInfo);
    }

    @Override
    public Metrics GetMetricsByProcess(Process processInfo) throws RemoteError {
        Process process = testManager.getProcess(processInfo);
        return processMetricsCollector.Get(process.pid);
    }

    @Override
    public Metrics GetAvarageMetricsByProcess(Process processInfo, Duration durationInfo) throws RemoteError {
        Process process = testManager.getProcess(processInfo);
        int seconds = durationInfo == null ? 60 : durationInfo.seconds;
        return processMetricsCollector.GetAverage(process.pid, seconds);
    }

    @Override
    public ProcessStatus Schedule(Process processInfo, RemoteTaskManager.ScheduleTime scheduledAt) throws RemoteError {
        return testManager.Schedule(processInfo, scheduledAt);
    }

    @Override
    public StackTrace GetStackTrace(Process processInfo) throws RemoteError {
        Process process = testManager.getProcess(processInfo);
        return new StackTrace(process.pid, collectStackTrace(process.pid));
    }

    private static String collectStackTrace(int pid) throws RemoteError {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String executable = windows ? "jcmd.exe" : "jcmd";
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", executable);
        String command = Files.isExecutable(jcmd) ? jcmd.toString() : "jcmd";
        List<String> arguments = List.of(command, Integer.toString(pid), "Thread.print", "-l");

        try {
            java.lang.Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RemoteError("Timed out collecting stack trace for PID " + pid);
            }

            String trace = new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || trace.isBlank()) {
                throw new RemoteError("Unable to collect stack trace for PID " + pid + ": " + trace);
            }
            return trace;
        } catch (RemoteError e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteError("Interrupted while collecting stack trace for PID " + pid);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new RemoteError("Unable to collect stack trace for PID " + pid + ": " + message);
        }
    }

    @Override
    public Metrics GetAverageMetrics(Duration durationInfo) {
        int seconds = durationInfo == null ? 0 : durationInfo.seconds;
        return metricsHistory.GetAverage(seconds);
    }

    private float GetCpuUsage() {
        double load = osBean.getSystemCpuLoad();
        return load < 0 ? 0f : (float) (load * 100.0);
    }

    private long[] getPhysicalMemory() {
        return new long[]{osBean.getTotalMemorySize(), osBean.getFreeMemorySize()};
    }

    private static float GetTotalDiskGiB() {
        long total = 0;
        for (FileStore store : getRootFileStores()) {
            try {
                total += store.getTotalSpace();
            } catch (IOException ignored) {
            }
        }
        return (float) total / GiB;
    }

    private static float GetDiskUsageGiB() {
        long used = 0;
        for (FileStore store : getRootFileStores()) {
            try {
                used += store.getTotalSpace() - store.getUnallocatedSpace();
            } catch (IOException ignored) {
            }
        }
        return (float) used / GiB;
    }

    private static Iterable<FileStore> getRootFileStores() {
        List<FileStore> stores = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                stores.add(Files.getFileStore(root));
            } catch (IOException ignored) {
            }
        }
        return stores;
    }
}
