package nosql.benchmark;

import nosql.client.NoSqlSdk;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 兼容 Redis RESP 协议的分布式数据库并发性能压力测试工具 (Redis-Benchmark-Lite)
 */
public class BenchmarkTool {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;
        int threads = 10;
        int requestsPerThread = 5000;
        String mode = "mix"; // "write", "read", "mix"

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++i]);
                    break;
                case "--reqs":
                    requestsPerThread = Integer.parseInt(args[++i]);
                    break;
                case "--mode":
                    mode = args[++i];
                    break;
            }
        }

        int totalRequests = threads * requestsPerThread;
        System.out.println("=================================================");
        System.out.println(" Starting Redis-Compatible Database Benchmark");
        System.out.println(" Server Address    : " + host + ":" + port);
        System.out.println(" Concurrency       : " + threads + " Threads");
        System.out.println(" Requests/Thread   : " + requestsPerThread);
        System.out.println(" Total Operations  : " + totalRequests);
        System.out.println(" Testing Mode      : " + mode.toUpperCase());
        System.out.println("=================================================");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0); 

        String finalMode = mode;
        String finalHost = host;
        int finalPort = port;
        int finalReqs = requestsPerThread;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (NoSqlSdk sdk = new NoSqlSdk(finalHost, finalPort)) {
                    startLatch.await();

                    for (int i = 0; i < finalReqs; i++) {
                        String key = "bench:" + threadId + ":" + i;
                        long startNs = System.nanoTime();
                        try {
                            if ("write".equalsIgnoreCase(finalMode)) {
                                sdk.sendCommand(List.of("SET", key, "value_" + i));
                            } else if ("read".equalsIgnoreCase(finalMode)) {
                                sdk.sendCommand(List.of("GET", key));
                            } else {
                                // 混合模式：80% 读，20% 写
                                if (i % 5 == 0) {
                                    sdk.sendCommand(List.of("SET", key, "value_" + i));
                                } else {
                                    sdk.sendCommand(List.of("GET", key));
                                }
                            }
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        } finally {
                            totalLatency.addAndGet(System.nanoTime() - startNs);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Thread error: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTestTime = System.currentTimeMillis();
        startLatch.countDown(); 

        try {
            endLatch.await(); 
        } catch (InterruptedException ignored) {}

        long endTestTime = System.currentTimeMillis();
        long durationMs = endTestTime - startTestTime;
        double durationSec = durationMs / 1000.0;

        long successes = successCount.get();
        long failures = failureCount.get();
        double qps = successes / durationSec;
        double avgLatencyMs = (totalLatency.get() / (double) successes) / 1_000_000.0;

        System.out.println("================= Benchmark Results =================");
        System.out.printf("Time Taken for Tests : %.3f seconds%n", durationSec);
        System.out.println("Successful Operations: " + successes);
        System.out.println("Failed Operations    : " + failures);
        System.out.printf("Throughput (QPS)     : %.2f ops/sec%n", qps);
        System.out.printf("Average Latency      : %.3f ms%n", avgLatencyMs);
        System.out.println("=====================================================");

        executor.shutdown();
    }
}
