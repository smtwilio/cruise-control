/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.executor;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.common.KafkaCruiseControlThreadFactory;
import com.linkedin.kafka.cruisecontrol.common.MetadataClient;
import com.linkedin.kafka.cruisecontrol.monitor.LoadMonitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kafka.zk.KafkaZkClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.currentUtcDate;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.State.ABORTED;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.State.ABORTING;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.State.DEAD;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.State.IN_PROGRESS;


/**
 * Executor for Kafka GoalOptimizer.
 * <p>
 * The executor class is responsible for talking to the Kafka cluster to execute the rebalance proposals.
 *
 * The executor is thread-safe.
 */
public class Executor {
  private static final Logger LOG = LoggerFactory.getLogger(Executor.class);
  private static final long EXECUTION_HISTORY_SCANNER_PERIOD_SECONDS = 5;
  private static final long EXECUTION_HISTORY_SCANNER_INITIAL_DELAY_SECONDS = 0;
  // The maximum time to wait for a leader movement to finish. A leader movement will be marked as failed if
  // it takes longer than this time to finish.
  private static final long LEADER_ACTION_TIMEOUT_MS = 180000L;
  private static final String ZK_EXECUTOR_METRIC_GROUP = "CruiseControlExecutor";
  private static final String ZK_EXECUTOR_METRIC_TYPE = "Executor";
  // The execution progress is controlled by the ExecutionTaskManager.
  private final ExecutionTaskManager _executionTaskManager;
  private final MetadataClient _metadataClient;
  private final long _statusCheckingIntervalMs;
  private final ExecutorService _proposalExecutor;
  private final KafkaZkClient _kafkaZkClient;

  private static final long METADATA_REFRESH_BACKOFF = 100L;
  private static final long METADATA_EXPIRY_MS = Long.MAX_VALUE;

  // Some state for external service to query
  private final AtomicBoolean _stopRequested;
  private final Time _time;
  private volatile boolean _hasOngoingExecution;
  private volatile int _numFinishedPartitionMovements;
  private volatile int _numFinishedLeadershipMovements;
  private volatile long _finishedDataMovementInMB;
  private volatile ExecutorState _executorState;
  private volatile String _uuid;

  private AtomicInteger _numExecutionStopped;
  private AtomicInteger _numExecutionStoppedByUser;
  private AtomicInteger _numExecutionStartedInKafkaAssignerMode;
  private AtomicInteger _numExecutionStartedInNonKafkaAssignerMode;
  private volatile boolean _isKafkaAssignerMode;

  private static final String EXECUTION_STARTED = "execution-started";
  private static final String KAFKA_ASSIGNER_MODE = "kafka_assigner";
  private static final String EXECUTION_STOPPED = "execution-stopped";

  private static final String GAUGE_EXECUTION_STOPPED = EXECUTION_STOPPED;
  private static final String GAUGE_EXECUTION_STOPPED_BY_USER = EXECUTION_STOPPED + "-by-user";
  private static final String GAUGE_EXECUTION_STARTED_IN_KAFKA_ASSIGNER_MODE = EXECUTION_STARTED + "-"  + KAFKA_ASSIGNER_MODE;
  private static final String GAUGE_EXECUTION_STARTED_IN_NON_KAFKA_ASSIGNER_MODE = EXECUTION_STARTED + "-non-"  + KAFKA_ASSIGNER_MODE;
  // TODO: Execution history is currently kept in memory, but ideally we should move it to a persistent store.
  private final long _demotionHistoryRetentionTimeMs;
  private final long _removalHistoryRetentionTimeMs;
  private final ConcurrentMap<Integer, Long> _latestDemoteStartTimeMsByBrokerId;
  private final ConcurrentMap<Integer, Long> _latestRemoveStartTimeMsByBrokerId;
  private final ScheduledExecutorService _executionHistoryScannerExecutor;

  /**
   * The executor class that execute the proposals generated by optimizer.
   *
   * @param config The configurations for Cruise Control.
   */
  public Executor(KafkaCruiseControlConfig config,
                  Time time,
                  MetricRegistry dropwizardMetricRegistry,
                  long demotionHistoryRetentionTimeMs,
                  long removalHistoryRetentionTimeMs) {
    this(config, time, dropwizardMetricRegistry, null, demotionHistoryRetentionTimeMs, removalHistoryRetentionTimeMs);
  }

