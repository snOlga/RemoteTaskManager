using DotNetOrb.Core;
using PortableServer;
using server.Services;

var orbProperties = new Dictionary<string, string>
{
    ["OAIAddr"] = "0.0.0.0",
    ["OAPort"] = "19019"
};

Console.WriteLine("Initializing orb...");
ORB orb = (ORB)ORB.Init(orbProperties); // Object Request Broker
var poa = POAHelper.Narrow(orb.ResolveInitialReferences("RootPOA")); // Portable Object Adapter
poa.ThePOAManager.Activate();

var servant = new MetricsServerImpl();
CORBA.IObject obj = poa.ServantToReference(servant);

var ior = orb.ObjectToString(obj);
File.WriteAllText("metricsServer.ior", ior);

Console.WriteLine("MetricsServer IOR: " + ior);
Console.WriteLine("IOR saved to metricsServer.ior");
Console.WriteLine("Press any key to stop server...");
Console.ReadLine();
poa.Destroy(true, true);
orb.Shutdown(false);
Console.WriteLine("Finished");