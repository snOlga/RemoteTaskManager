using System.Diagnostics;
using System.Runtime.InteropServices;
using RemoteTaskManager;

namespace server.Services;

public class MetricsServerImpl : MetricsServerPOA
{
    private const float MiB = 1024 * 1024;
    private const float GiB = 1024 * 1024 * 1024;

    private readonly PerformanceCounter cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
    private readonly string serverId;

    public MetricsServerImpl()
        : this(Environment.MachineName)
    {
    }

    public MetricsServerImpl(string serverId)
    {
        this.serverId = serverId;
        cpuCounter.NextValue();
        Thread.Sleep(TimeSpan.FromSeconds(1));
        cpuCounter.NextValue();
    }

    public override Metrics GetMetrics()
    {
        (ulong totalBytes, ulong availableBytes) = SystemMetrics.GetPhysicalMemory();
        return new Metrics
        {
            cpuUsage = cpuCounter.NextValue(),
            memoryUsage = (float)(totalBytes - availableBytes) / MiB,
            memoryTotal = (float)totalBytes / MiB,
            diskUsage = GetDiskUsageGiB(),
            diskTotal = GetTotalDiskGiB()
        };
    }

    public override bool GetHeartbeat()
    {
        return true;
    }

    public override ServerInfo GetServerInfo()
    {
        return new ServerInfo
        {
            name = serverId,
            OS = RuntimeInformation.OSDescription,
            cpuAmount = Environment.ProcessorCount,
            memoryTotal = (float)SystemMetrics.GetPhysicalMemory().totalBytes / MiB,
            diskTotal = GetTotalDiskGiB()
        };
    }

    public override string GetServerId()
    {
        return serverId;
    }

    private static float GetTotalDiskGiB()
    {
        return (float)DriveInfo.GetDrives()
            .Where(drive => drive.DriveType == DriveType.Fixed && drive.IsReady)
            .Sum(drive => (double)drive.TotalSize) / GiB;
    }

    private static float GetDiskUsageGiB()
    {
        return (float)DriveInfo.GetDrives()
            .Where(drive => drive.DriveType == DriveType.Fixed && drive.IsReady)
            .Sum(drive => (double)(drive.TotalSize - drive.TotalFreeSpace)) / GiB;
    }
}