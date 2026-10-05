using System.Diagnostics;
using RemoteTaskManager;

namespace server.Services;

public class MetricsServerImpl : MetricsServerPOA
{
    private PerformanceCounter cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
    private PerformanceCounter ramCounter = new PerformanceCounter("Memory", "Available MBytes");
    private PerformanceCounter diskReadCounter = new PerformanceCounter("PhysicalDisk", "Disk Read Bytes/sec", "_Total");

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
        return diskReadCounter.NextValue();
    }

    public override string getName()
    {
        return Environment.MachineName;
    }
}