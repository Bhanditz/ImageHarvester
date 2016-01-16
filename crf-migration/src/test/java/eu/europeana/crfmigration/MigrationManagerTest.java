package eu.europeana.crfmigration;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import eu.europeana.crfmigration.domain.MigratorConfig;
import org.joda.time.DateTime;
import org.junit.*;
import org.junit.runners.MethodSorters;
import utils.MigratorUtils;
import utils.MongoDBUtils;


import java.io.IOException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Ignore
public class MigrationManagerTest {
    private MigratorConfig migratorConfig;
    private MongoDBUtils mongoDBUtils; 

    @After
    public void tearDown () {
        mongoDBUtils.cleanMongoDatabase();
    }


    @Test
    public void testDBIteration_AllDatabase () throws IOException, ParseException, InterruptedException, ExecutionException, TimeoutException {
        migratorConfig = MigratorUtils.createMigratorConfig("config-files/allData/migration.conf");
        mongoDBUtils = new MongoDBUtils(migratorConfig);
        mongoDBUtils.cleanMongoDatabase();

        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/allData/record.json", "record");
        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/allData/aggregation.json", "Aggregation");

        MigratorUtils.runMigrator(migratorConfig, null);

        final DBCollection aggregation = mongoDBUtils.connectToSource().getCollection("Aggregation");
        final DBCollection sourceDocumentReferences = mongoDBUtils.connectToTarget()
                                                                  .getCollection("SourceDocumentReference");
        final DBCollection processJobs = mongoDBUtils.connectToTarget().getCollection("ProcessingJob");


        final int count2 = aggregation.find(new BasicDBObject("edmObject", new BasicDBObject("$exists", true))).size() +
                                   aggregation.find(new BasicDBObject("hasView", new BasicDBObject("$exists", true)))
                                              .size() +
                                   aggregation
                                           .find(new BasicDBObject("edmIsShownBy", new BasicDBObject("$exists", true)))
                                           .size() +
                                   aggregation
                                           .find(new BasicDBObject("edmIsShownAt", new BasicDBObject("$exists", true)))
                                           .size();

        assertEquals(20, sourceDocumentReferences.find().size());
        assertEquals(count2, processJobs.find().size());
    }

    @Test
    public void testDBIteration_FilterByDate_BatchSize2_AllMinus2 () throws IOException, ParseException, InterruptedException, ExecutionException, TimeoutException {
        migratorConfig = MigratorUtils.createMigratorConfig("config-files/filterSomeByDate/migration.conf");
        mongoDBUtils = new MongoDBUtils(migratorConfig.withBatchSize(2));
        mongoDBUtils.cleanMongoDatabase();

        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/filterSomeByDate/record.json", "record");
        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/filterSomeByDate/aggregation.json",
                                   "Aggregation");

        MigratorUtils.runMigrator(migratorConfig, DateTime.parse("2014-08-15T00:00:00.000Z").toDate());

        final DBCollection aggregation = mongoDBUtils.connectToSource().getCollection("Aggregation");
        final DBCollection sourceDocumentReferences = mongoDBUtils.connectToTarget()
                                                                  .getCollection("SourceDocumentReference");
        final DBCollection processJobs = mongoDBUtils.connectToTarget().getCollection("ProcessingJob");

        final BasicDBObject query = new BasicDBObject("timestampUpdated", new BasicDBObject("$lt", DateTime.parse("2014-08-15T00:00:00.000Z")
                                                                                                           .toDate()));

        aggregation.remove(query);

        assertEquals(12, sourceDocumentReferences.find().size());
        assertEquals(12, processJobs.find().size());
    }

    @Test
    public void testDBIteration_FilterByDate_BatchSize2_All () throws IOException, ParseException, InterruptedException, ExecutionException, TimeoutException {
        migratorConfig = MigratorUtils.createMigratorConfig("config-files/filterNoOneByDate/migration.conf");
        mongoDBUtils = new MongoDBUtils(migratorConfig.withBatchSize(2));
        mongoDBUtils.cleanMongoDatabase();

        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/filterNoOneByDate/record.json", "record");
        mongoDBUtils.loadMongoData(MigratorUtils.PATH_PREFIX + "data-files/filterNoOneByDate/aggregation.json",
                                   "Aggregation");

        MigratorUtils.runMigrator(migratorConfig, DateTime.parse("2014-06-19T00:00:00.000Z").toDate());


        final DBCollection aggregation = mongoDBUtils.connectToSource().getCollection("Aggregation");
        final DBCollection sourceDocumentReferences = mongoDBUtils.connectToTarget()
                                                                  .getCollection("SourceDocumentReference");
        final DBCollection processJobs = mongoDBUtils.connectToTarget().getCollection("ProcessingJob");


        final int count2 = aggregation.find(new BasicDBObject("edmObject", new BasicDBObject("$exists", true))).size() +
                                   aggregation.find(new BasicDBObject("hasView", new BasicDBObject("$exists", true)))
                                              .size() +
                                   aggregation
                                           .find(new BasicDBObject("edmIsShownBy", new BasicDBObject("$exists", true)))
                                           .size() +
                                   aggregation
                                           .find(new BasicDBObject("edmIsShownAt", new BasicDBObject("$exists", true)))
                                           .size();

        assertEquals(20, sourceDocumentReferences.find().size());
        assertEquals(count2, processJobs.find().size());
    }


}
