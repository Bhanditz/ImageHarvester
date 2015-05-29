package eu.europeana.harvester.cluster.slave;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import eu.europeana.harvester.cluster.domain.messages.DoneDownload;
import eu.europeana.harvester.cluster.domain.messages.DoneProcessing;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrlWithProcessingConfig;
import eu.europeana.harvester.cluster.slave.downloading.SlaveDownloader;
import eu.europeana.harvester.cluster.slave.downloading.SlaveLinkChecker;
import eu.europeana.harvester.cluster.slave.processing.SlaveProcessor;
import eu.europeana.harvester.cluster.slave.processing.color.ColorExtractor;
import eu.europeana.harvester.cluster.slave.processing.metainfo.MediaMetaInfoExtractor;
import eu.europeana.harvester.cluster.slave.processing.thumbnail.ThumbnailGenerator;
import eu.europeana.harvester.db.MediaStorageClient;
import eu.europeana.harvester.db.filesystem.FileSystemMediaStorageClientImpl;
import eu.europeana.harvester.domain.*;
import eu.europeana.harvester.httpclient.HttpRetrieveConfig;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponse;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponseFactory;
import eu.europeana.harvester.httpclient.response.ResponseType;
import org.apache.commons.io.FileUtils;
import org.joda.time.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class RetrieveAndProcessActorTests {

    private static String PROCESSING_PATH_PREFIX = Paths.get("harvester-server/src/test/resources/processing").toAbsolutePath().toString() + "/";
    private static final String pathOnDisk = PROCESSING_PATH_PREFIX + "current_image1.jpeg";
    private static final String text1GitHubUrl = "https://raw.githubusercontent.com/europeana/ImageHarvester/master/harvester-server/src/test/resources/image1.jpeg";

    private static final String PATH_PREFIX = Paths.get("harvester-server/src/test/resources/").toAbsolutePath().toString() + "/" ;
    private static final String PATH_COLORMAP = PATH_PREFIX + "colormap.png";

    private static final String FILESYSTEM_PATH_PREFIX = Paths.get("harvester-server/src/test/resources/filesystem").toAbsolutePath().toString() + "/";

    private static final MediaStorageClient client = new FileSystemMediaStorageClientImpl(FILESYSTEM_PATH_PREFIX);
    final HttpRetrieveResponseFactory httpRetrieveResponseFactory = new HttpRetrieveResponseFactory();

    static ActorSystem system;

    private static SlaveDownloader slaveDownloader = new SlaveDownloader(org.apache.logging.log4j.LogManager.getLogger(SlaveDownloader.class.getName()));
    private static SlaveLinkChecker slaveLinkChecker = new SlaveLinkChecker(org.apache.logging.log4j.LogManager.getLogger(SlaveLinkChecker.class.getName()));
    private static SlaveProcessor slaveProcessor = new SlaveProcessor(new MediaMetaInfoExtractor(PATH_COLORMAP), new ThumbnailGenerator(PATH_COLORMAP), new ColorExtractor(PATH_COLORMAP), client,
            null);

    private static MetricRegistry metrics = new MetricRegistry();

    @BeforeClass
    public static void setup() throws IOException {
        FileUtils.forceMkdir(new File(FILESYSTEM_PATH_PREFIX));
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() throws IOException {
        system.shutdown();
        FileUtils.deleteDirectory(new File(FILESYSTEM_PATH_PREFIX));
    }

    @Test
    public void canRetreievAndProcessTypicalJob() throws Exception {

        final HttpRetrieveResponse response = httpRetrieveResponseFactory.create(ResponseType.DISK_STORAGE, pathOnDisk);
        final HttpRetrieveConfig httpRetrieveConfig = new HttpRetrieveConfig(
                Duration.millis(0),
                0l,
                0l,
                5*1000l, /* terminationThresholdReadPerSecondInBytes */
                Duration.standardSeconds(10) /* terminationThresholdTimeLimit */,
                DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD, /* taskType */
                (int)Duration.standardSeconds(10).getMillis() /* connectionTimeoutInMillis */,
                10 /* maxNrOfRedirects */
        );

        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask smallThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(180,180)));
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                smallThumbnailExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl("id-1", text1GitHubUrl, httpRetrieveConfig, "jobid-1",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null);

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+"/"+task.getId(),"Testing actor");
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{
            final Props props = Props.create(RetrieveAndProcessActor.class,
                                    httpRetrieveResponseFactory, slaveDownloader, slaveLinkChecker, slaveProcessor,
                                     metrics);

            final ActorRef subject = system.actorOf(props);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneDownload msg1 = (DoneDownload)expectMsgAnyClassOf(DoneDownload.class);
            assertEquals(msg1.getDocumentReferenceTask().getTaskType(),DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD);

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = (DoneProcessing)expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getWidth().intValue(),2500);
            assertEquals(msg2.getImageMetaInfo().getHeight().intValue(),1737);
            assertEquals(msg2.getImageMetaInfo().getMimeType(),"image/jpeg");

        }};
    }


}