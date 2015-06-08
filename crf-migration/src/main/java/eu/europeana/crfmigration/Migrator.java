package eu.europeana.crfmigration;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import eu.europeana.crfmigration.dao.MigratorEuropeanaDao;
import eu.europeana.crfmigration.dao.MigratorHarvesterDao;
import eu.europeana.crfmigration.domain.GraphiteReporterConfig;
import eu.europeana.crfmigration.domain.MigratorConfig;
import eu.europeana.crfmigration.domain.MongoConfig;
import eu.europeana.crfmigration.logic.MigrationManager;
import eu.europeana.crfmigration.logic.MigrationMetrics;
import org.apache.logging.log4j.LogManager;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.format.ISODateTimeFormat;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.concurrent.TimeUnit;


public class Migrator {
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(MigrationManager.class.getName());
    private final Date dateFilter;

    public Migrator(Date dateFilter) {
        this.dateFilter = dateFilter;
    }

    public void start() throws IOException {
        LOG.info("Migrator starting ");

        LOG.info("Date to filter: " + dateFilter);

        final String configFilePath = "migration.conf";
        final File configFile = new File(configFilePath);

        if (!configFile.canRead()) {
            throw new IOException("Cannot read file " + configFilePath);
        }

        final Config config = ConfigFactory.parseFileAnySyntax(configFile,
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF));

        final String sourceHost = config.getString("sourceMongo.host");
        final Integer sourcePort = config.getInt("sourceMongo.port");
        final String sourceDBName = config.getString("sourceMongo.dbName");
        final String sourceDBUsername = config.getString("sourceMongo.username");
        final String sourceDBPassword = config.getString("sourceMongo.password");

        final String targetHost = config.getString("targetMongo.host");
        final Integer targetPort = config.getInt("targetMongo.port");
        final String targetDBName = config.getString("targetMongo.dbName");
        final String targetDBUsername = config.getString("targetMongo.username");
        final String targetDBPassword = config.getString("targetMongo.password");

        final String graphiteMasterId = config.getString("metrics.masterID");
        final String graphiteServer = config.getString("metrics.graphiteServer");
        final Integer graphitePort = config.getInt("metrics.graphitePort");

        final int batch = config.getInt("config.batch");

        final MongoConfig sourceMongoConfig = new MongoConfig(sourceHost, sourcePort, sourceDBName, sourceDBUsername, sourceDBPassword);
        final MongoConfig targetMongoConfig = new MongoConfig(targetHost, targetPort, targetDBName, targetDBUsername, targetDBPassword);

        final GraphiteReporterConfig graphiteReporterConfig = new GraphiteReporterConfig(graphiteServer, graphiteMasterId, graphitePort);

        final MigratorConfig migrationConfig = new MigratorConfig(sourceMongoConfig, targetMongoConfig, graphiteReporterConfig, batch);

        // Prepare the graphite reporter
        final Graphite graphite = new Graphite(new InetSocketAddress(graphiteReporterConfig.getGraphiteServer(),
                graphiteReporterConfig.getGraphitePort()));

        GraphiteReporter graphiteReporter = GraphiteReporter.forRegistry(MigrationMetrics.METRIC_REGISTRY)
                .prefixedWith(graphiteReporterConfig.getGraphiteMasterId())
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(MetricFilter.ALL)
                .build(graphite);

        graphiteReporter.start(30, TimeUnit.SECONDS);

        // Prepare the LOG reporter
        // TODO : This is probably not correct!
        final Slf4jReporter reporter = Slf4jReporter.forRegistry(MigrationMetrics.METRIC_REGISTRY)
                .outputTo(org.slf4j.LoggerFactory.getLogger(LOG.getName()))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        reporter.start(1, TimeUnit.MINUTES);

        // Prepare the persistence
        final MigratorEuropeanaDao migratorEuropeanaDao = new MigratorEuropeanaDao(migrationConfig.getSourceMongoConfig());
        final MigratorHarvesterDao migratorHarvesterDao = new MigratorHarvesterDao(migrationConfig.getTargetMongoConfig()

        );

        // Prepare the migrator & start it
        final MigrationManager migrationManager = new MigrationManager(migratorEuropeanaDao,
                migratorHarvesterDao,
                dateFilter,
                migrationConfig.getBatch());
        migrationManager.migrate();
    }

    public void stop() {
        LOG.info("Migrator stopped ");
        System.exit(0);
    }

    public static void main(String[] args) throws IOException {
        LOG.info("Initializing migrator");

        Date dateFilter = null;

        if (1 == args.length) {
            try {
                dateFilter = ISODateTimeFormat.dateTime().parseDateTime(args[0]).toDate();
            } catch (Exception e) {
                LOG.error("The timestamp must respect the ISO 861 format! E.q: yyyy-MM-dd'T'HH:mm:ss.SSSZZ defaulting to begining of time", e);
                dateFilter = DateTime.now().minusYears(20).toDate();
            }
        } else if (args.length > 1) {
            System.out.println("Too many arguments. Please provide only one !");
            System.exit(-1);
        }

        final Migrator migrator = new Migrator(dateFilter);
        migrator.start();
    }

}
