package eu.europeana.harvester.cluster.slave;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.remote.AssociatedEvent;
import akka.remote.DisassociatedEvent;
import com.codahale.metrics.MetricRegistry;
import eu.europeana.harvester.cluster.Slave;
import eu.europeana.harvester.cluster.domain.NodeMasterConfig;
import eu.europeana.harvester.cluster.domain.messages.*;
import eu.europeana.harvester.db.MediaStorageClient;
import eu.europeana.harvester.logging.LoggingComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * This actor sends feedback for each task and supervises the node master actor, if it failes than restarts it.
 */
public class NodeSupervisor extends UntypedActor {

    public static ActorRef createActor(final ActorSystem system, final Slave slave, final ActorRef masterSender,
                                       final NodeMasterConfig nodeMasterConfig, final MediaStorageClient mediaStorageClient, MetricRegistry metrics) {
        return system.actorOf(Props.create(NodeSupervisor.class, slave, masterSender, nodeMasterConfig,
                mediaStorageClient, metrics), "nodeSupervisor");

    }

    private final Logger LOG = LoggerFactory.getLogger(this.getClass().getName());

    /**
     * Reference to the cluster master actor.
     * We need this to send request for new tasks.
     */
    private final ActorRef masterSender;

    private final Slave slave;

    /**
     * An object which contains all the config information needed by this actor to start.
     */
    private final NodeMasterConfig nodeMasterConfig;

    /**
     * A reference to the nodeMaster actor. This actor decides which slave execute which task.
     */
    private ActorRef nodeMaster;

    /**
     * A reference to the watchdog actor. This actor restarts the slave system if there is no reasonable activity.
     */
    private ActorRef watchdog;

    /**
     * This client is used to save the thumbnails in Mongo.
     */
    private final MediaStorageClient mediaStorageClient;

    /**
     * NodeSupervisor sends heartbeat messages to the slave which responds with the same message.
     * If 3 consecutive messages are missed than the slave is restarted.
     */
    private Integer missedHeartbeats;
    private int memberups;

    private final MetricRegistry metrics;

    public NodeSupervisor(final Slave slave, final ActorRef masterSender,
                          final NodeMasterConfig nodeMasterConfig, final MediaStorageClient mediaStorageClient, MetricRegistry metrics) {


        this.slave = slave;
        this.masterSender = masterSender;
        this.nodeMasterConfig = nodeMasterConfig;
        this.mediaStorageClient = mediaStorageClient;
        this.missedHeartbeats = 0;
        this.metrics = metrics;

        this.memberups = 0;
    }

