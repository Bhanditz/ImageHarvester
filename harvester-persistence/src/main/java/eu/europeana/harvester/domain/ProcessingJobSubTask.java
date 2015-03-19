package eu.europeana.harvester.domain;

import java.io.Serializable;

/**
 * The wrapping class of a subtask
 */
public class ProcessingJobSubTask implements Serializable {

    /**
     * The type of the subtask. (COLOR_EXTRACTION, META_EXTRACTION or GENERATE_THUMBNAIL)
     */
    private ProcessingJobSubTaskType taskType;

    /**
     * The configuration object needed by the specified subtask.
     */
    private GenericSubTaskConfiguration config;

    public ProcessingJobSubTask(ProcessingJobSubTaskType taskType, GenericSubTaskConfiguration config) {
        this.taskType = taskType;
        this.config = config;
    }

    public ProcessingJobSubTask() {}

    public ProcessingJobSubTaskType getTaskType() {
        return taskType;
    }

    public GenericSubTaskConfiguration getConfig() {
        return config;
    }
}