  /**
   * The executor class that execute the proposals generated by optimizer.
   * Package private for unit test.
   *
   * @param config The configurations for Cruise Control.
   */
  Executor(KafkaCruiseControlConfig config,
           Time time,
           MetricRegistry dropwizardMetricRegistry,
           MetadataClient metadataClient,
           long demotionHistoryRetentionTimeMs,
           long removalHistoryRetentionTimeMs) {
    String zkUrl = config.getString(KafkaCruiseControlConfig.ZOOKEEPER_CONNECT_CONFIG);
    _numExecutionStopped = new AtomicInteger(0);
    _numExecutionStoppedByUser = new AtomicInteger(0);
    _numExecutionStartedInKafkaAssignerMode = new AtomicInteger(0);
    _numExecutionStartedInNonKafkaAssignerMode = new AtomicInteger(0);
    _isKafkaAssignerMode = false;
    // Register gauge sensors.
    registerGaugeSensors(dropwizardMetricRegistry);

    _time = time;
    _kafkaZkClient = KafkaCruiseControlUtils.createKafkaZkClient(zkUrl, ZK_EXECUTOR_METRIC_GROUP, ZK_EXECUTOR_METRIC_TYPE);
    _executionTaskManager =
        new ExecutionTaskManager(config.getInt(KafkaCruiseControlConfig.NUM_CONCURRENT_PARTITION_MOVEMENTS_PER_BROKER_CONFIG),
                                 config.getInt(KafkaCruiseControlConfig.NUM_CONCURRENT_LEADER_MOVEMENTS_CONFIG),
                                 config.getList(KafkaCruiseControlConfig.REPLICA_MOVEMENT_STRATEGIES_CONFIG),
                                 dropwizardMetricRegistry,
                                 time);
    _metadataClient = metadataClient != null
                      ? metadataClient
                      : new MetadataClient(config,
                                           new Metadata(METADATA_REFRESH_BACKOFF, METADATA_EXPIRY_MS, false),
                                           -1L,
                                           time);
    _statusCheckingIntervalMs = config.getLong(KafkaCruiseControlConfig.EXECUTION_PROGRESS_CHECK_INTERVAL_MS_CONFIG);
    _proposalExecutor =
        Executors.newSingleThreadExecutor(new KafkaCruiseControlThreadFactory("ProposalExecutor", false, LOG));
    _latestDemoteStartTimeMsByBrokerId = new ConcurrentHashMap<>();
    _latestRemoveStartTimeMsByBrokerId = new ConcurrentHashMap<>();
    _executorState = ExecutorState.noTaskInProgress(recentlyDemotedBrokers(), recentlyRemovedBrokers());
    _stopRequested = new AtomicBoolean(false);
    _hasOngoingExecution = false;
    _uuid = null;
    _demotionHistoryRetentionTimeMs = demotionHistoryRetentionTimeMs;
    _removalHistoryRetentionTimeMs = removalHistoryRetentionTimeMs;
    _executionHistoryScannerExecutor = Executors.newSingleThreadScheduledExecutor(
        new KafkaCruiseControlThreadFactory("ExecutionHistoryScanner", true, null));
    _executionHistoryScannerExecutor.scheduleAtFixedRate(new ExecutionHistoryScanner(),
                                                         EXECUTION_HISTORY_SCANNER_INITIAL_DELAY_SECONDS,
                                                         EXECUTION_HISTORY_SCANNER_PERIOD_SECONDS,
                                                         TimeUnit.SECONDS);
  }

  /**
   * Register gauge sensors.
   */
  private void registerGaugeSensors(MetricRegistry dropwizardMetricRegistry) {
    String metricName = "Executor";
    dropwizardMetricRegistry.register(MetricRegistry.name(metricName, GAUGE_EXECUTION_STOPPED),
                                      (Gauge<Integer>) this::numExecutionStopped);
    dropwizardMetricRegistry.register(MetricRegistry.name(metricName, GAUGE_EXECUTION_STOPPED_BY_USER),
                                      (Gauge<Integer>) this::numExecutionStoppedByUser);
    dropwizardMetricRegistry.register(MetricRegistry.name(metricName, GAUGE_EXECUTION_STARTED_IN_KAFKA_ASSIGNER_MODE),
                                      (Gauge<Integer>) this::numExecutionStartedInKafkaAssignerMode);
    dropwizardMetricRegistry.register(MetricRegistry.name(metricName, GAUGE_EXECUTION_STARTED_IN_NON_KAFKA_ASSIGNER_MODE),
                                      (Gauge<Integer>) this::numExecutionStartedInNonKafkaAssignerMode);
  }

  private void removeExpiredDemotionHistory() {
    LOG.debug("Remove expired demotion history");
    _latestDemoteStartTimeMsByBrokerId.entrySet().removeIf(entry -> (entry.getValue() + _demotionHistoryRetentionTimeMs
                                                                     < _time.milliseconds()));
  }

  private void removeExpiredRemovalHistory() {
    LOG.debug("Remove expired broker removal history");
    _latestRemoveStartTimeMsByBrokerId.entrySet().removeIf(entry -> (entry.getValue() + _removalHistoryRetentionTimeMs
                                                                     < _time.milliseconds()));
  }

