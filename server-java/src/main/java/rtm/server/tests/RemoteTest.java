package rtm.server.tests;

public interface RemoteTest
{
    String id();
    String name();
    ProcessLaunch createLaunch();
}
