using System;
using System.Diagnostics;
using server.IServices;

namespace server.Services;

public class MetricsService : IMetricsService
{
    private PerformanceCounter cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
    private PerformanceCounter ramCounter = new PerformanceCounter("Memory", "Available MBytes");

    public float getCurrentCpuUsage()
    {
        return cpuCounter.NextValue();
    }

    public float getAvailableRAM()
    {
        return ramCounter.NextValue();
    }
}
