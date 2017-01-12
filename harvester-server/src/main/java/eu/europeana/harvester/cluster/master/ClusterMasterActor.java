package eu.europeana.harvester.cluster.master;

import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.UnreachableMember;
import akka.remote.AssociatedEvent;
import akka.remote.DisassociatedEvent;
import com.codahale.metrics.Gauge;
import eu.europeana.harvester.cluster.domain.ClusterMasterConfig;
import eu.europeana.harvester.cluster.domain.DefaultLimits;
import eu.europeana.harvester.cluster.domain.IPExceptions;
import eu.europeana.harvester.cluster.domain.messages.*;
import eu.europeana.harvester.cluster.master.accountants.AccountantActor;
import eu.europeana.harvester.cluster.master.jobrestarter.JobRestarterActor;
import eu.europeana.harvester.cluster.master.limiter.IPLimiterAccountantActor;
import eu.europeana.harvester.cluster.master.limiter.domain.IPLimiterConfig;
import eu.europeana.harvester.cluster.master.limiter.domain.ReserveConnectionSlotRequest;
import eu.europeana.harvester.cluster.master.limiter.domain.ReturnConnectionSlotRequest;
import eu.europeana.harvester.cluster.master.loaders.JobLoaderMasterActor;
import eu.europeana.harvester.cluster.master.metrics.MasterMetrics;
import eu.europeana.harvester.cluster.master.receivers.ReceiverMasterActor;
import eu.europeana.harvester.db.interfaces.*;
import eu.europeana.harvester.logging.LoggingComponent;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ClusterMasterActor extends UntypedActor {

    private final Logger LOG = LoggerFactory.getLogger(this.getClass().getName());

    /**
     * The cluster master is split into three separate actors.
     * This reference is reference to an actor which only receives messages from slave.
     */
    private ActorRef receiverActor;

    /**
     * The cluster master is split into three separate actors.
     * This reference is reference to an actor which only loads jobs from MongoDB.
     */
    private ActorRef jobLoaderActor;

    /**
     * The cluster master is split into three separate actors.
     * This reference is reference to an actor which only sends jobs to slaves.
     */
    private ActorRef jobSenderActor;

    /**
     * A wrapper class for all important data (ips, loaded jobs, jobs in progress etc.)
     */
    private ActorRef accountantActor;

    /**
     * A wrapper class for all monitoring data
     */
    private ActorRef monitoringActor;

    /**
     *   every  X seconds computes the number of documents from Mongo which have
     *   the state ERROR, SUCCESS or READY
     */
    private ActorRef processingJobStateStatisticsActor;

    /**
     *  every X seconds get the job profiles and create new jobs
     */
    private ActorRef jobRestarterActor;

    /**
     * Contains all the configuration needed by this actor.
     */
    private final ClusterMasterConfig clusterMasterConfig;

    /**
     * An object which contains a list of IPs which has to be treated different.
     */
    private final IPExceptions ipExceptions;

    /**
     * A map with all system addresses which maps each address with a list of actor refs.
     * This is needed if we want to clean them or if we want to broadcast a message.
     */
    private final Map<Address, HashSet<ActorRef>> actorsPerAddress;

    /**
     * A map with all system addresses which maps each address with a set of tasks.
     * This is needed to restore the tasks if a system crashes.
     */
    private final Map<Address, HashSet<String>> tasksPerAddress;

    /**
     * A map with all sent but not confirmed tasks which maps these tasks with a datetime object.
     * It's needed to restore all the tasks which are not confirmed after a given period of time.
     */
    private final Map<String, DateTime> tasksPerTime;

    /**
     * ProcessingJob DAO object which lets us to read and store data to and from the database.
     */
    private final ProcessingJobDao processingJobDao;

    /**
     * ProcessingJob DAO object which lets us to read and store data to and from the database.
     */
    private final HistoricalProcessingJobDao historicalProcessingJobDao;

    /**
     * MachineResourceReference DAO object which lets us to read and store data to and from the database.
     */
    private final MachineResourceReferenceDao machineResourceReferenceDao;

    /**
     * SourceDocumentProcessingStatistics DAO object which lets us to read and store data to and from the database.
     */
    private final SourceDocumentProcessingStatisticsDao sourceDocumentProcessingStatisticsDao;

    /**
     * SourceDocumentReference DAO object which lets us to read and store data to and from the database.
     */
    private final SourceDocumentReferenceDao sourceDocumentReferenceDao;

    private final SourceDocumentReferenceProcessingProfileDao sourceDocumentProcessingProfileDao;

    /**
     * SourceDocumentReferenceMetaInfo DAO object which lets us to read and store data to and from the database.
     */
    private final SourceDocumentReferenceMetaInfoDao sourceDocumentReferenceMetaInfoDao;

    /**
     * Contains default download limits.
     */
    private final DefaultLimits defaultLimits;

    /**
     * The interval in hours when the master cleans itself and its slaves.
     */
    private final Integer cleanupInterval;

    private final Duration delayForCountingTheStateOfDocuments;

    /**
     * Maps each IP with a boolean which indicates if an IP has jobs in MongoDB or not.
     */
    private final HashMap<String, Boolean> ipsWithJobs = new HashMap<>();
    private final LastSourceDocumentProcessingStatisticsDao lastSourceDocumentProcessingStatisticsDao;

    private  ActorRef masterLimiter;

    public ClusterMasterActor (final ClusterMasterConfig clusterMasterConfig,
                               final IPExceptions ipExceptions,
                               final ProcessingJobDao processingJobDao,
                               final HistoricalProcessingJobDao historicalProcessingJobDao,
                               final MachineResourceReferenceDao machineResourceReferenceDao,
                               final SourceDocumentProcessingStatisticsDao sourceDocumentProcessingStatisticsDao,
                               final LastSourceDocumentProcessingStatisticsDao lastSourceDocumentProcessingStatisticsDao,
                               final SourceDocumentReferenceDao SourceDocumentReferenceDao,
                               final SourceDocumentReferenceMetaInfoDao sourceDocumentReferenceMetaInfoDao,
                               final SourceDocumentReferenceProcessingProfileDao sourceDocumentProcessingProfileDao,
                               final DefaultLimits defaultLimits, final Integer cleanupInterval,
                               final Duration delayForCountingTheStateOfDocuments) {

        LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                "ClusterMasterActor constructor");

        this.ipExceptions = ipExceptions;
        this.clusterMasterConfig = clusterMasterConfig;
        this.processingJobDao = processingJobDao;
        this.historicalProcessingJobDao = historicalProcessingJobDao;
        this.machineResourceReferenceDao = machineResourceReferenceDao;
        this.sourceDocumentProcessingStatisticsDao = sourceDocumentProcessingStatisticsDao;
        this.lastSourceDocumentProcessingStatisticsDao = lastSourceDocumentProcessingStatisticsDao;
        this.sourceDocumentReferenceDao = SourceDocumentReferenceDao;
        this.sourceDocumentProcessingProfileDao = sourceDocumentProcessingProfileDao;
        this.sourceDocumentReferenceMetaInfoDao = sourceDocumentReferenceMetaInfoDao;
        this.defaultLimits = defaultLimits;
        this.cleanupInterval = cleanupInterval;
        this.delayForCountingTheStateOfDocuments = delayForCountingTheStateOfDocuments;

        this.actorsPerAddress = Collections.synchronizedMap(new HashMap<Address, HashSet<ActorRef>>());
        this.tasksPerAddress = Collections.synchronizedMap(new HashMap<Address, HashSet<String>>());
        this.tasksPerTime = Collections.synchronizedMap(new HashMap<String, DateTime>());
    }

    @Override
    public void preStart() throws Exception {
        LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                "ClusterMasterActor prestart");

        monitoringActor = getContext().system().actorOf(Props.create(ClusterMasterMonitoringActor.class), "monitoring");

        accountantActor = getContext().system().actorOf(Props.create(AccountantActor.class,defaultLimits), "accountant");

        receiverActor = getContext().system().actorOf(Props.create(ReceiverMasterActor.class, clusterMasterConfig,
                accountantActor, monitoringActor, processingJobDao, historicalProcessingJobDao,
                sourceDocumentProcessingStatisticsDao,
                lastSourceDocumentProcessingStatisticsDao,
                sourceDocumentReferenceDao, sourceDocumentReferenceMetaInfoDao
        ), "receiver");
        masterLimiter = IPLimiterAccountantActor.createActor(getContext().system(), new IPLimiterConfig(defaultLimits.getDefaultMaxConcurrentConnectionsLimit(), Collections.EMPTY_MAP, defaultLimits.getMaxJobProcessingDuration()), "masterLimiter");

        jobLoaderActor = getContext().system().actorOf(Props.create(JobLoaderMasterActor.class, receiverActor,
                clusterMasterConfig, accountantActor,masterLimiter, processingJobDao,
                sourceDocumentProcessingStatisticsDao, sourceDocumentReferenceDao, machineResourceReferenceDao,
                defaultLimits, ipsWithJobs, ipExceptions), "jobLoader");

        jobRestarterActor = getContext().system().actorOf(Props.create(JobRestarterActor.class,
                                                                       clusterMasterConfig.getJobRestarterConfig(),
                                                                       sourceDocumentReferenceDao,
                                                                       processingJobDao,
                                                                       sourceDocumentProcessingProfileDao
                                                                       ),
                                                          "jobRestarter"
                                                         );



        final Cluster cluster = Cluster.get(getContext().system());
        cluster.subscribe(getSelf(), ClusterEvent.initialStateAsEvents(),
                MemberEvent.class, UnreachableMember.class, AssociatedEvent.class);

        getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(cleanupInterval,
                TimeUnit.HOURS), getSelf(), new Clean(), getContext().system().dispatcher(), getSelf());


        MasterMetrics.Master.unreachableNodesInClusterCount.registerHandler(new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return cluster.state().unreachable().size();
            }
        });

        MasterMetrics.Master.connectedNodesInClusterCount.registerHandler(new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return cluster.state().members().size();
            }
        });

        setMasterDatabaseMetrics();
    }

    private void setMasterDatabaseMetrics() {
        MasterMetrics.MasterDatabase.HistoricalProcessingJobCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = historicalProcessingJobDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.ProcessingJobCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = processingJobDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.LastSourceDocumentProcessingStatisticsCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = lastSourceDocumentProcessingStatisticsDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.MachineResourceReferenceCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = machineResourceReferenceDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.SourceDocumentProcessingStatisticsCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = sourceDocumentProcessingStatisticsDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.SourceDocumentReferenceCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = sourceDocumentReferenceDao.getCount();
                }
                return lastValueComputed;
            }
        });

        MasterMetrics.MasterDatabase.SourceDocumentReferenceMetaInfoCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = sourceDocumentReferenceMetaInfoDao.getCount();
                }
                return lastValueComputed;
            }
        });


        MasterMetrics.MasterDatabase.SourceDocumentReferenceProcessingProfileCollectionSize.registerHandler(new Gauge<Long>() {

            DateTime lastTimeComputed = DateTime.now().minusMinutes(5);;
            Long lastValueComputed = 0l;
            @Override
            public Long getValue() {
                if (lastTimeComputed.plusMinutes(1).isBefore(DateTime.now())) {
                    lastTimeComputed = DateTime.now();
                    lastValueComputed = sourceDocumentProcessingProfileDao.getCount();
                }
                return lastValueComputed;
            }
        });

    }

    @Override
    public void preRestart(Throwable reason, Option<Object> message) throws Exception {
        super.preRestart(reason, message);
        LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                "ClusterMasterActor prestart");
        //getContext().system().stop(jobSenderActor);
        getContext().system().stop(jobLoaderActor);
        getContext().system().stop(receiverActor);
        getContext().system().stop(accountantActor);
        getContext().system().stop(monitoringActor);
        getContext().system().stop(processingJobStateStatisticsActor);
        getContext().system().stop(jobRestarterActor);
    }

    @Override
    public void postRestart(Throwable reason) throws Exception {
        super.postRestart(reason);
        LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                "ClusterMasterActor poststart");

        getSelf().tell(new LoadJobs(), ActorRef.noSender());

        getSelf().tell(new Monitor(), ActorRef.noSender());

        getSelf().tell( new Clean(), ActorRef.noSender());
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if(message instanceof DoneProcessing) {
            final DoneProcessing doneProcessing = (DoneProcessing) message;
            receiverActor.tell(message, getSender());
            return ;
        }
        if(message instanceof ReserveConnectionSlotRequest) {
            masterLimiter.tell(message, getSender());
            return;
        }

        if(message instanceof ReturnConnectionSlotRequest) {
            masterLimiter.tell(message, getSender());
            return;
        }

        if(message instanceof RequestTasks) {
            accountantActor.tell(message, getSender());
            jobLoaderActor.tell(new LoadJobs(), ActorRef.noSender());
            return;
        }

        if(message instanceof LoadJobs) {
            jobLoaderActor.tell(message, ActorRef.noSender());
            return;
        }

        if(message instanceof Monitor) {
            monitor();
            accountantActor.tell(message, ActorRef.noSender());

            getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(10,
                    TimeUnit.MINUTES), getSelf(), new Monitor(), getContext().system().dispatcher(), getSelf());
            return;
        }

        if(message instanceof Clean) {
            accountantActor.tell(message, ActorRef.noSender());
            return;
        }


        // cluster events
        if (message instanceof MemberUp) {
            final MemberUp mUp = (MemberUp) message;
            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                    "Member is Up: {}", mUp.member());

            return;
        }
        if (message instanceof UnreachableMember) {
            final UnreachableMember mUnreachable = (UnreachableMember) message;
            LOG.debug(LoggingComponent.appendAppFields( LoggingComponent.Master.CLUSTER_MASTER),
                    "Member detected as Unreachable: {}", mUnreachable.member());

            return;
        }
        if (message instanceof AssociatedEvent) {
            final AssociatedEvent associatedEvent = (AssociatedEvent) message;

            LOG.debug(LoggingComponent.appendAppFields( LoggingComponent.Master.CLUSTER_MASTER),
                    "Member associated: {}", associatedEvent.remoteAddress());

            return;
        }

        if (message instanceof DisassociatedEvent) {
            final DisassociatedEvent disassociatedEvent = (DisassociatedEvent) message;
            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                    "Member disassociated: {}", disassociatedEvent.remoteAddress());

            //recoverTasks(disassociatedEvent.remoteAddress());
            return;
        }

        if (message instanceof MemberRemoved) {
            final MemberRemoved mRemoved = (MemberRemoved) message;
            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Master.CLUSTER_MASTER),
                    "Member is Removed: {}", mRemoved.member());


            return;
        }

        unhandled(message);
        return;


    }

    /**
     * ONLY FOR DEBUG
     */


    // TODO : Refactor this as it polutes the logstash index.
    private void monitor() {
        monitoringActor.tell(new Monitor(), ActorRef.noSender());
    }



}
