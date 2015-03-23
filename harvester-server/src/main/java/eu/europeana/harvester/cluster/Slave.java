package eu.europeana.harvester.cluster;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.routing.FromConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import eu.europeana.harvester.cluster.domain.NodeMasterConfig;
import eu.europeana.harvester.cluster.master.NodeSupervisor;
import eu.europeana.harvester.db.MediaStorageClient;
import eu.europeana.harvester.db.mongo.MediaStorageClientImpl;
import eu.europeana.harvester.domain.MediaStorageClientConfig;
import eu.europeana.harvester.httpclient.response.ResponseType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import java.io.File;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Slave {

    private static final Logger LOG = LogManager.getLogger(Slave.class.getName());

    private final String[] args;

    private ActorSystem system;

    public Slave(String[] args) {
        this.args = args;
    }

    public void init(Slave slave) {
        String configFilePath;

        if(args.length == 0) {
            configFilePath = "./extra-files/config-files/slave.conf";
        } else {
            configFilePath = args[0];
        }

        final File configFile = new File(configFilePath);
        if(!configFile.exists()) {
            LOG.error("Config file not found!");
            System.exit(-1);
        }

        final Config config = ConfigFactory.parseFileAnySyntax(configFile,
                        ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF));

        final ExecutorService bossPool = Executors.newCachedThreadPool();
        final ExecutorService workerPool = Executors.newCachedThreadPool();

        final ChannelFactory channelFactory = new NioClientSocketChannelFactory(bossPool, workerPool);

        final ResponseType responseType;

        if("diskStorage".equals(config.getString("slave.responseType"))) {
            responseType = ResponseType.DISK_STORAGE;
        } else {
            responseType = ResponseType.MEMORY_STORAGE;
        }

        final String pathToSave = config.getString("slave.pathToSave");
        final File dir = new File(pathToSave);
        if(!dir.exists()) {
            dir.mkdirs();
        }
        final String source = config.getString("media-storage.source");
        final String colorMapPath = config.getString("slave.colorMap");

        final Integer nrOfDownloaderSlaves = config.getInt("slave.nrOfDownloaderSlaves");
        final Integer nrOfExtractorSlaves = config.getInt("slave.nrOfExtractorSlaves");
        final Integer nrOfPingerSlaves = config.getInt("slave.nrOfPingerSlaves");
        final Integer nrOfRetries = config.getInt("slave.nrOfRetries");
        final Integer taskNrLimit = config.getInt("slave.taskNrLimit");

        final NodeMasterConfig nodeMasterConfig = new NodeMasterConfig(nrOfDownloaderSlaves, nrOfExtractorSlaves,
                nrOfPingerSlaves, nrOfRetries, taskNrLimit, pathToSave, responseType, source, colorMapPath);

        final String mediaStorageHost = config.getString("media-storage.host");
        final Integer mediaStoragePort = config.getInt("media-storage.port");
        final String mediaStorageDBName = config.getString("media-storage.dbName");
        final String mediaStorageNameSpace = config.getString("media-storage.nameSpace");
        final String mediaStorageUsername = config.getString("media-storage.username");
        final String mediaStoragePassword = config.getString("media-storage.password");

        final MediaStorageClientConfig mediaStorageClientConfig =
                new MediaStorageClientConfig(mediaStorageHost, mediaStoragePort,
                        mediaStorageUsername, mediaStoragePassword, mediaStorageDBName, mediaStorageNameSpace);

        MediaStorageClient mediaStorageClient = null;
        try {
            mediaStorageClient = new MediaStorageClientImpl(mediaStorageClientConfig);
        } catch (UnknownHostException e) {
            LOG.error("Error: connection failed to media-storage");
            System.exit(-1);
        }

        system = ActorSystem.create("ClusterSystem", config);

        final ActorRef masterSender = system.actorOf(FromConfig.getInstance().props(), "masterSender");

        system.actorOf(Props.create(NodeSupervisor.class, slave, masterSender, channelFactory, nodeMasterConfig,
                        mediaStorageClient), "nodeSupervisor");

        //system.actorOf(Props.create(MetricsListener.class), "metricsListener");
    }

    public void start() {

    }

    public void restart() {
        system.shutdown();

        this.init(this);
        this.start();
    }

    public ActorSystem getActorSystem() {
        return system;
    }

    public static void main(String[] args) {
        final Slave slave = new Slave(args);
        slave.init(slave);
        slave.start();
    }
}