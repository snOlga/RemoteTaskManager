using DotNetOrb.Core;
using PortableServer;
using server.IServices;
using server.Services;

var orbProperties = new Dictionary<string, string>
{
    ["OAIAddr"] = "127.0.0.1",
    ["OAPort"] = "18018"
};

Console.WriteLine("Initializing orb...");
ORB orb = (ORB)ORB.Init(orbProperties);
var poa = POAHelper.Narrow(orb.ResolveInitialReferences("RootPOA"));
poa.ThePOAManager.Activate();

var servant = new MetricsServerImpl(new MetricsService());
CORBA.IObject obj = poa.ServantToReference(servant);

var ior = orb.ObjectToString(obj);
File.WriteAllText("metricsServer.ior", ior);

Console.WriteLine("MetricsServer IOR: " + ior);
Console.WriteLine("IOR saved to metricsServer.ior");
Console.WriteLine("Press any key to stop server...");
Console.ReadLine();
orb.Shutdown(false);