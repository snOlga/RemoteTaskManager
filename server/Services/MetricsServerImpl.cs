using System.Diagnostics;
using RemoteTaskManager;

namespace server.Services;

public class MetricsServerImpl : MetricsServerPOA
{
    private PerformanceCounter cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
    private PerformanceCounter ramCounter = new PerformanceCounter("Memory", "Available MBytes");

    public MetricsServerImpl()
    {
        cpuCounter.NextValue();
        Thread.Sleep(TimeSpan.FromSeconds(1));
        cpuCounter.NextValue();
    }

    public override float getCurrentCpuUsage()
    {
        return cpuCounter.NextValue();
    }

    public override float getAvailableRAM()
    {
        return ramCounter.NextValue();
    }

    public override float getTotalDisk()
    {
        double totalBytes = DriveInfo.GetDrives()
            .Where(drive => drive.DriveType == DriveType.Fixed && drive.IsReady)
            .Sum(drive => (double)drive.TotalSize);

        return (float)(totalBytes / (1024 * 1024 * 1024));
    }

    public override string getName()
    {
        return Environment.MachineName;
    }
}