  /**
   * A runnable class to remove expired execution history.
   */
  private class ExecutionHistoryScanner implements Runnable {
    @Override
    public void run() {
      try {
        removeExpiredDemotionHistory();
        removeExpiredRemovalHistory();
      } catch (Throwable t) {
        LOG.warn("Received exception when trying to expire execution history.", t);
      }
    }
  }

  /**
   * Recently demoted brokers are the ones for which a demotion was started, regardless of how the process was completed.
   *
   * @return IDs of recently demoted brokers -- i.e. demoted within the last {@link #_demotionHistoryRetentionTimeMs}.
   */
  public Set<Integer> recentlyDemotedBrokers() {
    return Collections.unmodifiableSet(_latestDemoteStartTimeMsByBrokerId.keySet());
  }

  /**
   * Recently removed brokers are the ones for which a removal was started, regardless of how the process was completed.
   *
   * @return IDs of recently removed brokers -- i.e. removed within the last {@link #_removalHistoryRetentionTimeMs}.
   */
  public Set<Integer> recentlyRemovedBrokers() {
    return Collections.unmodifiableSet(_latestRemoveStartTimeMsByBrokerId.keySet());
  }

  /**
   * Check whether the executor is executing a set of proposals.
   */
  public ExecutorState state() {
    return _executorState;
  }

  /**
   * Initialize proposal execution and start execution.
   *
   * @param proposals Proposals to be executed.
   * @param unthrottledBrokers Brokers that are not throttled in terms of the number of in/out replica movements.
   * @param removedBrokers Removed brokers, null if no brokers has been removed.
   * @param loadMonitor Load monitor.
   * @param requestedPartitionMovementConcurrency The maximum number of concurrent partition movements per broker
   *                                              (if null, use num.concurrent.partition.movements.per.broker).
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements
   *                                               (if null, use num.concurrent.leader.movements).
   * @param uuid UUID of the execution.
   */
  public synchronized void executeProposals(Collection<ExecutionProposal> proposals,
                                            Collection<Integer> unthrottledBrokers,
                                            Collection<Integer> removedBrokers,
                                            LoadMonitor loadMonitor,
                                            Integer requestedPartitionMovementConcurrency,
                                            Integer requestedLeadershipMovementConcurrency,
                                            String uuid) {
    initProposalExecution(proposals, unthrottledBrokers, loadMonitor, requestedPartitionMovementConcurrency,
                          requestedLeadershipMovementConcurrency, uuid);
    startExecution(loadMonitor, null, removedBrokers);
  }

  private synchronized void initProposalExecution(Collection<ExecutionProposal> proposals,
                                                  Collection<Integer> brokersToSkipConcurrencyCheck,
                                                  LoadMonitor loadMonitor,
                                                  Integer requestedPartitionMovementConcurrency,
                                                  Integer requestedLeadershipMovementConcurrency,
                                                  String uuid) {
    if (_hasOngoingExecution) {
      throw new IllegalStateException("Cannot execute new proposals while there is an ongoing execution.");
    }

    if (loadMonitor == null) {
      throw new IllegalArgumentException("Load monitor cannot be null.");
    }
    _executionTaskManager.setExecutionModeForTaskTracker(_isKafkaAssignerMode);
    _executionTaskManager.addExecutionProposals(proposals, brokersToSkipConcurrencyCheck, _metadataClient.refreshMetadata().cluster());
    setRequestedPartitionMovementConcurrency(requestedPartitionMovementConcurrency);
    setRequestedLeadershipMovementConcurrency(requestedLeadershipMovementConcurrency);
    if (uuid == null) {
      LOG.info("Executing a request triggered by an anomaly detector.");
    }
    _uuid = uuid;
  }

  /**
   * Initialize proposal execution and start demotion.
   *
   * @param proposals Proposals to be executed.
   * @param demotedBrokers Demoted brokers.
   * @param loadMonitor Load monitor.
   * @param concurrentSwaps The number of concurrent swap operations per broker.
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements
   *                                               (if null, use num.concurrent.leader.movements).
   * @param uuid UUID of the execution.
   */
  public synchronized void executeDemoteProposals(Collection<ExecutionProposal> proposals,
                                                  Collection<Integer> demotedBrokers,
                                                  LoadMonitor loadMonitor,
                                                  Integer concurrentSwaps,
                                                  Integer requestedLeadershipMovementConcurrency,
                                                  String uuid) {
    initProposalExecution(proposals, demotedBrokers, loadMonitor, concurrentSwaps, requestedLeadershipMovementConcurrency, uuid);
    startExecution(loadMonitor, demotedBrokers, null);
  }

  /**
   * Dynamically set the partition movement concurrency per broker.
   *
   * @param requestedPartitionMovementConcurrency The maximum number of concurrent partition movements per broker.
   */
  public void setRequestedPartitionMovementConcurrency(Integer requestedPartitionMovementConcurrency) {
    _executionTaskManager.setRequestedPartitionMovementConcurrency(requestedPartitionMovementConcurrency);
  }

