package eu.europeana.publisher.domain;

import org.joda.time.DateTime;

public class PublisherConfig {

    /**
     * Source MongoDB host.
     */
    private final String sourceHost;

    /**
     * Source MongoDB port.
     */
    private final Integer sourcePort;

    /**
     * The source DB name where the thumbnails will be stored.
     */
    private final String sourceDBName;

    /**
     * The db username if it's needed.
     */
    private final String sourceDBUsername;

    /**
     * The db password corresponding to the given username.
     */
    private final String sourceDBPassword;

    /**
     * Target MongoDB host.
     */
    private final String targetHost;

    /**
     * Target MongoDB port.
     */
    private final Integer targetPort;

    /**
     * The target DB name where the thumbnails will be stored.
     */
    private final String targetDBName;

    /**
     * The db username if it's needed.
     */
    private final String targetDBUsername;

    /**
     * The db password corresponding to the given username.
     */
    private final String targetDBPassword;

    /**
     * The timestamp which indicates the starting time of the publisher.
     * All metadata harvested after this timestamp will be published to MongoDB and Solr.
     */
    private final DateTime startTimestamp;

    /**
     * The URL of the Solr instance.
     * e.g.: http://IP:Port/solr
     */
    private final String solrURL;

    /**
     * Batch of documents to update.
     */
    private final Integer batch;

    public PublisherConfig(final String sourceHost, final Integer sourcePort, final String sourceDBName,
                           final String sourceDBUsername, final String sourceDBPassword, final String targetHost,
                           final Integer targetPort, final String targetDBName, final String targetDBUsername,
                           final String targetDBPassword, final DateTime startTimestamp,
                           final String solrURL, final Integer batch) {
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.sourceDBName = sourceDBName;
        this.sourceDBUsername = sourceDBUsername;
        this.sourceDBPassword = sourceDBPassword;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.targetDBName = targetDBName;
        this.targetDBUsername = targetDBUsername;
        this.targetDBPassword = targetDBPassword;
        this.startTimestamp = startTimestamp;
        this.solrURL = solrURL;
        this.batch = batch;
    }

    public String getSourceHost() {
        return sourceHost;
    }

    public Integer getSourcePort() {
        return sourcePort;
    }

    public String getSourceDBName() {
        return sourceDBName;
    }

    public String getSourceDBUsername() {
        return sourceDBUsername;
    }

    public String getSourceDBPassword() {
        return sourceDBPassword;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public Integer getTargetPort() {
        return targetPort;
    }

    public String getTargetDBName() {
        return targetDBName;
    }

    public String getTargetDBUsername() {
        return targetDBUsername;
    }

    public String getTargetDBPassword() {
        return targetDBPassword;
    }

    public DateTime getStartTimestamp() {
        return startTimestamp;
    }

    public String getSolrURL() {
        return solrURL;
    }

    public Integer getBatch() {
        return batch;
    }
}
