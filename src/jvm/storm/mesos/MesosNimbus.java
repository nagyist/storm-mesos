package storm.mesos;

import backtype.storm.Config;
import backtype.storm.scheduler.*;
import backtype.storm.utils.LocalState;
import com.google.protobuf.ByteString;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.log4j.Logger;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos.*;
import org.apache.mesos.Protos.CommandInfo.URI;
import org.apache.mesos.Protos.Value.Range;
import org.apache.mesos.Protos.Value.Ranges;
import org.apache.mesos.Protos.Value.Scalar;
import org.apache.mesos.Protos.Value.Type;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.json.simple.JSONValue;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class MesosNimbus implements INimbus {
  public static final String CONF_EXECUTOR_URI = "mesos.executor.uri";
  public static final String CONF_MASTER_URL = "mesos.master.url";
  public static final String CONF_MASTER_FAILOVER_TIMEOUT_SECS = "mesos.master.failover.timeout.secs";
  public static final String CONF_MESOS_ALLOWED_HOSTS = "mesos.allowed.hosts";
  public static final String CONF_MESOS_DISALLOWED_HOSTS = "mesos.disallowed.hosts";
  public static final String CONF_MESOS_ROLE = "mesos.framework.role";
  public static final String CONF_MESOS_CHECKPOINT = "mesos.framework.checkpoint";
  public static final String CONF_MESOS_OFFER_LRU_CACHE_SIZE = "mesos.offer.lru.cache.size";

  public static final Logger LOG = Logger.getLogger(MesosNimbus.class);

  private static final String FRAMEWORK_ID = "FRAMEWORK_ID";
  private final Object OFFERS_LOCK = new Object();
  private RotatingMap<OfferID, Offer> _offers;
  private Map<TaskID, Offer> used_offers;
  private ScheduledExecutorService timerScheduler =
    Executors.newScheduledThreadPool(1);

  LocalState _state;
  NimbusScheduler _scheduler;
  volatile SchedulerDriver _driver;
  Timer _timer = new Timer();

  @Override
  public IScheduler getForcedScheduler() {
    return null;
  }

  @Override
  public String getHostName(Map<String, SupervisorDetails> map, String nodeId) {
    return nodeId;
  }

  public class NimbusScheduler implements Scheduler {

    Semaphore _initter;

    public NimbusScheduler(Semaphore initter) {
      _initter = initter;
    }

    @Override
    public void registered(final SchedulerDriver driver, FrameworkID id, MasterInfo masterInfo) {
      _driver = driver;
      try {
        _state.put(FRAMEWORK_ID, id.getValue());
      } catch (IOException e) {
        LOG.error("Halting process...", e);
        Runtime.getRuntime().halt(1);
      }
      _offers = new storm.mesos.RotatingMap<OfferID, Offer>(
          new storm.mesos.RotatingMap.ExpiredCallback<OfferID, Offer>() {
        @Override
        public void expire(OfferID key, Offer val) {
            driver.declineOffer(val.getId());
        }
      });

      Number lruCacheSize = (Number) _conf.get(CONF_MESOS_OFFER_LRU_CACHE_SIZE);
      if (lruCacheSize == null) lruCacheSize = 1000;
      final int LRU_CACHE_SIZE = lruCacheSize.intValue();
      used_offers = Collections.synchronizedMap(new LinkedHashMap<TaskID, Offer>(LRU_CACHE_SIZE + 1, .75F, true) {
        // This method is called just after a new entry has been added
        public boolean removeEldestEntry(Map.Entry eldest) {
          return size() > LRU_CACHE_SIZE;
        }
      });

      Number offerExpired = (Number) _conf.get(Config.NIMBUS_MONITOR_FREQ_SECS);
      if (offerExpired == null) offerExpired = 60;
      _timer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          try {
            synchronized (OFFERS_LOCK) {
              _offers.rotate();
            }
          } catch (Throwable t) {
            LOG.error("Received fatal error Halting process...", t);
            Runtime.getRuntime().halt(2);
          }
        }
      }, 0, 2000 * offerExpired.intValue());
      _initter.release();
    }

    @Override
    public void reregistered(SchedulerDriver sd, MasterInfo info) {
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
    }

    @Override
    public void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
      synchronized (OFFERS_LOCK) {
        _offers.clear();
        for (Offer offer : offers) {
          if (_offers != null && isHostAccepted(offer.getHostname())) {
            _offers.put(offer.getId(), offer);
          } else {
            driver.declineOffer(offer.getId());
          }
        }
      }
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, OfferID id) {
      synchronized (OFFERS_LOCK) {
        _offers.remove(id);
      }
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
      LOG.info("Received status update: " + status.toString());
      switch (status.getState()) {
        case TASK_FINISHED:
        case TASK_FAILED:
        case TASK_KILLED:
        case TASK_LOST:
          final TaskID taskId = status.getTaskId();
          timerScheduler.schedule(new Runnable() {
            @Override
            public void run() {
              used_offers.remove(taskId);
          }}, MesosCommon.getSuicideTimeout(_conf), TimeUnit.SECONDS);
          break;
        default:
          break;
      }
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId, SlaveID slaveId, byte[] data) {
    }

    @Override
    public void slaveLost(SchedulerDriver driver, SlaveID id) {
      LOG.info("Lost slave: " + id.toString());
    }

    @Override
    public void error(SchedulerDriver driver, String msg) {
      LOG.error("Received fatal error \nmsg:" + msg + "\nHalting process...");
      Runtime.getRuntime().halt(2);
    }

    @Override
    public void executorLost(SchedulerDriver driver, ExecutorID executor, SlaveID slave, int status) {
      LOG.info("Executor lost: executor=" + executor + " slave=" + slave);
    }
  }

  Map _conf;
  Set<String> _allowedHosts;
  Set<String> _disallowedHosts;

  private static Set listIntoSet(List l) {
    if (l == null) {
      return null;
    } else return new HashSet<String>(l);
  }

  @Override
  public void prepare(Map conf, String localDir) {
    try {
      _conf = conf;
      _state = new LocalState(localDir);
      String id = (String) _state.get(FRAMEWORK_ID);

      _allowedHosts = listIntoSet((List) _conf.get(CONF_MESOS_ALLOWED_HOSTS));
      _disallowedHosts = listIntoSet((List) _conf.get(CONF_MESOS_DISALLOWED_HOSTS));

      Semaphore initter = new Semaphore(0);
      _scheduler = new NimbusScheduler(initter);
      Number failoverTimeout = (Number) conf.get(CONF_MASTER_FAILOVER_TIMEOUT_SECS);
      if (failoverTimeout == null) failoverTimeout = 3600;

      String role = (String) conf.get(CONF_MESOS_ROLE);
      if (role == null) role = new String("*");
      Boolean checkpoint = (Boolean) conf.get(CONF_MESOS_CHECKPOINT);
      if (checkpoint == null) checkpoint = new Boolean(false);

      FrameworkInfo.Builder finfo = FrameworkInfo.newBuilder()
          .setName("Storm!!!")
          .setFailoverTimeout(failoverTimeout.doubleValue())
          .setUser("")
          .setRole(role)
          .setCheckpoint(checkpoint);
      if (id != null) {
        finfo.setId(FrameworkID.newBuilder().setValue(id).build());
      }


      MesosSchedulerDriver driver =
          new MesosSchedulerDriver(
              _scheduler,
              finfo.build(),
              (String) conf.get(CONF_MASTER_URL));

      driver.start();
      LOG.info("Waiting for scheduler to initialize...");
      initter.acquire();
      LOG.info("Scheduler initialized...");
    } catch (IOException|InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static class OfferResources {
    int cpuSlots = 0;
    int memSlots = 0;
    List<Integer> ports = new ArrayList<Integer>();

    @Override
    public String toString() {
      return ToStringBuilder.reflectionToString(this);
    }
  }

  private OfferResources getResources(Offer offer, double cpu, double mem) {
    OfferResources resources = new OfferResources();

    double offerCpu = 0;
    double offerMem = 0;

    for (Resource r : offer.getResourcesList()) {
      if (r.getName().equals("cpus") && r.getScalar().getValue() > offerCpu) {
        offerCpu = r.getScalar().getValue();
        resources.cpuSlots = (int) Math.floor(offerCpu / cpu);
      } else if (r.getName().equals("mem") && r.getScalar().getValue() > offerMem) {
        offerMem = r.getScalar().getValue();
        resources.memSlots = (int) Math.floor(offerMem / mem);
      }
    }

    int maxPorts = Math.min(resources.cpuSlots, resources.memSlots);

    for (Resource r : offer.getResourcesList()) {
      if (r.getName().equals("ports")) {
        for (Range range : r.getRanges().getRangeList()) {
          if (resources.ports.size() >= maxPorts) {
            break;
          } else {
            int start = (int) range.getBegin();
            int end = (int) range.getEnd();
            for (int p = start; p <= end; p++) {
              resources.ports.add(p);
              if (resources.ports.size() >= maxPorts) {
                break;
              }
            }
          }
        }
      }
    }

    LOG.debug("Offer: " + offer.toString());
    LOG.debug("Extracted resources: " + resources.toString());
    return resources;
  }

  private List<WorkerSlot> toSlots(Offer offer, double cpu, double mem, final Set<String> existingWorkers) {
    OfferResources resources = getResources(offer, cpu, mem);

    List<WorkerSlot> ret = new ArrayList<WorkerSlot>();
    int availableSlots = Math.min(resources.cpuSlots, resources.memSlots);
    availableSlots = Math.min(availableSlots, resources.ports.size());
    for (int i = 0; i < availableSlots; i++) {
      if (!existingWorkers.contains(MesosCommon.taskId(offer.getHostname(), resources.ports.get(0)))) {
        ret.add(new WorkerSlot(offer.getHostname(), resources.ports.get(0)));
      }
    }
    return ret;
  }


  public boolean isHostAccepted(String hostname) {
    return
        (_allowedHosts == null && _disallowedHosts == null) ||
        (_allowedHosts != null && _allowedHosts.contains(hostname)) ||
        (_disallowedHosts != null && !_disallowedHosts.contains(hostname))
        ;
  }

  @Override
  public Collection<WorkerSlot> allSlotsAvailableForScheduling(
      Collection<SupervisorDetails> existingSupervisors, Topologies topologies, Set<String> topologiesMissingAssignments) {
    synchronized (OFFERS_LOCK) {
      LOG.info("Currently have " + _offers.size() + " offers buffered");
      if (!topologiesMissingAssignments.isEmpty()) {
        LOG.info("Topologies that need assignments: " + topologiesMissingAssignments.toString());
      } else {
        LOG.info("Declining offers because no topologies need assignments");
        _offers.clear();
      }
    }

    Double cpu = null;
    Double mem = null;
    // TODO: maybe this isn't the best approach. if a topology raises #cpus keeps failing,
    // it will mess up scheduling on this cluster permanently
    for (String id : topologiesMissingAssignments) {
      TopologyDetails details = topologies.getById(id);
      double tcpu = MesosCommon.topologyCpu(_conf, details);
      double tmem = MesosCommon.topologyMem(_conf, details);
      if (cpu == null || tcpu > cpu) {
        cpu = tcpu;
      }
      if (mem == null || tmem > mem) {
        mem = tmem;
      }
    }

    // need access to how many slots are currently used to limit number of slots taken up

    List<WorkerSlot> allSlots = new ArrayList<WorkerSlot>();
    Set<String> existingWorkers = new HashSet<String>();
    for (SupervisorDetails supervisor : existingSupervisors) {
      final String host = supervisor.getHost();
      for (Integer port : supervisor.getAllPorts()) {
        existingWorkers.add(MesosCommon.taskId(host, port));
      }
    }


    if (cpu != null && mem != null) {
      synchronized (OFFERS_LOCK) {
        for (Offer offer : _offers.newestValues()) {
          allSlots.addAll(toSlots(offer, cpu, mem, existingWorkers));
        }
      }
    }


    LOG.info("Number of available slots: " + allSlots.size());
    return allSlots;
  }

  private OfferID findOffer(WorkerSlot worker) {
    int port = worker.getPort();
    ArrayList<Offer> offers = new ArrayList<Offer>(_offers.values());
    Collections.sort(offers, new Comparator<Offer>() {
      public int compare(Offer lhs, Offer rhs) {
        if (lhs.getSlaveLoadHint() > rhs.getSlaveLoadHint()) {
          return 1;
        } else if (lhs.getSlaveLoadHint() < rhs.getSlaveLoadHint()) {
          return -1;
        }
        return 0;
      }
    });
    for (Offer offer : offers) {
      if (offer.getHostname().equals(worker.getNodeId())) {
        for (Resource r : offer.getResourcesList()) {
          if (r.getName().equals("ports")) {
            for (Range range : r.getRanges().getRangeList()) {
              if (port >= range.getBegin() && port <= range.getEnd()) {
                return offer.getId();
              }
            }
          }
        }
      }
    }
    // Still haven't found the slot? Maybe it's an offer we already used.
    return null;
  }

  @Override
  public void assignSlots(Topologies topologies, Map<String, Collection<WorkerSlot>> slots) {
    synchronized (OFFERS_LOCK) {
      Map<OfferID, List<TaskInfo>> toLaunch = new HashMap();
      for (String topologyId : slots.keySet()) {
        for (WorkerSlot slot : slots.get(topologyId)) {
          OfferID id = null;
          Offer offer = null;
          if (used_offers.containsKey(MesosCommon.taskId(slot.getNodeId(), slot.getPort()))) {
            offer = used_offers.get(MesosCommon.taskId(slot.getNodeId(), slot.getPort()));
            id = offer.getId();
          } else {
            id = findOffer(slot);
            offer = _offers.get(id);
          }
          if (id != null) {
            if (!toLaunch.containsKey(id)) {
              toLaunch.put(id, new ArrayList());
            }
            TopologyDetails details = topologies.getById(topologyId);
            double cpu = MesosCommon.topologyCpu(_conf, details);
            double mem = MesosCommon.topologyMem(_conf, details);

            Map executorData = new HashMap();
            executorData.put(MesosCommon.SUPERVISOR_ID, slot.getNodeId() + "-" + details.getId());
            executorData.put(MesosCommon.ASSIGNMENT_ID, slot.getNodeId());

            // Determine roles for cpu, mem, ports
            String cpuRole = new String("*");
            String memRole = cpuRole;
            String portsRole = cpuRole;
            for (Resource r : offer.getResourcesList()) {
              if (r.getName().equals("cpus") && r.getScalar().getValue() >= cpu) {
                cpuRole = r.getRole();
              } else if (r.getName().equals("mem") && r.getScalar().getValue() >= mem) {
                memRole = r.getRole();
              } else if (r.getName().equals("ports") && r.getScalar().getValue() >= mem) {
                for (Range range : r.getRanges().getRangeList()) {
                  if (slot.getPort() >= range.getBegin() && slot.getPort() <= range.getEnd()) {
                    portsRole = r.getRole();
                    break;
                  }
                }
              }
            }

            String executorDataStr = JSONValue.toJSONString(executorData);
            LOG.info("Launching task with executor data: <" + executorDataStr + ">");
            TaskInfo task = TaskInfo.newBuilder()
                .setName("worker " + slot.getNodeId() + ":" + slot.getPort())
                .setTaskId(TaskID.newBuilder()
                    .setValue(MesosCommon.taskId(slot.getNodeId(), slot.getPort())))
                .setSlaveId(offer.getSlaveId())
                .setExecutor(ExecutorInfo.newBuilder()
                    .setExecutorId(ExecutorID.newBuilder().setValue(details.getId()))
                    .setData(ByteString.copyFromUtf8(executorDataStr))
                    .setCommand(CommandInfo.newBuilder()
                        .addUris(URI.newBuilder().setValue((String) _conf.get(CONF_EXECUTOR_URI)))
                        .setValue("cd storm-mesos* && python bin/storm supervisor storm.mesos.MesosSupervisor")
                    ))
                .addResources(Resource.newBuilder()
                    .setName("cpus")
                    .setType(Type.SCALAR)
                    .setScalar(Scalar.newBuilder().setValue(cpu))
                    .setRole(cpuRole))
                .addResources(Resource.newBuilder()
                    .setName("mem")
                    .setType(Type.SCALAR)
                    .setScalar(Scalar.newBuilder().setValue(mem))
                    .setRole(memRole))
                .addResources(Resource.newBuilder()
                    .setName("ports")
                    .setType(Type.RANGES)
                    .setRanges(Ranges.newBuilder()
                        .addRange(Range.newBuilder()
                            .setBegin(slot.getPort())
                            .setEnd(slot.getPort())))
                    .setRole(portsRole))
                .build();
            toLaunch.get(id).add(task);
          }
        }
      }
      for (OfferID id : toLaunch.keySet()) {
        List<TaskInfo> tasks = toLaunch.get(id);

        LOG.info("Launching tasks for offer " + id.getValue() + "\n" + tasks.toString());
        _driver.launchTasks(id, tasks);
        List<TaskID> taskIds = new ArrayList<>();
        for (TaskInfo task : tasks) {
          used_offers.put(task.getTaskId(), _offers.get(id));
          taskIds.add(task.getTaskId());
        }
        _offers.remove(id);
      }
    }
  }

  public static void main(String[] args) {
    backtype.storm.daemon.nimbus.launch(new MesosNimbus());
  }
}
