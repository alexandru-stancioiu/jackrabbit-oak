/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.index.indexer.document.flatfile.pipelined;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoIncompatibleDriverException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.ReadPreference;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.apache.jackrabbit.guava.common.base.Preconditions;
import org.apache.jackrabbit.guava.common.base.Stopwatch;
import org.apache.jackrabbit.oak.commons.IOUtils;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.index.indexer.document.flatfile.pipelined.MongoRegexPathFilterFactory.MongoFilterPaths;
import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.mongo.MongoDocumentStore;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.apache.jackrabbit.oak.plugins.index.FormattingUtils;
import org.apache.jackrabbit.oak.plugins.index.MetricsFormatter;
import org.apache.jackrabbit.oak.plugins.index.MetricsUtils;
import org.apache.jackrabbit.oak.spi.filter.PathFilter;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Sorts.ascending;

public class PipelinedMongoDownloadTask implements Callable<PipelinedMongoDownloadTask.Result> {
    public static class Result {
        private final long documentsDownloaded;

        public Result(long documentsDownloaded) {
            this.documentsDownloaded = documentsDownloaded;
        }

        public long getDocumentsDownloaded() {
            return documentsDownloaded;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PipelinedMongoDownloadTask.class);

    /**
     * Whether to retry on connection errors to MongoDB.
     * This property affects the query that is used to download the documents from MongoDB. If set to true, the query
     * will traverse the results by order of the _modified property (does an index scan), which allows it to resume after
     * a failed connection from where it left off. If set to false, it uses a potentially more efficient query that does
     * not impose any order on the results (does a simple column scan).
     */
    public static final String OAK_INDEXER_PIPELINED_RETRY_ON_CONNECTION_ERRORS = "oak.indexer.pipelined.retryOnConnectionErrors";
    public static final boolean DEFAULT_OAK_INDEXER_PIPELINED_RETRY_ON_CONNECTION_ERRORS = true;
    public static final String OAK_INDEXER_PIPELINED_MONGO_CONNECTION_RETRY_SECONDS = "oak.indexer.pipelined.mongoConnectionRetrySeconds";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_MONGO_CONNECTION_RETRY_SECONDS = 300;

    /**
     * Whether to do path filtering in the Mongo query instead of doing a full traversal of the document store and
     * filtering in the indexing job. This feature may significantly reduce the number of documents downloaded from
     * Mongo.
     * The performance gains may not be proportional to the reduction in the number of documents downloaded because Mongo
     * still has to traverse all the documents. This is required because the regex expression used for path filtering
     * starts with a wildcard (because the _id starts with the depth of the path, so the regex expression must ignore
     * this part). Because of the wildcard at the start, Mongo cannot use of the index on _id.
     */
    public static final String OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING = "oak.indexer.pipelined.mongoRegexPathFiltering";
    public static final boolean DEFAULT_OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING = false;

    /**
     * Maximum number of elements in the included/excluded paths list used for regex path filtering. If after
     * merging and de-deduplication of the paths of all the path filters the number of included or excluded paths exceeds
     * this value, then disable path filtering to avoid creating Mongo queries with large number of filters
     */
    public static final String OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING_MAX_PATHS = "oak.indexer.pipelined.mongoRegexPathFilteringMaxPaths";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING_MAX_PATHS = 20;


    // Use a short initial retry interval. In most cases if the connection to a replica fails, there will be other
    // replicas available so a reconnection attempt will succeed immediately.
    private final static long retryInitialIntervalMillis = 100;
    private final static long retryMaxIntervalMillis = 10_000;

    // TODO: Revise this timeout. It is used to prevent the indexer from blocking forever if the queue is full.
    private static final Duration MONGO_QUEUE_OFFER_TIMEOUT = Duration.ofMinutes(30);
    private static final int MIN_INTERVAL_BETWEEN_DELAYED_ENQUEUING_MESSAGES = 10;
    private final static BsonDocument NATURAL_HINT = BsonDocument.parse("{ $natural: 1 }");
    private final static BsonDocument ID_INDEX_HINT = BsonDocument.parse("{ _id: 1 }");

    private static final String THREAD_NAME = "mongo-dump";