  /**
   * Dynamically set the leadership movement concurrency.
   *
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements.
   */
  public void setRequestedLeadershipMovementConcurrency(Integer requestedLeadershipMovementConcurrency) {
    _executionTaskManager.setRequestedLeadershipMovementConcurrency(requestedLeadershipMovementConcurrency);
  }

  /**
   * Set the execution mode of the tasks to keep track of the ongoing execution mode via sensors.
   *
   * @param isKafkaAssignerMode True if kafka assigner mode, false otherwise.
   */
  public synchronized void setExecutionMode(boolean isKafkaAssignerMode) {
    _isKafkaAssignerMode = isKafkaAssignerMode;
  }

  private int numExecutionStopped() {
    return _numExecutionStopped.get();
  }

  private int numExecutionStoppedByUser() {
    return _numExecutionStoppedByUser.get();
  }

  private int numExecutionStartedInKafkaAssignerMode() {
    return _numExecutionStartedInKafkaAssignerMode.get();
  }

  private int numExecutionStartedInNonKafkaAssignerMode() {
    return _numExecutionStartedInNonKafkaAssignerMode.get();
  }

  /**
   * Pause the load monitor and kick off the execution.
   *
   * @param loadMonitor Load monitor.
   * @param demotedBrokers Brokers to be demoted, null if no broker has been demoted.
   * @param removedBrokers Brokers to be removed, null if no broker has been removed.
   */
  private void startExecution(LoadMonitor loadMonitor, Collection<Integer> demotedBrokers, Collection<Integer> removedBrokers) {
    if (!ExecutorUtils.partitionsBeingReassigned(_kafkaZkClient).isEmpty()) {
      _executionTaskManager.clear();
      _uuid = null;
      // Note that in case there is an ongoing partition reassignment, we do not unpause metric sampling.
      throw new IllegalStateException("There are ongoing partition reassignments.");
    }
    _hasOngoingExecution = true;
    _stopRequested.set(false);
    if (_isKafkaAssignerMode) {
      _numExecutionStartedInKafkaAssignerMode.incrementAndGet();
    } else {
      _numExecutionStartedInNonKafkaAssignerMode.incrementAndGet();
    }
    _proposalExecutor.submit(new ProposalExecutionRunnable(loadMonitor, demotedBrokers, removedBrokers));
  }

  /**
   * Request the executor to stop any ongoing execution.
   */
  public synchronized void userTriggeredStopExecution() {
    if (stopExecution()) {
      _numExecutionStoppedByUser.incrementAndGet();
    }
  }

  /**
   * Request the executor to stop any ongoing execution.
   *
   * @return True if the flag to stop the execution is set after the call (i.e. was not set already), false otherwise.
   */
  private synchronized boolean stopExecution() {
    if (_stopRequested.compareAndSet(false, true)) {
      _numExecutionStopped.incrementAndGet();
      return true;
    }
    return false;
  }

  /**
   * Shutdown the executor.
   */
  public synchronized void shutdown() {
    LOG.info("Shutting down executor.");
    if (_hasOngoingExecution) {
      LOG.warn("Shutdown executor may take long because execution is still in progress.");
    }
    _proposalExecutor.shutdown();

    try {
      _proposalExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting for anomaly detector to shutdown.");
    }
    _metadataClient.close();
    KafkaCruiseControlUtils.closeKafkaZkClientWithTimeout(_kafkaZkClient);
    _executionHistoryScannerExecutor.shutdownNow();
    LOG.info("Executor shutdown completed.");
  }

  /**
   * package private for unit test.
   */
  boolean hasOngoingExecution() {
    return _hasOngoingExecution;
  }

  /**
   * This class is thread safe.
   *
   * Note that once the thread for {@link ProposalExecutionRunnable} is submitted for running, the variable
   * _executionTaskManager can only be written within this inner class, but not from the outer Executor class.
   */
  private class ProposalExecutionRunnable implements Runnable {
    private final LoadMonitor _loadMonitor;
    private ExecutorState.State _state;
    private Set<Integer> _recentlyDemotedBrokers;
    private Set<Integer> _recentlyRemovedBrokers;

    ProposalExecutionRunnable(LoadMonitor loadMonitor, Collection<Integer> demotedBrokers, Collection<Integer> removedBrokers) {
      _loadMonitor = loadMonitor;
      _state = ExecutorState.State.NO_TASK_IN_PROGRESS;

      if (demotedBrokers != null) {
        // Add/overwrite the latest demotion time of demoted brokers (if any).
        demotedBrokers.forEach(id -> _latestDemoteStartTimeMsByBrokerId.put(id, _time.milliseconds()));
      }
      if (removedBrokers != null) {
        // Add/overwrite the latest removal time of removed brokers (if any).
        removedBrokers.forEach(id -> _latestRemoveStartTimeMsByBrokerId.put(id, _time.milliseconds()));
      }
      _recentlyDemotedBrokers = recentlyDemotedBrokers();
      _recentlyRemovedBrokers = recentlyRemovedBrokers();
    }

