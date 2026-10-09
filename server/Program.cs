using DotNetOrb.Core;
using PortableServer;
using server.Services;

string port = "19020";
string id = "";
string addr = "0.0.0.0";
for (int i = 0; i < args.Length - 1; i++)
{
    if (args[i] == "--port")
    {
        port = args[i + 1];
    }
    else if (args[i] == "--id")
    {
        id = args[i + 1];
    }
    else if (args[i] == "--addr")
    {
        addr = args[i + 1];
    }
}

var orbProperties = new Dictionary<string, string>
{
    ["OAIAddr"] = addr,
    ["OAPort"] = port
};

Console.WriteLine("Initializing orb...");
ORB orb = (ORB)ORB.Init(orbProperties);
var poa = POAHelper.Narrow(orb.ResolveInitialReferences("RootPOA"));
poa.ThePOAManager.Activate();

var servant = id.Length == 0 ? new MetricsServerImpl() : new MetricsServerImpl(id);
CORBA.IObject obj = poa.ServantToReference(servant);

var ior = orb.ObjectToString(obj);
File.WriteAllText("metricsServer.ior", ior);

Console.WriteLine("MetricsServer IOR: " + ior);
Console.WriteLine("IOR saved to metricsServer.ior");
Console.WriteLine("Press any key to stop server...");
try
{
    Console.In.ReadLine();
}
catch
{
    Thread.Sleep(Timeout.Infinite);
}
poa.Destroy(true, true);
orb.Shutdown(false);
Console.WriteLine("Finished");