    @Override
    public void preStart() throws Exception {

        LOG.debug("SLAVE - Node supervisor pre start");

        nodeMaster = NodeMasterActor.createActor(context(), masterSender,getSelf(), nodeMasterConfig, mediaStorageClient);
        watchdog = WatchdogActor.createActor(context().system(),slave);

        context().watch(nodeMaster);
        context().watch(watchdog);

        final Cluster cluster = Cluster.get(getContext().system());
        cluster.subscribe(getSelf(), ClusterEvent.initialStateAsEvents(),
                ClusterEvent.MemberEvent.class, ClusterEvent.UnreachableMember.class, AssociatedEvent.class);
        getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(10,
                TimeUnit.SECONDS), getSelf(), new SendHearbeat(), getContext().system().dispatcher(), getSelf());

    }

    @Override
    public void onReceive(Object message) throws Exception {

        LOG.debug("SLAVE - Node supervisor on receive");

        if (message instanceof BagOfTasks) {
            final BagOfTasks m = (BagOfTasks)message;
            SlaveMetrics.Worker.Master.jobsReceivedCounter.inc(m.getTasks().size());
            onBagOfTasksReceived((BagOfTasks) message);
            return;
        }
        if (message instanceof Terminated) {
            onTerminatedReceived((Terminated) message);
            return;
        }
        if (message instanceof SendHearbeat) {
            onSendHeartBeatReceived(message);
            return;
        }
        if (message instanceof SlaveHeartbeat) {
            onSlaveHeartBeatReceived();
            return;
        }
        if (message instanceof DisassociatedEvent) {
            onDissasociatedEventReceived((DisassociatedEvent) message);
            return;
        }

        if (message instanceof AssociatedEvent) {
            onAssociatedEventReceived();
            return;
        }

        if (message instanceof ClusterEvent.MemberUp) {
            onMemberUpReceived((ClusterEvent.MemberUp) message);
            return;
        }

        if (message instanceof ClusterEvent.UnreachableMember) {
            onUnreachableMember((ClusterEvent.UnreachableMember) message);
            return;
        }

        // Anything else
        nodeMaster.tell(message, getSender());
    }

    private void onMemberUpReceived(ClusterEvent.MemberUp message) {

        LOG.debug("SLAVE - Node supervisor onMemberUpReceived memberups: {}", memberups);

        ClusterEvent.MemberUp mUp = message;

        memberups++;
        if (memberups == 2)
            nodeMaster.tell(new RequestTasks(), getSelf());
    }

    private void onAssociatedEventReceived() {
    }

    private void onDissasociatedEventReceived(DisassociatedEvent message) throws Exception {
        final DisassociatedEvent disassociatedEvent = message;


    }


    private void onUnreachableMember(ClusterEvent.UnreachableMember message){
        // if it's the master, restart

        LOG.debug("SLAVE - Node supervisor onUnreachableMember");

        if (message.member().getRoles().contains("clusterMaster")) {
            try {
                Thread.sleep(300000);

            } catch (InterruptedException e) {
                LOG.error(LoggingComponent.appendAppFields(LoggingComponent.Slave.SUPERVISOR),
                        "Master {} unreachable. Interrupted while waiting 30 secs.", e);
            }

            slave.restart();
        }
    }

    private void onSlaveHeartBeatReceived() {
        missedHeartbeats = 0;
    }

    private void onSendHeartBeatReceived(Object message) {
        // for safety, send a job request

        LOG.debug("SLAVE - Node supervisor onSendHeartBeatReceived, missedheartbeats: {}", missedHeartbeats);

        nodeMaster.tell(new RequestTasks(), getSelf());

        if (missedHeartbeats >= 3) {
            LOG.error(LoggingComponent.appendAppFields(LoggingComponent.Slave.SUPERVISOR),
                    "Slave hasn't responded to the heartbeat 3 consecutive times. It will be restarted.");
            missedHeartbeats = 0;

            getContext().system().stop(nodeMaster);
            restartNodeMaster();
        }
        nodeMaster.tell(message, getSelf());
        missedHeartbeats += 1;

        getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(3,
                TimeUnit.MINUTES), getSelf(), new SendHearbeat(), getContext().system().dispatcher(), getSelf());
    }

    private void onTerminatedReceived(Terminated message) {

        LOG.debug("SLAVE - Node supervisor onTerminatedReceived");

        final Terminated t = message;
        if (t.getActor() == nodeMaster) {
            restartNodeMaster();
        }
    }

    private void onBagOfTasksReceived(BagOfTasks message) {
        final BagOfTasks bagOfTasks = message;

        LOG.debug("SLAVE - Node supervisor onBagOfTasksReceived, bagoftasks size: {}", bagOfTasks.getTasks().size());
        for (RetrieveUrl url : bagOfTasks.getTasks()) {
            LOG.debug("retrieve url: {} /n", url.getUrl());
        }

        for (final RetrieveUrl request : bagOfTasks.getTasks()) {

            final StartedTask startedTask = new StartedTask(request.getId());

            getSender().tell(startedTask, getSelf());
            nodeMaster.tell(new RetrieveUrlWithProcessingConfig(request, nodeMasterConfig.getPathToSave() + "/" + request.getJobId()), getSender());
        }
    }

    private void restartNodeMaster() {

        LOG.debug("SLAVE - Node supervisor restartnodemaster");

        nodeMaster = NodeMasterActor.createActor(context(), masterSender, getSelf(),
                nodeMasterConfig,
                mediaStorageClient);
        context().watch(nodeMaster);
    }
}