    public void run() {
      LOG.info("Starting executing balancing proposals.");
      execute();
      LOG.info("Execution finished.");
    }

    /**
     * Start the actual execution of the proposals in order: First move replicas, then transfer leadership.
     */
    private void execute() {
      _state = ExecutorState.State.STARTING_EXECUTION;
      _executorState = ExecutorState.executionStarted(_uuid, _recentlyDemotedBrokers, _recentlyRemovedBrokers);
      try {
        // Pause the metric sampling to avoid the loss of accuracy during execution.
        while (true) {
          try {
            // Ensure that the temporary states in the load monitor are explicitly handled -- e.g. SAMPLING.
            _loadMonitor.pauseMetricSampling(String.format("Paused-By-Cruise-Control-Before-Starting-Execution (Date: %s)", currentUtcDate()));
            break;
          } catch (IllegalStateException e) {
            Thread.sleep(_statusCheckingIntervalMs);
            LOG.debug("Waiting for the load monitor to be ready to initialize the execution.", e);
          }
        }

        // 1. Move replicas if possible.
        if (_state == ExecutorState.State.STARTING_EXECUTION) {
          _state = ExecutorState.State.REPLICA_MOVEMENT_TASK_IN_PROGRESS;
          // The _executorState might be inconsistent with _state if the user checks it between the two assignments.
          _executorState = ExecutorState.replicaMovementInProgress(_numFinishedPartitionMovements,
                                                                   _executionTaskManager.getExecutionTasksSummary(),
                                                                   _finishedDataMovementInMB,
                                                                   _executionTaskManager.partitionMovementConcurrency(),
                                                                   _executionTaskManager.leadershipMovementConcurrency(),
                                                                   _uuid,
                                                                   _recentlyDemotedBrokers,
                                                                   _recentlyRemovedBrokers);
          moveReplicas();
          updateOngoingExecutionState();
        }
        // 2. Transfer leadership if possible.
        if (_state == ExecutorState.State.REPLICA_MOVEMENT_TASK_IN_PROGRESS) {
          _state = ExecutorState.State.LEADER_MOVEMENT_TASK_IN_PROGRESS;
          // The _executorState might be inconsistent with _state if the user checks it between the two assignments.
          _executorState = ExecutorState.leaderMovementInProgress(_numFinishedLeadershipMovements,
                                                                  _executionTaskManager.getExecutionTasksSummary(),
                                                                  _executionTaskManager.partitionMovementConcurrency(),
                                                                  _executionTaskManager.leadershipMovementConcurrency(),
                                                                  _uuid,
                                                                  _recentlyDemotedBrokers,
                                                                  _recentlyRemovedBrokers);
          moveLeaderships();
          updateOngoingExecutionState();
        }

      } catch (Throwable t) {
        LOG.error("Executor got exception during execution", t);
      } finally {
        _loadMonitor.resumeMetricSampling(String.format("Resumed-By-Cruise-Control-After-Completed-Execution (Date: %s)", currentUtcDate()));
        // Clear completed execution.
        clearCompletedExecution();
      }
    }

    private void clearCompletedExecution() {
      _executionTaskManager.clear();
      _uuid = null;
      _state = ExecutorState.State.NO_TASK_IN_PROGRESS;
      // The _executorState might be inconsistent with _state if the user checks it between the two assignments.
      _executorState = ExecutorState.noTaskInProgress(_recentlyDemotedBrokers, _recentlyRemovedBrokers);
      _hasOngoingExecution = false;
      _stopRequested.set(false);
      _numFinishedPartitionMovements = 0;
      _numFinishedLeadershipMovements = 0;
      _finishedDataMovementInMB = 0L;
    }

    private void updateOngoingExecutionState() {
      if (!_stopRequested.get()) {
        switch (_state) {
          case LEADER_MOVEMENT_TASK_IN_PROGRESS:
            _executorState = ExecutorState.leaderMovementInProgress(_numFinishedLeadershipMovements,
                                                                    _executionTaskManager.getExecutionTasksSummary(),
                                                                    _executionTaskManager.partitionMovementConcurrency(),
                                                                    _executionTaskManager.leadershipMovementConcurrency(),
                                                                    _uuid,
                                                                    _recentlyDemotedBrokers,
                                                                    _recentlyRemovedBrokers);
            break;
          case REPLICA_MOVEMENT_TASK_IN_PROGRESS:
            _executorState = ExecutorState.replicaMovementInProgress(_numFinishedPartitionMovements,
                                                                     _executionTaskManager.getExecutionTasksSummary(),
                                                                     _finishedDataMovementInMB,
                                                                     _executionTaskManager.partitionMovementConcurrency(),
                                                                     _executionTaskManager.leadershipMovementConcurrency(),
                                                                     _uuid,
                                                                     _recentlyDemotedBrokers,
                                                                     _recentlyRemovedBrokers);
            break;
          default:
            throw new IllegalStateException("Unexpected ongoing execution state " + _state);
        }
      } else {
        _state = ExecutorState.State.STOPPING_EXECUTION;
        _executorState = ExecutorState.stopping(_numFinishedPartitionMovements,
                                                _numFinishedLeadershipMovements,
                                                _executionTaskManager.getExecutionTasksSummary(),
                                                _finishedDataMovementInMB,
                                                _executionTaskManager.partitionMovementConcurrency(),
                                                _executionTaskManager.leadershipMovementConcurrency(), _uuid,
                                                _recentlyDemotedBrokers,
                                                _recentlyRemovedBrokers);
      }
    }

