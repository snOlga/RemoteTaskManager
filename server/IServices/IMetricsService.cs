using System;

namespace server.IServices;

public interface IMetricsService
{
    public float getCurrentCpuUsage();
    public float getAvailableRAM();
}