    private final int maxBatchNumberOfDocuments;
    private final BlockingQueue<NodeDocument[]> mongoDocQueue;
    private final List<PathFilter> pathFilters;
    private final int retryDuringSeconds;
    private final boolean retryOnConnectionErrors;
    private final boolean regexPathFiltering;
    private final Logger traversalLog = LoggerFactory.getLogger(PipelinedMongoDownloadTask.class.getName() + ".traversal");
    private final MongoCollection<NodeDocument> dbCollection;
    private final ReadPreference readPreference;
    private final Stopwatch downloadStartWatch = Stopwatch.createUnstarted();
    private final int maxBatchSizeBytes;
    private final StatisticsProvider statisticsProvider;
    private final MongoRegexPathFilterFactory regexPathFilterFactory;

    private long totalEnqueueWaitTimeMillis = 0;
    private Instant lastDelayedEnqueueWarningMessageLoggedTimestamp = Instant.now();
    private long documentsRead = 0;
    private long nextLastModified = 0;
    private String lastIdDownloaded = null;


    public PipelinedMongoDownloadTask(MongoDatabase mongoDatabase,
                                      MongoDocumentStore mongoDocStore,
                                      int maxBatchSizeBytes,
                                      int maxBatchNumberOfDocuments,
                                      BlockingQueue<NodeDocument[]> queue,
                                      List<PathFilter> pathFilters,
                                      StatisticsProvider statisticsProvider) {
        this.statisticsProvider = statisticsProvider;
        NodeDocumentCodecProvider nodeDocumentCodecProvider = new NodeDocumentCodecProvider(mongoDocStore, Collection.NODES);
        CodecRegistry nodeDocumentCodecRegistry = CodecRegistries.fromRegistries(
                CodecRegistries.fromProviders(nodeDocumentCodecProvider),
                MongoClientSettings.getDefaultCodecRegistry()
        );
        this.dbCollection = mongoDatabase
                .withCodecRegistry(nodeDocumentCodecRegistry)
                .getCollection(Collection.NODES.toString(), NodeDocument.class);
        this.maxBatchSizeBytes = maxBatchSizeBytes;
        this.maxBatchNumberOfDocuments = maxBatchNumberOfDocuments;
        this.mongoDocQueue = queue;
        this.pathFilters = pathFilters;

        // Default retries for 5 minutes.
        this.retryDuringSeconds = ConfigHelper.getSystemPropertyAsInt(
                OAK_INDEXER_PIPELINED_MONGO_CONNECTION_RETRY_SECONDS,
                DEFAULT_OAK_INDEXER_PIPELINED_MONGO_CONNECTION_RETRY_SECONDS);
        Preconditions.checkArgument(retryDuringSeconds > 0,
                "Property " + OAK_INDEXER_PIPELINED_MONGO_CONNECTION_RETRY_SECONDS + " must be > 0. Was: " + retryDuringSeconds);
        this.retryOnConnectionErrors = ConfigHelper.getSystemPropertyAsBoolean(
                OAK_INDEXER_PIPELINED_RETRY_ON_CONNECTION_ERRORS,
                DEFAULT_OAK_INDEXER_PIPELINED_RETRY_ON_CONNECTION_ERRORS);
        this.regexPathFiltering = ConfigHelper.getSystemPropertyAsBoolean(
                OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING,
                DEFAULT_OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING);
        int regexPathFilteringMaxNumberOfPaths = ConfigHelper.getSystemPropertyAsInt(
                OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING_MAX_PATHS,
                DEFAULT_OAK_INDEXER_PIPELINED_MONGO_REGEX_PATH_FILTERING_MAX_PATHS);
        this.regexPathFilterFactory = new MongoRegexPathFilterFactory(regexPathFilteringMaxNumberOfPaths);

        //TODO This may lead to reads being routed to secondary depending on MongoURI
        //So caller must ensure that its safe to read from secondary
//        this.readPreference = MongoDocumentStoreHelper.getConfiguredReadPreference(mongoStore, collection);
        this.readPreference = ReadPreference.secondaryPreferred();
        LOG.info("maxBatchSizeBytes: {}, maxBatchNumberOfDocuments: {}, readPreference: {}",
                maxBatchSizeBytes, maxBatchNumberOfDocuments, readPreference.getName());
    }

