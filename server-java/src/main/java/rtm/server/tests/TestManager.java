package rtm.server.tests;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import RemoteTaskManager.ProcessStatus;

public final class TestManager {

    private static final int MAX_PROCESSES = 10;
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final List<RemoteTest> tests;
    private final Map<String, RemoteTest> testsByName = new ConcurrentHashMap<>();
    private final Map<String, RemoteTest> testsById = new ConcurrentHashMap<>();

    public TestManager() {
        tests = TestCatalog.loadFromPlugins();
        for (RemoteTest test : tests) {
            testsByName.putIfAbsent(test.name().toLowerCase(), test);
            testsById.putIfAbsent(test.id().toLowerCase(), test);
        }

        System.out.println("Loaded test plugins: " + tests.size());
        for (RemoteTest test : tests) {
            System.out.println("  " + test.id() + " / " + test.name());
        }
    }

    public RemoteTaskManager.Process[] GetTests() {
        List<RemoteTaskManager.Process> result = new ArrayList<>();
        for (RemoteTest test : tests) {
            result.add(toCorba(test.id(), test.name()));
        }
        return result.toArray(new RemoteTaskManager.Process[0]);
    }

    public RemoteTaskManager.Process[] GetProcesses() {
        List<RemoteTaskManager.Process> result = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .sorted(Comparator.comparing(TestManager::cpuTime).reversed()
                        .thenComparingLong(ProcessHandle::pid))
                .limit(MAX_PROCESSES)
                .map(TestManager::toCorba)
                .toList();
        return result.toArray(new RemoteTaskManager.Process[0]);
    }

    private static Duration cpuTime(ProcessHandle handle) {
        return handle.info().totalCpuDuration().orElse(Duration.ZERO);
    }

    public ProcessStatus Run(RemoteTaskManager.Process processInfo) {
        RemoteTest test = findTest(processInfo.name);
        if (test == null) {
            test = findTest(processInfo.id);
        }
        if (test == null) {
            return ProcessStatus.FAILED;
        }

        try {
            ProcessLaunch launch = test.createLaunch();
            ProcessBuilder builder = new ProcessBuilder(launch.command());
            if (launch.workingDirectory() != null && !launch.workingDirectory().isBlank()) {
                builder.directory(new java.io.File(launch.workingDirectory()));
            }
            builder.environment().putAll(launch.environment());

            builder.start();
            return ProcessStatus.RUNNING;
        } catch (Exception e) {
            return ProcessStatus.FAILED;
        }
    }

    public ProcessStatus Stop(RemoteTaskManager.Process processInfo) {
        ProcessHandle handle = findOsProcess(processInfo);
        if (handle == null) {
            return ProcessStatus.UNKNOWN;
        }
        return stopOsProcess(handle);
    }

    private static ProcessStatus stopOsProcess(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return ProcessStatus.STOPPED;
        }

        try {
            if (!handle.destroy()) {
                return handle.isAlive() ? ProcessStatus.RUNNING : ProcessStatus.STOPPED;
            }
            handle.onExit().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ProcessStatus.STOPPED;
        } catch (java.util.concurrent.TimeoutException e) {
            return handle.isAlive() ? ProcessStatus.RUNNING : ProcessStatus.STOPPED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessStatus.UNKNOWN;
        } catch (Exception e) {
            return ProcessStatus.UNKNOWN;
        }
    }

    private static ProcessHandle findOsProcess(RemoteTaskManager.Process processInfo) {
        if (processInfo == null) {
            return null;
        }

        long pid = processInfo.pid;
        if (pid <= 0 && processInfo.id != null && !processInfo.id.isBlank()) {
            try {
                pid = Long.parseLong(processInfo.id.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return pid <= 0 ? null : ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
    }

    private RemoteTest findTest(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }

        String key = nameOrId.toLowerCase();
        RemoteTest test = testsByName.get(key);
        return test != null ? test : testsById.get(key);
    }

    private static RemoteTaskManager.Process toCorba(String id, String name) {
        RemoteTaskManager.Process process = new RemoteTaskManager.Process();
        process.id = id;
        process.name = name;
        process.pid = 0;
        process.status = ProcessStatus.UNKNOWN;
        return process;
    }

    private static RemoteTaskManager.Process toCorba(ProcessHandle handle) {
        long pid = handle.pid();
        String name = handle.info().command()
                .map(command -> new java.io.File(command).getName())
                .filter(value -> !value.isBlank())
                .orElse("pid-" + pid);

        RemoteTaskManager.Process process = new RemoteTaskManager.Process();
        process.id = Long.toString(pid);
        process.name = name;
        process.pid = (int) pid;
        process.status = ProcessStatus.RUNNING;
        return process;
    }

}