    private void moveReplicas() {
      int numTotalPartitionMovements = _executionTaskManager.remainingPartitionMovements().size();
      long totalDataToMoveInMB = _executionTaskManager.remainingDataToMoveInMB();
      LOG.info("Starting {} partition movements.", numTotalPartitionMovements);

      int partitionsToMove = numTotalPartitionMovements;
      // Exhaust all the pending partition movements.
      while ((partitionsToMove > 0 || !_executionTaskManager.inExecutionTasks().isEmpty()) && !_stopRequested.get()) {
        // Get tasks to execute.
        List<ExecutionTask> tasksToExecute = _executionTaskManager.getReplicaMovementTasks();
        LOG.info("Executor will execute {} task(s)", tasksToExecute.size());

        if (!tasksToExecute.isEmpty()) {
          // Execute the tasks.
          _executionTaskManager.markTasksInProgress(tasksToExecute);
          ExecutorUtils.executeReplicaReassignmentTasks(_kafkaZkClient, tasksToExecute);
        }
        // Wait indefinitely for partition movements to finish.
        waitForExecutionTaskToFinish();
        partitionsToMove = _executionTaskManager.remainingPartitionMovements().size();
        long dataToMove = _executionTaskManager.remainingDataToMoveInMB();
        Set<ExecutionTask> inExecutionTasks = _executionTaskManager.inExecutionTasks();
        _numFinishedPartitionMovements = numTotalPartitionMovements - partitionsToMove - inExecutionTasks.size();
        _finishedDataMovementInMB = totalDataToMoveInMB - dataToMove - _executionTaskManager.inExecutionDataToMoveInMB();
        LOG.info("{}/{} ({}%) partition movements completed. {}/{} ({}%) MB have been moved.",
                 _numFinishedPartitionMovements, numTotalPartitionMovements,
                 String.format(java.util.Locale.US, "%.2f",
                               _numFinishedPartitionMovements * 100.0 / numTotalPartitionMovements),
                 _finishedDataMovementInMB, totalDataToMoveInMB,
                 totalDataToMoveInMB == 0 ? 100 : String.format(java.util.Locale.US, "%.2f",
                                                                (_finishedDataMovementInMB * 100.0) / totalDataToMoveInMB));
      }
      // After the partition movement finishes, wait for the controller to clean the reassignment zkPath. This also
      // ensures a clean stop when the execution is stopped in the middle.
      Set<ExecutionTask> inExecutionTasks = _executionTaskManager.inExecutionTasks();
      while (!inExecutionTasks.isEmpty()) {
        LOG.info("Waiting for {} tasks moving {} MB to finish: {}",
                 inExecutionTasks.size(), _executionTaskManager.inExecutionDataToMoveInMB(), inExecutionTasks);
        waitForExecutionTaskToFinish();
        inExecutionTasks = _executionTaskManager.inExecutionTasks();
      }
      if (_executionTaskManager.inProgressTasks().isEmpty()) {
        LOG.info("Partition movements finished.");
      } else if (_stopRequested.get()) {
        ExecutionTaskManager.ExecutionTasksSummary executionTasksSummary = _executionTaskManager.getExecutionTasksSummary();
        LOG.info("Partition movements stopped. {} in-progress, {} pending, {} aborting, {} aborted, {} dead, "
                 + "{} remaining data to move.",
                 executionTasksSummary.inProgressTasks().size(),
                 executionTasksSummary.remainingPartitionMovements().size(),
                 executionTasksSummary.abortingTasks(),
                 executionTasksSummary.abortedTasks().size(),
                 executionTasksSummary.deadTasks().size(),
                 executionTasksSummary.remainingDataToMoveInMB());
      }
    }

    private void moveLeaderships() {
      int numTotalLeadershipMovements = _executionTaskManager.remainingLeadershipMovements().size();
      LOG.info("Starting {} leadership movements.", numTotalLeadershipMovements);
      _numFinishedLeadershipMovements = 0;
      while (!_executionTaskManager.remainingLeadershipMovements().isEmpty() && !_stopRequested.get()) {
        updateOngoingExecutionState();
        _numFinishedLeadershipMovements += moveLeadershipInBatch();
        LOG.info("{}/{} ({}%) leadership movements completed.", _numFinishedLeadershipMovements,
                 numTotalLeadershipMovements, _numFinishedLeadershipMovements * 100 / numTotalLeadershipMovements);
      }
      LOG.info("Leadership movements finished.");
    }