    @Override
    public Result call() throws Exception {
        String originalName = Thread.currentThread().getName();
        Thread.currentThread().setName(THREAD_NAME);
        LOG.info("[TASK:{}:START] Starting to download from MongoDB", THREAD_NAME.toUpperCase(Locale.ROOT));
        try {
            this.nextLastModified = 0;
            this.lastIdDownloaded = null;

            downloadStartWatch.start();
            if (retryOnConnectionErrors) {
                downloadWithRetryOnConnectionErrors();
            } else {
                downloadWithNaturalOrdering();
            }
            long durationMillis = downloadStartWatch.elapsed(TimeUnit.MILLISECONDS);
            String enqueueingDelayPercentage = PipelinedUtils.formatAsPercentage(totalEnqueueWaitTimeMillis, durationMillis);
            String metrics = MetricsFormatter.newBuilder()
                    .add("duration", FormattingUtils.formatToSeconds(downloadStartWatch))
                    .add("durationSeconds", durationMillis / 1000)
                    .add("documentsDownloaded", documentsRead)
                    .add("enqueueingDelayMillis", totalEnqueueWaitTimeMillis)
                    .add("enqueueingDelayPercentage", enqueueingDelayPercentage)
                    .build();
            MetricsUtils.setCounterOnce(statisticsProvider,
                    PipelinedMetrics.OAK_INDEXER_PIPELINED_MONGO_DOWNLOAD_DURATION_SECONDS,
                    durationMillis / 1000
            );
            MetricsUtils.setCounterOnce(statisticsProvider,
                    PipelinedMetrics.OAK_INDEXER_PIPELINED_DOCUMENTS_DOWNLOADED_TOTAL,
                    documentsRead
            );
            MetricsUtils.setCounterOnce(statisticsProvider,
                    PipelinedMetrics.OAK_INDEXER_PIPELINED_MONGO_DOWNLOAD_ENQUEUE_DELAY_PERCENTAGE,
                    PipelinedUtils.toPercentage(totalEnqueueWaitTimeMillis, durationMillis)
            );
            LOG.info("[TASK:{}:END] Metrics: {}", THREAD_NAME.toUpperCase(Locale.ROOT), metrics);
            return new Result(documentsRead);
        } catch (InterruptedException t) {
            LOG.warn("Thread interrupted", t);
            throw t;
        } catch (Throwable t) {
            LOG.warn("Thread terminating with exception.", t);
            throw t;
        } finally {
            Thread.currentThread().setName(originalName);
        }
    }

    private void reportProgress(String id) {
        if (this.documentsRead % 10000 == 0) {
            double rate = ((double) this.documentsRead) / downloadStartWatch.elapsed(TimeUnit.SECONDS);
            String formattedRate = String.format(Locale.ROOT, "%1.2f nodes/s, %1.2f nodes/hr", rate, rate * 3600);
            LOG.info("Dumping from NSET Traversed #{} {} [{}] (Elapsed {})",
                    this.documentsRead, id, formattedRate, FormattingUtils.formatToSeconds(downloadStartWatch));
        }
        traversalLog.trace(id);
    }

