using DotNetOrb.Core;
using RemoteTaskManager;

string[] iorCandidates = args.Length > 0
    ? [args[0]]
    : ["metricsServer.ior", Path.Combine("..", "server", "metricsServer.ior")];

string? iorPath = iorCandidates.FirstOrDefault(File.Exists);

if (iorPath is null)
{
    Console.Error.WriteLine("Could not find metricsServer.ior. Looked in:");
    foreach (var candidate in iorCandidates)
    {
        Console.Error.WriteLine("  " + Path.GetFullPath(candidate));
    }

    Console.Error.WriteLine("Start the server first, or pass the IOR file path as an argument.");
    return 1;
}

Console.WriteLine($"Reading IOR from {Path.GetFullPath(iorPath)}");

ORB orb = (ORB)ORB.Init([]);
CORBA.Object obj = orb.StringToObject(File.ReadAllText(iorPath));
var server = MetricsServerHelper.Narrow(obj)
             ?? throw new InvalidOperationException("Object does not implement MetricsServer");

Console.WriteLine($"CPU usage:      {server.getCurrentCpuUsage():F2} %");
Console.WriteLine($"Available RAM:  {server.getAvailableRAM():F0} MB");
Console.WriteLine($"Total disk:     {server.getTotalDisk():F0} MB");

orb.Shutdown(false);
return 0;
