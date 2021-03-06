package eu.europeana.harvester.client;

import com.mongodb.WriteConcern;

/**
 * Contains different configuration values:<br/>
 *      - writeConcern :describes the guarantee that MongoDB provides when reporting on the success of a write operation
 */
public class HarvesterClientConfig {

    /**
     * Write concern describes the guarantee that MongoDB provides when reporting on the success of a write operation.
     */
    private final WriteConcern writeConcern;

    public HarvesterClientConfig() {
        this.writeConcern = WriteConcern.ACKNOWLEDGED;
    }

    public HarvesterClientConfig(WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
    }

    public WriteConcern getWriteConcern() {
        return writeConcern;
    }
}
