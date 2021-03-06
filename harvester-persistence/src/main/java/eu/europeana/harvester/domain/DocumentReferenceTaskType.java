package eu.europeana.harvester.domain;

/**
 * An enum which contains all possible types of task.
 */
public enum DocumentReferenceTaskType {
    /**
     * Only check the link.
     */
    CHECK_LINK,
    /**
     * Check the link and download it only if the content size & date response headers are different than previously.
     */
    CONDITIONAL_DOWNLOAD,
    /**
     * Download no matter what.
     */
    UNCONDITIONAL_DOWNLOAD
}
