package rtm.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.omg.CORBA.ORB;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import org.omg.PortableServer.POAManager;

public final class Main {

    public static void main(String[] args) throws Exception {
        String port = "19021";
        String id = "";
        String addr = "0.0.0.0";

        Properties orbProperties = new Properties();
        orbProperties.put("OAPort", port);
        orbProperties.put("OAIAddr", addr);

        System.out.println("Initializing orb...");
        ORB orb = ORB.init(new String[0], orbProperties);
        POA rootPoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        POAManager poaManager = rootPoa.the_POAManager();
        poaManager.activate();

        MetricsServerImpl servant = id.isEmpty() ? new MetricsServerImpl() : new MetricsServerImpl(id);
        org.omg.CORBA.Object reference = rootPoa.servant_to_reference(servant);
        String ior = orb.object_to_string(reference);
        try {
            Files.writeString(Path.of("metricsServer.ior"), ior);
        } catch (IOException e) {
            System.err.println("Failed to write metricsServer.ior: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("MetricsServer IOR: " + ior);
        System.out.println("IOR saved to metricsServer.ior");
        System.out.println("Press any key to stop server...");
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            in.readLine();
        } catch (IOException e) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        rootPoa.destroy(true, true);
        orb.shutdown(false);
        System.out.println("Finished");
    }
}
