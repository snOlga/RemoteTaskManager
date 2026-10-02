using RemoteTaskManager;
using server.IServices;

namespace server.Services;

public class MetricsServerImpl : MetricsServerPOA
{
    private readonly IMetricsService metricsService;

    public MetricsServerImpl(IMetricsService metricsService)
    {
        this.metricsService = metricsService;
    }

    public override float getCurrentCpuUsage()
    {
        return metricsService.getCurrentCpuUsage();
    }

    public override float getAvailableRAM()
    {
        return metricsService.getAvailableRAM();
    }
}