    private void downloadWithRetryOnConnectionErrors() throws InterruptedException, TimeoutException {
        // If regex filtering is enabled, start by downloading the ancestors of the path used for filtering.
        // That is, download "/", "/content", "/content/dam" for a base path of "/content/dam". These nodes will not be
        // matched by the regex used in the Mongo query, which assumes a prefix of "???:/content/dam"
        MongoFilterPaths mongoFilterPathsDefinition = getPathsForRegexFiltering();
        Bson childrenFilter;
        if (mongoFilterPathsDefinition == MongoFilterPaths.DOWNLOAD_ALL) {
            LOG.info("Downloading full repository");
            childrenFilter = null;
        } else {
            // Regex path filtering is enabled
            // Download the ancestors in a separate query. No retrials done on this query, as it will take only a few
            // seconds and is done at the start of the job, so if it fails, the job can be retried without losing much work
            downloadAncestors(mongoFilterPathsDefinition.included);

            // Filter to apply to the main query
            childrenFilter = descendantsFilter(mongoFilterPathsDefinition);
            LOG.info("Downloading from Mongo using filter: {}", childrenFilter);
        }

        Instant failuresStartTimestamp = null; // When the last series of failures started
        long retryIntervalMs = retryInitialIntervalMillis;
        int numberOfFailures = 0;
        boolean downloadCompleted = false;
        Map<String, Integer> exceptions = new HashMap<>();
        this.nextLastModified = 0;
        this.lastIdDownloaded = null;
        while (!downloadCompleted) {
            try {
                if (lastIdDownloaded != null) {
                    LOG.info("Recovering from broken connection, finishing downloading documents with _modified={}", nextLastModified);
                    downloadRange(new DownloadRange(nextLastModified, nextLastModified + 1, lastIdDownloaded), childrenFilter);
                    // We have managed to reconnect, reset the failure timestamp
                    failuresStartTimestamp = null;
                    numberOfFailures = 0;
                    // Continue downloading everything starting from the next _lastmodified value
                    downloadRange(new DownloadRange(nextLastModified + 1, Long.MAX_VALUE, null), childrenFilter);
                } else {
                    downloadRange(new DownloadRange(nextLastModified, Long.MAX_VALUE, null), childrenFilter);
                }
                downloadCompleted = true;
            } catch (MongoException e) {
                if (e instanceof MongoInterruptedException || e instanceof MongoIncompatibleDriverException) {
                    // Non-recoverable exceptions
                    throw e;
                }
                if (failuresStartTimestamp == null) {
                    failuresStartTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                }
                LOG.warn("Connection error downloading from MongoDB.", e);
                long secondsSinceStartOfFailures = Duration.between(failuresStartTimestamp, Instant.now()).toSeconds();
                if (secondsSinceStartOfFailures > retryDuringSeconds) {
                    // Give up. Get a string of all exceptions that were thrown
                    StringBuilder summary = new StringBuilder();
                    for (Map.Entry<String, Integer> entry : exceptions.entrySet()) {
                        summary.append("\n\t").append(entry.getValue()).append("x: ").append(entry.getKey());
                    }
                    throw new RetryException(retryDuringSeconds, summary.toString(), e);
                } else {
                    numberOfFailures++;
                    LOG.warn("Retrying download in {} ms; number of times failed: {}; current series of failures started at: {} ({} seconds ago)",
                            retryIntervalMs, numberOfFailures, failuresStartTimestamp, secondsSinceStartOfFailures);
                    exceptions.compute(e.getClass().getSimpleName() + " - " + e.getMessage(),
                            (key, val) -> val == null ? 1 : val + 1
                    );
                    Thread.sleep(retryIntervalMs);
                    // simple exponential backoff mechanism
                    retryIntervalMs = Math.min(retryMaxIntervalMillis, retryIntervalMs * 2);
                }
            }
        }
    }

    private void downloadRange(DownloadRange range, Bson filter) throws InterruptedException, TimeoutException {
        Bson findQuery = range.getFindQuery();
        if (filter != null) {
            findQuery = Filters.and(findQuery, filter);
        }
        LOG.info("Traversing: {}. Query: {}", range, findQuery);
        FindIterable<NodeDocument> mongoIterable = dbCollection
                .withReadPreference(readPreference)
                .find(findQuery)
                .sort(ascending(NodeDocument.MODIFIED_IN_SECS, NodeDocument.ID));
        download(mongoIterable);
    }

    private void downloadAncestors(List<String> basePath) throws InterruptedException, TimeoutException {
        if (basePath.size() == 1 && basePath.get(0).equals("/")) {
            return; // No need to download ancestors of root, the root will be downloaded as part of the normal traversal
        }
        Bson ancestorQuery = ancestorsFilter(basePath);
        LOG.info("Downloading ancestors of: {}, Query: {}.", basePath, ancestorQuery);
        FindIterable<NodeDocument> ancestorsIterable = dbCollection
                .withReadPreference(readPreference)
                .find(ancestorQuery)
                // Use the index on _id: this query returns very few documents and the filter condition is on _id.
                .hint(ID_INDEX_HINT);
        download(ancestorsIterable);
    }

