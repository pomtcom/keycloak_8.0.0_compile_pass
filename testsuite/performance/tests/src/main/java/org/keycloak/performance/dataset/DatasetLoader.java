package org.keycloak.performance.dataset;

import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.commons.lang.Validate;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.performance.TestConfig;
import org.keycloak.performance.templates.DatasetTemplate;
import org.keycloak.performance.util.Loggable;

/**
 *
 * @author tkyjovsk
 */
public class DatasetLoader implements Loggable {

    private static final boolean DELETE = Boolean.parseBoolean(System.getProperty("delete", "false"));
    private static final int LOG_EVERY = Integer.parseInt(System.getProperty("log.every", "5"));
    private static final int QUEUE_TIMEOUT = Integer.parseInt(System.getProperty("queue.timeout", "60"));
    private static final int THREADPOOL_SHUTDOWN_TIMEOUT = Integer.parseInt(System.getProperty("shutdown.timeout", "60"));

    public static void main(String[] args) {
        DatasetTemplate template = new DatasetTemplate();
        template.validateConfiguration();
        DatasetLoader loader = new DatasetLoader(template.produce(), DELETE);
        loader.processDataset();
    }

    private final Dataset dataset;
    private final boolean delete;

    private final BlockingQueue<Keycloak> adminClients = new LinkedBlockingQueue<>();
    private Throwable error = null;

    Map<String, Integer> counter = new LinkedHashMap<>();
    long startTime;
    long nextLoggingTime;

    public DatasetLoader(Dataset dataset, boolean delete) {
        Validate.notNull(dataset);
        this.dataset = dataset;
        this.delete = delete;
        for (int i = 0; i < TestConfig.numOfWorkers; i++) {
            adminClients.add(Keycloak.getInstance(
                    TestConfig.serverUrisIterator.next(),
                    TestConfig.authRealm,
                    TestConfig.authUser,
                    TestConfig.authPassword,
                    TestConfig.authClient));
        }
    }

    private void processDataset() {
        if (delete) {
            logger().info("Deleting dataset.");
            processEntities(dataset.realms());
            logProcessedEntityCounts(true);
            closeAdminClients();
            logger().info("Dataset deleted.");
        } else {
            logger().info("Creating dataset.");
            processEntities(dataset.realms());
            processEntities(dataset.realmRoles());
            processEntities(dataset.clients());
            processEntities(dataset.clientRoles());
            processEntities(dataset.users());
            processEntities(dataset.credentials());
            processEntities(dataset.userRealmRoleMappings());
            processEntities(dataset.userClientRoleMappings());
            processEntities(dataset.groups());
            processEntities(dataset.resourceServers());
            processEntities(dataset.scopes());
            processEntities(dataset.resources());
            processEntities(dataset.rolePolicies());
            processEntities(dataset.jsPolicies());
            processEntities(dataset.userPolicies());
            processEntities(dataset.clientPolicies());
            processEntities(dataset.resourcePermissions());
            processEntities(dataset.scopePermissions());
            logProcessedEntityCounts(true);
            closeAdminClients();
            logger().info("Dataset created.");
        }
    }

    private void processEntities(Stream<? extends Updatable> stream) {
        if (!errorReported()) {
            Iterator<? extends Updatable> iterator = stream.iterator();
            ExecutorService threadPool = Executors.newFixedThreadPool(TestConfig.numOfWorkers);
            BlockingQueue<Updatable> queue = new LinkedBlockingQueue<>(TestConfig.numOfWorkers + 5);
            try {
                while (iterator.hasNext() && !errorReported()) {
                    logProcessedEntityCounts(false);
                    try {
                        if (queue.offer(iterator.next(), QUEUE_TIMEOUT, SECONDS)) {
                            threadPool.execute(() -> {
                                if (!errorReported()) {
                                    try {

                                        Updatable updatable = queue.take();
                                        Keycloak adminClient = adminClients.take();

                                        try {

                                            if (delete) {
                                                updatable.deleteOrIgnoreMissing(adminClient);
                                            } else {
                                                if (updatable instanceof Creatable) {
                                                    ((Creatable) updatable).createOrUpdateExisting(adminClient);
                                                } else {
                                                    updatable.update(adminClient);
                                                }
                                            }

                                            confirmEntityAsProcessed(updatable);

                                        } finally {
                                            adminClients.add(adminClient); // return client for reuse    
                                        }

                                    } catch (Exception ex) {
                                        reportError(ex);
                                    }
                                }
                            });
                        } else {
                            reportError(new TimeoutException("Waiting for executor timed out."));
                        }
                    } catch (InterruptedException ex) {
                        reportError(ex);
                    }
                }
            } catch (Exception ex) {
                reportError(ex);
            }
            // shut down threadpool
            if (errorReported()) {
                logger().error("Exception thrown from executor service. Shutting down.");
                threadPool.shutdownNow();
                throw new RuntimeException(error);
            } else {
                try {
                    threadPool.shutdown();
                    threadPool.awaitTermination(THREADPOOL_SHUTDOWN_TIMEOUT, SECONDS);
                    if (!threadPool.isTerminated()) {
                        throw new IllegalStateException("Executor service still not terminated.");
                    }
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private synchronized void confirmEntityAsProcessed(Updatable entity) {
        String key = entity.getClass().getSimpleName();
        Integer count = counter.get(key);
        count = count == null ? 1 : ++count;
        counter.put(key, count);
    }

    private synchronized void logProcessedEntityCounts(boolean ignoreTimestamp) {
        long time = new Date().getTime();
        if (startTime == 0) {
            startTime = new Date().getTime();
        }
        if (!counter.isEmpty() && (ignoreTimestamp || time > nextLoggingTime)) {

            StringBuilder sb = new StringBuilder();
            counter.entrySet().forEach(e -> sb.append(String.format("\n%-20s %s", e.getKey(), e.getValue())));
            logger().info(String.format("Time: +%s s\n%s entities: %s\n",
                    (time - startTime) / 1000,
                    (delete ? "Deleted" : "Created"),
                    sb.toString()
            ));
            nextLoggingTime = time + LOG_EVERY * 1000;
        }
    }

    private synchronized boolean errorReported() {
        return error != null;
    }

    private synchronized void reportError(Throwable ex) {
        logProcessedEntityCounts(true);
        logger().error("Error occured: " + ex);
        this.error = ex;
    }

    public void closeAdminClients() {
        while (!adminClients.isEmpty()) {
            adminClients.poll().close();
        }
    }

}