    private int moveLeadershipInBatch() {
      List<ExecutionTask> leadershipMovementTasks = _executionTaskManager.getLeadershipMovementTasks();
      int numLeadershipToMove = leadershipMovementTasks.size();
      LOG.debug("Executing {} leadership movements in a batch.", numLeadershipToMove);
      // Execute the leadership movements.
      if (!leadershipMovementTasks.isEmpty() && !_stopRequested.get()) {
        // Mark leadership movements in progress.
        _executionTaskManager.markTasksInProgress(leadershipMovementTasks);

        // Run preferred leader election.
        ExecutorUtils.executePreferredLeaderElection(_kafkaZkClient, leadershipMovementTasks);
        LOG.trace("Waiting for leadership movement batch to finish.");
        while (!_executionTaskManager.inProgressTasks().isEmpty() && !_stopRequested.get()) {
          waitForExecutionTaskToFinish();
        }
      }
      return numLeadershipToMove;
    }

    /**
     * This method periodically check zookeeper to see if the partition reassignment has finished or not.
     */
    private void waitForExecutionTaskToFinish() {
      List<ExecutionTask> finishedTasks = new ArrayList<>();
      do {
        // If there is no finished tasks, we need to check if anything is blocked.
        maybeReexecuteTasks();
        try {
          Thread.sleep(_statusCheckingIntervalMs);
        } catch (InterruptedException e) {
          // let it go
        }

        Cluster cluster = _metadataClient.refreshMetadata().cluster();
        LOG.debug("Tasks in execution: {}", _executionTaskManager.inExecutionTasks());
        List<ExecutionTask> deadOrAbortingTasks = new ArrayList<>();
        for (ExecutionTask task : _executionTaskManager.inExecutionTasks()) {
          TopicPartition tp = task.proposal().topicPartition();
          if (cluster.partition(tp) == null) {
            // Handle topic deletion during the execution.
            LOG.debug("Task {} is marked as finished because the topic has been deleted", task);
            finishedTasks.add(task);
            _executionTaskManager.markTaskAborting(task);
            _executionTaskManager.markTaskDone(task);
          } else if (isTaskDone(cluster, tp, task)) {
            // Check to see if the task is done.
            finishedTasks.add(task);
            _executionTaskManager.markTaskDone(task);
          } else if (maybeMarkTaskAsDeadOrAborting(cluster, task)) {
            // Only add the dead or aborted tasks to execute if it is not a leadership movement.
            if (task.type() != ExecutionTask.TaskType.LEADER_ACTION) {
              deadOrAbortingTasks.add(task);
            }
            // A dead or aborted task is considered as finished.
            if (task.state() == DEAD || task.state() == ABORTED) {
              finishedTasks.add(task);
            }
          }
        }
        // TODO: Execute the dead or aborted tasks.
        if (!deadOrAbortingTasks.isEmpty()) {
          // TODO: re-enable this rollback action when KAFKA-6304 is available.
          // ExecutorUtils.executeReplicaReassignmentTasks(_kafkaZkClient, deadOrAbortingTasks);
          if (!_stopRequested.get()) {
            // If there is task aborted or dead, we stop the execution.
            stopExecution();
          }
        }
        updateOngoingExecutionState();
      } while (!_executionTaskManager.inExecutionTasks().isEmpty() && finishedTasks.isEmpty());
      // Some tasks have finished, remove them from in progress task map.
      LOG.info("Completed tasks: {}", finishedTasks);
    }

    /**
     * Check if a task is done.
     */
    private boolean isTaskDone(Cluster cluster, TopicPartition tp, ExecutionTask task) {
      if (task.type() == ExecutionTask.TaskType.REPLICA_ACTION) {
        return isReplicaActionDone(cluster, tp, task);
      } else {
        return isLeadershipMovementDone(cluster, tp, task);
      }
    }

    /**
     * For a replica action, the completion depends on the task state:
     * IN_PROGRESS: when the current replica list is the same as the new replica list.
     * ABORTING: done when the current replica list is the same as the old replica list. Due to race condition,
     *           we also consider it done if the current replica list is the same as the new replica list.
     * DEAD: always considered as done because we neither move forward or rollback.
     *
     * There should be no other task state seen here.
     */
    private boolean isReplicaActionDone(Cluster cluster, TopicPartition tp, ExecutionTask task) {
      Node[] currentOrderedReplicas = cluster.partition(tp).replicas();
      switch (task.state()) {
        case IN_PROGRESS:
          return task.proposal().isCompletedSuccessfully(currentOrderedReplicas);
        case ABORTING:
          return task.proposal().isAborted(currentOrderedReplicas);
        case DEAD:
          return true;
        default:
          throw new IllegalStateException("Should never be here. State " + task.state());
      }
    }

