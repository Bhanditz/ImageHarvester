package eu.europeana.publisher.dao;

import com.codahale.metrics.Timer;
import com.google.code.morphia.Datastore;
import com.google.code.morphia.Morphia;
import com.mongodb.*;
import eu.europeana.harvester.db.mongo.WebResourceMetaInfoDaoImpl;
import eu.europeana.harvester.domain.*;
import eu.europeana.publisher.domain.DBTargetConfig;
import eu.europeana.publisher.domain.HarvesterRecord;
import eu.europeana.publisher.logging.LoggingComponent;
import eu.europeana.publisher.logic.PublisherMetrics;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.*;

/**
 * Created by salexandru on 03.06.2015.
 */
public class PublisherHarvesterDao {
    private static final int MAX_NUMBER_OF_RETRIES = 5;
    private final WebResourceMetaInfoDaoImpl webResourceMetaInfoDao;
    private final String connectionId;

    private final DB mongoDB;
    private final org.slf4j.Logger LOG = LoggerFactory.getLogger(this.getClass().getName());

    public PublisherHarvesterDao (DBTargetConfig config) throws UnknownHostException {

        if (null == config || null == config.getMongoConfig()) {
            throw new IllegalArgumentException ("mongoConfig cannot be null");
        }

        final MongoConfig mongoConfig = config.getMongoConfig();
        this.connectionId = config.getName();
        this.mongoDB = mongoConfig.connectToDB();
        Morphia morphia = new Morphia();
        final Datastore dataStore = morphia.createDatastore(mongoConfig.connectToMongo(), mongoConfig.getDbName());
        webResourceMetaInfoDao = new WebResourceMetaInfoDaoImpl(this.mongoDB,morphia,dataStore);
    }

    public void writeMetaInfos (Collection<HarvesterRecord> records) {
        writeMetaInfos(records, "publisher-writeMetainfos-"+ Double.doubleToLongBits(Math.random() * 5000));
    }


    public void writeMetaInfos (Collection<HarvesterRecord> records, final String publishingBatchId) {
        final Timer.Context context = PublisherMetrics.Publisher.Write.Mongo.mongoWriteDocumentsDuration.time(connectionId);

        try {
            if (null == records || records.isEmpty()) {
                return;
            }

            final Map<String, WebResourceMetaInfo> webResourceMetaInfos = new HashMap<>();

            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                     "Getting metainfos."
                    );

            boolean aggregationHasToUpdate = false;
            boolean europeanaAggregationHasToUpdate = false;

            final BulkWriteOperation bulkWriteAggregation = mongoDB.getCollection("Aggregation").initializeUnorderedBulkOperation();
            final BulkWriteOperation bulkWriteEuropeanaAggregation = mongoDB.getCollection("EuropeanaAggregation").initializeUnorderedBulkOperation();

            for (final HarvesterRecord record : records) {
                for (final SourceDocumentReferenceMetaInfo metaInfo : record.getUniqueMetainfos()) {
                    webResourceMetaInfos.put(metaInfo.getId(),
                                             new WebResourceMetaInfo(metaInfo.getId(), metaInfo.getImageMetaInfo(),
                                                                     metaInfo.getAudioMetaInfo(),
                                                                     metaInfo.getVideoMetaInfo(),
                                                                     metaInfo.getTextMetaInfo())
                                            );
                }

                if (record.updateEdmObject()) {
                    final String newUrl = record.newEdmObjectUrl();
                    final String[] aggregationIds = new String[]{"/aggregation/provider" + record.getRecordId(), "/provider/aggregation" + record.getRecordId()};

                    final String[] europeanaAggregationIds = new String[]{"/aggregation/europeana" + record.getRecordId(), "/europeana/aggregation" + record.getRecordId()};


                    final BasicDBList orListAggregation = new BasicDBList();
                    final BasicDBList orListEuropeanaAggregation = new BasicDBList();

                    for (final String id : aggregationIds) {
                        orListAggregation.add(new BasicDBObject("about", id));
                        aggregationHasToUpdate = true;
                    }

                    bulkWriteAggregation.find(new BasicDBObject("$or", orListAggregation))
                                        .updateOne(new BasicDBObject("$set", new BasicDBObject("edmObject", newUrl)));

                    for (final String id : europeanaAggregationIds) {
                        orListEuropeanaAggregation.add(new BasicDBObject("about", id));

                        europeanaAggregationHasToUpdate = true;
                    }

                    bulkWriteEuropeanaAggregation.find(new BasicDBObject("$or", orListEuropeanaAggregation))
                                                 .updateOne(new BasicDBObject("$set",
                                                                              new BasicDBObject("edmPreview", newUrl)));
                }
            }

            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                     "Starting the updates."
                    );

            if (aggregationHasToUpdate) {
                final Timer.Context writeEdmObjectContext = PublisherMetrics.Publisher.Write.Mongo.writeEdmObject.time(connectionId);
                try {
                    LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                             "Updating edmObject");
                    BulkWriteResult result = bulkWriteAggregation.execute(WriteConcern.ACKNOWLEDGED);
                    LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA,
                                                              publishingBatchId), "Updated edmObject. Results: " + result.toString());
                }
                catch (Exception e) {
                    LOG.error(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                              "Updated edmObject failed. Cannot retry.", e);
                    throw e;
                }
                finally {
                   writeEdmObjectContext.close();
                }
            }

            if (europeanaAggregationHasToUpdate) {
                final Timer.Context writeEdmPreviewContext = PublisherMetrics.Publisher.Write.Mongo.writeEdmPreview.time(connectionId);
                try {
                    LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                             "Updating edmPreview");
                    BulkWriteResult result = bulkWriteEuropeanaAggregation.execute(WriteConcern.ACKNOWLEDGED);
                    LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA,
                                                              publishingBatchId), "Updated edmPreview. Results: " + result.toString());
                }
                catch (Exception e) {
                    LOG.error(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                              "Updated edmPreview failed. Cannot retry.", e);
                    throw e;
                }
                finally {
                    writeEdmPreviewContext.close();
                }
            }

            LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                     "Updating finished. Started writing meta info. #{} " + webResourceMetaInfos.size()
                    );

            final Timer.Context context_metainfo = PublisherMetrics.Publisher.Write.Mongo.mongoWriteMetaInfoDuration.time(connectionId);
            try {
                        webResourceMetaInfoDao.createOrModify(webResourceMetaInfos.values(), WriteConcern.ACKNOWLEDGED);
                        LOG.debug(LoggingComponent.appendAppFields(LoggingComponent.Migrator.PERSISTENCE_EUROPEANA, publishingBatchId),
                                 "Done updating meta {} info documents",webResourceMetaInfos.values().size()
                                );
            }
            finally {
               context_metainfo.close();
            }

            PublisherMetrics.Publisher.Write.Mongo.totalNumberOfDocumentsWritten.inc(webResourceMetaInfos.size());
            PublisherMetrics.Publisher.Write.Mongo.totalNumberOfDocumentsWrittenToOneConnection.inc(connectionId,
                                                                                                    webResourceMetaInfos.size()
                                                                                                   );
        } catch (Exception e){
            throw e;
        }
        finally {
            context.close();
        }
    }
}
