package rtm.server.tests;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import RemoteTaskManager.ProcessStatus;
import RemoteTaskManager.RemoteError;

public final class TestManager {

    private static final int MAX_PROCESSES = 10;
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final List<RemoteTest> tests;
    private final Map<String, RemoteTest> testsByName = new ConcurrentHashMap<>();
    private final Map<String, RemoteTest> testsById = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduledLaunches = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "scheduled-test-launcher");
        thread.setDaemon(true);
        return thread;
    });

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

    public RemoteTaskManager.Process[] GetProcesses() throws RemoteError {
        try {
            List<RemoteTaskManager.Process> result = ProcessHandle.allProcesses()
                    .filter(ProcessHandle::isAlive)
                    .sorted(Comparator.comparing(TestManager::cpuTime).reversed()
                            .thenComparingLong(ProcessHandle::pid))
                    .limit(MAX_PROCESSES)
                    .map(TestManager::toCorba)
                    .toList();
            return result.toArray(new RemoteTaskManager.Process[0]);
        } catch (RuntimeException e) {
            throw new RemoteError("Unable to enumerate OS processes: " + errorMessage(e));
        }
    }

    private static Duration cpuTime(ProcessHandle handle) {
        return handle.info().totalCpuDuration().orElse(Duration.ZERO);
    }

    public RemoteTaskManager.Process getProcess(RemoteTaskManager.Process processInfo) throws RemoteError {
        if (processInfo == null) {
            throw new RemoteError("Process information is required");
        }

        ProcessHandle handle = findOsProcess(processInfo);
        if (handle == null || !handle.isAlive()) {
            throw new RemoteError("No live OS process found for PID " + processId(processInfo));
        }
        return toCorba(handle);
    }

    public ProcessStatus Run(RemoteTaskManager.Process processInfo) throws RemoteError {
        if (processInfo == null) {
            throw new RemoteError("Process information is required");
        }

        RemoteTest test = findTest(processInfo.name);
        if (test == null) {
            test = findTest(processInfo.id);
        }
        if (test == null) {
            throw new RemoteError("Test '" + processInfo.name + "' is not available on this server");
        }

        try {
            launch(test);
            return ProcessStatus.RUNNING;
        } catch (Exception e) {
            throw new RemoteError("Failed to start test '" + test.name() + "': " + errorMessage(e));
        }
    }

    public ProcessStatus Schedule(RemoteTaskManager.Process processInfo, RemoteTaskManager.ScheduleTime scheduledAt)
            throws RemoteError {
        if (processInfo == null) {
            throw new RemoteError("Process information is required");
        }
        if (scheduledAt == null) {
            throw new RemoteError("A schedule date and time is required");
        }

        RemoteTest test = findTest(processInfo.name);
        if (test == null) {
            test = findTest(processInfo.id);
        }
        if (test == null) {
            throw new RemoteError("Test '" + processInfo.name + "' is not available on this server");
        }

        RemoteTest scheduledTest = test;
        long now = System.currentTimeMillis();
        long delayMillis = scheduledAt.epochMillis <= now ? 0 : scheduledAt.epochMillis - now;
        scheduledLaunches.schedule(() -> {
            try {
                launch(scheduledTest);
            } catch (Exception e) {
                System.err.println("Scheduled test '" + scheduledTest.name() + "' failed to start: " + errorMessage(e));
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        return ProcessStatus.SCHEDULED;
    }

    private static void launch(RemoteTest test) throws Exception {
        ProcessLaunch launch = test.createLaunch();
        ProcessBuilder builder = new ProcessBuilder(launch.command());
        if (launch.workingDirectory() != null && !launch.workingDirectory().isBlank()) {
            builder.directory(new java.io.File(launch.workingDirectory()));
        }
        builder.environment().putAll(launch.environment());
        builder.start();
    }

    public ProcessStatus Stop(RemoteTaskManager.Process processInfo) throws RemoteError {
        ProcessHandle handle;
        try {
            handle = findOsProcess(processInfo);
        } catch (RuntimeException e) {
            throw new RemoteError("Unable to look up the requested process: " + errorMessage(e));
        }
        if (handle == null) {
            throw new RemoteError("No live OS process found for PID " + processId(processInfo));
        }
        return stopOsProcess(handle);
    }

    private static ProcessStatus stopOsProcess(ProcessHandle handle) throws RemoteError {
        if (!handle.isAlive()) {
            return ProcessStatus.STOPPED;
        }

        try {
            if (!handle.destroy()) {
                if (handle.isAlive()) {
                    throw new RemoteError("OS refused the termination request for PID " + handle.pid());
                }
                return ProcessStatus.STOPPED;
            }
            handle.onExit().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ProcessStatus.STOPPED;
        } catch (java.util.concurrent.TimeoutException e) {
            return handle.isAlive() ? ProcessStatus.RUNNING : ProcessStatus.STOPPED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteError("Interrupted while waiting for PID " + handle.pid() + " to stop");
        } catch (RemoteError e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteError("Failed to stop PID " + handle.pid() + ": " + errorMessage(e));
        }
    }

    private static long processId(RemoteTaskManager.Process processInfo) {
        if (processInfo == null) {
            return 0;
        }
        if (processInfo.pid > 0) {
            return processInfo.pid;
        }
        try {
            return Long.parseLong(processInfo.id);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
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
        return pid <= 0 ? null : ProcessHandle.of(pid).orElse(null);
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