    private boolean isInIsr(Integer leader, Cluster cluster, TopicPartition tp) {
      return Arrays.stream(cluster.partition(tp).inSyncReplicas()).anyMatch(node -> node.id() == leader);
    }

    /**
     * The completeness of leadership movement depends on the task state:
     * IN_PROGRESS: done when the leader becomes the destination.
     * ABORTING or DEAD: always considered as done the destination cannot become leader anymore.
     *
     * There should be no other task state seen here.
     */
    private boolean isLeadershipMovementDone(Cluster cluster, TopicPartition tp, ExecutionTask task) {
      Node leader = cluster.leaderFor(tp);
      switch (task.state()) {
        case IN_PROGRESS:
          return (leader != null && leader.id() == task.proposal().newLeader())
                 || leader == null
                 || !isInIsr(task.proposal().newLeader(), cluster, tp);
        case ABORTING:
        case DEAD:
          return true;
        default:
          throw new IllegalStateException("Should never be here.");
      }

    }

    /**
     * Mark the task as aborting or dead if needed.
     *
     * Ideally, the task should be marked as:
     * 1. ABORTING: when the execution is stopped by the users.
     * 2. ABORTING: When the destination broker is dead so the task cannot make progress, but the source broker is
     *              still alive.
     * 3. DEAD: when any replica in the new replica list is dead. Or when a leader action times out.
     *
     * Currently KafkaController does not support updates on the partitions that is being reassigned. (KAFKA-6304)
     * Therefore once a proposals is written to ZK, we cannot revoke it. So the actual behavior we are using is to
     * set the task state to:
     * 1. IN_PROGRESS: when the execution is stopped by the users. i.e. do nothing but let the task finish normally.
     * 2. DEAD: when the destination broker is dead. i.e. do not block on the execution.
     *
     * @param cluster the kafka cluster
     * @param task the task to check
     * @return true if the task is marked as dead or aborting, false otherwise.
     */
    private boolean maybeMarkTaskAsDeadOrAborting(Cluster cluster, ExecutionTask task) {
      // Only check tasks with IN_PROGRESS or ABORTING state.
      if (task.state() == IN_PROGRESS || task.state() == ABORTING) {
        switch (task.type()) {
          case LEADER_ACTION:
            if (cluster.nodeById(task.proposal().newLeader()) == null) {
              _executionTaskManager.markTaskDead(task);
              LOG.warn("Killing execution for task {} because the target leader is down", task);
              return true;
            } else if (_time.milliseconds() > task.startTime() + LEADER_ACTION_TIMEOUT_MS) {
              _executionTaskManager.markTaskDead(task);
              LOG.warn("Failed task {} because it took longer than {} to finish.", task, LEADER_ACTION_TIMEOUT_MS);
              return true;
            }
            break;

          case REPLICA_ACTION:
            for (int broker : task.proposal().newReplicas()) {
              if (cluster.nodeById(broker) == null) {
                _executionTaskManager.markTaskDead(task);
                LOG.warn("Killing execution for task {} because the new replica {} is down.", task, broker);
                return true;
              }
            }
            break;

          default:
            throw new IllegalStateException("Unknown task type " + task.type());

        }
      }
      return false;
    }

    /**
     * Due to the race condition between the controller and Cruise Control, some of the submitted tasks may be
     * deleted by controller without being executed. We will resubmit those tasks in that case.
     */
    private void maybeReexecuteTasks() {
      List<ExecutionTask> replicaActionsToReexecute =
          new ArrayList<>(_executionTaskManager.inExecutionTasks(ExecutionTask.TaskType.REPLICA_ACTION));
      if (replicaActionsToReexecute.size() > ExecutorUtils.partitionsBeingReassigned(_kafkaZkClient).size()) {
        LOG.info("Reexecuting tasks {}", replicaActionsToReexecute);
        ExecutorUtils.executeReplicaReassignmentTasks(_kafkaZkClient, replicaActionsToReexecute);
      }

      // Only reexecute leader actions if there is no replica actions running.
      if (replicaActionsToReexecute.isEmpty() && ExecutorUtils.ongoingLeaderElection(_kafkaZkClient).isEmpty()) {
        List<ExecutionTask> leaderActionsToReexecute =
            new ArrayList<>(_executionTaskManager.inExecutionTasks(ExecutionTask.TaskType.LEADER_ACTION));
        if (!leaderActionsToReexecute.isEmpty()) {
          LOG.info("Reexecuting tasks {}", leaderActionsToReexecute);
          ExecutorUtils.executePreferredLeaderElection(_kafkaZkClient, leaderActionsToReexecute);
        }
      }
    }
  }
}