    private void downloadWithNaturalOrdering() throws InterruptedException, TimeoutException {
        // We are downloading potentially a large fraction of the repository, so using an index scan will be
        // inefficient. So we pass the natural hint to force MongoDB to use natural ordering, that is, column scan
        MongoFilterPaths regexBasePath = getPathsForRegexFiltering();
        if (regexBasePath == MongoFilterPaths.DOWNLOAD_ALL) {
            LOG.info("Downloading full repository using natural order");
            FindIterable<NodeDocument> mongoIterable = dbCollection
                    .withReadPreference(readPreference)
                    .find()
                    .hint(NATURAL_HINT);
            download(mongoIterable);

        } else {
            downloadAncestors(regexBasePath.included);

            Bson childrenFilter = descendantsFilter(regexBasePath);
            FindIterable<NodeDocument> findIterable;
            if (childrenFilter == null) {
                LOG.info("Downloading full repository using natural order");
                findIterable = dbCollection
                        .withReadPreference(readPreference)
                        .find();
            } else {
                LOG.info("Downloading from Mongo using filter: {}", childrenFilter);
                findIterable = dbCollection
                        .withReadPreference(readPreference)
                        .find(childrenFilter);
            }
            download(findIterable.hint(NATURAL_HINT));
        }
    }

    private MongoFilterPaths getPathsForRegexFiltering() {
        if (!regexPathFiltering) {
            LOG.info("Regex path filtering disabled.");
            return MongoFilterPaths.DOWNLOAD_ALL;
        }
        LOG.info("Computing included/excluded paths for Mongo regex path filtering. PathFilters: {}", pathFilters.stream()
                .map(pf -> "PF{includedPaths=" + pf.getIncludedPaths() + ", excludedPaths=" + pf.getExcludedPaths() + "}")
                .collect(Collectors.joining(", ")));
        MongoFilterPaths mongoFilterPaths = this.regexPathFilterFactory.buildMongoFilter(pathFilters);
        LOG.info("Paths used for regex filtering on Mongo: {}", mongoFilterPaths);
        return mongoFilterPaths;
    }

    private Bson descendantsFilter(MongoFilterPaths mongoFilterPathsDefinition) {
        if (mongoFilterPathsDefinition == MongoFilterPaths.DOWNLOAD_ALL) {
            return null;
        }
        Bson pathFilter = descendantsFilter(mongoFilterPathsDefinition.included);
        if (mongoFilterPathsDefinition.excluded.isEmpty()) {
            return pathFilter;
        } else {
            // The Mongo filter returned here will download the top level path of each excluded subtree, which in theory
            // should be excluded. That is, if the tree /a/b/c is excluded, the filter will download /a/b/c but none of
            // its descendants.
            // This is done because excluding also the top level path would add extra complexity to the filter and
            // would not have any measurable impact on performance because it only downloads a few extra documents, one
            // for each excluded subtree. The transform stage will anyway filter out these paths.
            Bson excludedFilter = descendantsFilter(mongoFilterPathsDefinition.excluded);
            return Filters.and(pathFilter, Filters.nor(excludedFilter));
        }
    }

    private static Bson descendantsFilter(List<String> paths) {
        if (paths.isEmpty()) {
            return null;
        }
        // The filter for descendants of a list of paths is a series of or conditions. For each path, we have to build
        // two conditions in two different fields of the documents:
        // _ _id   - for non-long paths - In this case, the _id is of the form "2:/foo/bar"
        // _ _path - for long paths - In this case, the _id is n hash and the document contains an additional _path
        //      field with the path of the document.
        // We use the $in operator with a regular expression to match the paths.
        //  https://www.mongodb.com/docs/manual/reference/operator/query/in/#use-the--in-operator-with-a-regular-expression

        ArrayList<Pattern> pathPatterns = new ArrayList<>();
        ArrayList<Pattern> idPatterns = new ArrayList<>();

        for (String path : paths) {
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            String quotedPath = Pattern.quote(path);
            idPatterns.add(Pattern.compile("^[0-9]{1,3}:" + quotedPath + ".*$"));
            pathPatterns.add(Pattern.compile(quotedPath + ".*$"));
        }
        return Filters.or(
                Filters.in(NodeDocument.ID, idPatterns),
                Filters.in(NodeDocument.PATH, pathPatterns)
        );
    }

    static List<String> getAncestors(List<String> paths) {
        TreeSet<String> ancestors = new TreeSet<>();
        for (String child : paths) {
            String parent = child;
            while (true) {
                ancestors.add(parent);
                if (PathUtils.denotesRoot(parent)) {
                    break;
                }
                parent = PathUtils.getParentPath(parent);
            }
        }
        return new ArrayList<>(ancestors);
    }


