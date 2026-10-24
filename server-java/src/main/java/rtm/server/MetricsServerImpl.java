package rtm.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import RemoteTaskManager.Duration;
import RemoteTaskManager.Metrics;
import RemoteTaskManager.MetricsServerPOA;
import RemoteTaskManager.ProcessStatus;
import RemoteTaskManager.ServerInfo;
import rtm.server.tests.TestManager;

public final class MetricsServerImpl extends MetricsServerPOA {

    private static final float MiB = 1024f * 1024f;
    private static final float GiB = MiB * 1024f;

    private final com.sun.management.OperatingSystemMXBean osBean
            = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final String serverId;
    private final TestManager testManager;
    private final MetricsHistory metricsHistory;

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
    public RemoteTaskManager.Process[] GetProcesses() {
        return testManager.GetProcesses();
    }

    @Override
    public ProcessStatus Run(RemoteTaskManager.Process processInfo) {
        return testManager.Run(processInfo);
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