    static Bson ancestorsFilter(List<String> paths) {
        List<String> parentFilters = getAncestors(paths).stream()
                .map(Utils::getIdFromPath)
                .collect(Collectors.toList());
        return Filters.in(NodeDocument.ID, parentFilters);
    }

    private void download(FindIterable<NodeDocument> mongoIterable) throws InterruptedException, TimeoutException {
        try (MongoCursor<NodeDocument> cursor = mongoIterable.iterator()) {
            NodeDocument[] batch = new NodeDocument[maxBatchNumberOfDocuments];
            int nextIndex = 0;
            int batchSize = 0;
            try {
                while (cursor.hasNext()) {
                    NodeDocument next = cursor.next();
                    String id = next.getId();
                    // If we are retrying on connection errors, we need to keep track of the last _modified value
                    if (retryOnConnectionErrors) {
                        this.nextLastModified = next.getModified();
                    }
                    this.lastIdDownloaded = id;
                    this.documentsRead++;
                    reportProgress(id);

                    batch[nextIndex] = next;
                    nextIndex++;
                    int docSize = (int) next.remove(NodeDocumentCodec.SIZE_FIELD);
                    batchSize += docSize;
                    if (batchSize >= maxBatchSizeBytes || nextIndex == batch.length) {
                        LOG.trace("Enqueuing block with {} elements, estimated size: {} bytes", nextIndex, batchSize);
                        tryEnqueueCopy(batch, nextIndex);
                        nextIndex = 0;
                        batchSize = 0;
                    }
                }
                if (nextIndex > 0) {
                    LOG.info("Enqueueing last block with {} elements, estimated size: {}",
                            nextIndex, IOUtils.humanReadableByteCountBin(batchSize));
                    tryEnqueueCopy(batch, nextIndex);
                }
            } catch (MongoException e) {
                if (e instanceof MongoInterruptedException || e instanceof MongoIncompatibleDriverException) {
                    // Non-recoverable exceptions
                    throw e;
                }
                // There may be some documents in the current batch, enqueue them and rethrow the exception
                if (nextIndex > 0) {
                    LOG.info("Connection interrupted with recoverable failure. Enqueueing partial block with {} elements, estimated size: {}",
                            nextIndex, IOUtils.humanReadableByteCountBin(batchSize));
                    tryEnqueueCopy(batch, nextIndex);
                }
                throw e;
            }
        }
    }

    private void tryEnqueueCopy(NodeDocument[] batch, int nextIndex) throws TimeoutException, InterruptedException {
        NodeDocument[] copyOfBatch = Arrays.copyOfRange(batch, 0, nextIndex);
        Stopwatch enqueueDelayStopwatch = Stopwatch.createStarted();
        if (!mongoDocQueue.offer(copyOfBatch, MONGO_QUEUE_OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timeout trying to enqueue batch of MongoDB documents. Waited " + MONGO_QUEUE_OFFER_TIMEOUT);
        }
        long enqueueDelay = enqueueDelayStopwatch.elapsed(TimeUnit.MILLISECONDS);
        totalEnqueueWaitTimeMillis += enqueueDelay;
        if (enqueueDelay > 1) {
            logWithRateLimit(() ->
                    LOG.info("Enqueuing of Mongo document batch was delayed, took {} ms. mongoDocQueue size {}. " +
                                    "Consider increasing the number of Transform threads. " +
                                    "(This message is logged at most once every {} seconds)",
                            enqueueDelay, mongoDocQueue.size(), MIN_INTERVAL_BETWEEN_DELAYED_ENQUEUING_MESSAGES)
            );
        }
    }

    private void logWithRateLimit(Runnable f) {
        Instant now = Instant.now();
        if (Duration.between(lastDelayedEnqueueWarningMessageLoggedTimestamp, now).toSeconds() > MIN_INTERVAL_BETWEEN_DELAYED_ENQUEUING_MESSAGES) {
            f.run();
            lastDelayedEnqueueWarningMessageLoggedTimestamp = now;
        }
    }

    private static class RetryException extends RuntimeException {

        private final int retrialDurationSeconds;

        public RetryException(int retrialDurationSeconds, String message, Throwable cause) {
            super(message, cause);
            this.retrialDurationSeconds = retrialDurationSeconds;
        }

        @Override
        public String toString() {
            return "Tried for " + retrialDurationSeconds + " seconds: \n" + super.toString();
        }
    }
}
