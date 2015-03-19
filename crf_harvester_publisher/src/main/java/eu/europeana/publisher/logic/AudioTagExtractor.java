package eu.europeana.publisher.logic;

import eu.europeana.harvester.domain.AudioMetaInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the pure tags from an audio resource and generates the fake tags.
 */
public class AudioTagExtractor {

    private static Integer getQualityCode(Integer bitDepth, Integer sampleRate, String fileFormat) {
        if(bitDepth != null && sampleRate != null && bitDepth >= 16 && sampleRate >= 44100) {
            return 1;
        }

        if(fileFormat != null && (fileFormat.equalsIgnoreCase("alac") || fileFormat.equalsIgnoreCase("flac") ||
                fileFormat.equalsIgnoreCase("ape") || fileFormat.equalsIgnoreCase("shn") ||
                fileFormat.equalsIgnoreCase("wav") || fileFormat.equalsIgnoreCase("wma") ||
                fileFormat.equalsIgnoreCase("aiff") || fileFormat.equalsIgnoreCase("dsd"))) {
            return 1;
        }

        return 0;
    }

    private static Integer getDurationCode(Long duration) {
        if(duration == null) {
            return 0;
        }
        final Long temp = duration/60000;
        if(temp <= 0.5) {
            return 1;
        }
        if(temp <= 3) {
            return 2;
        }
        if(temp <= 6) {
            return 3;
        }

        return 4;
    }

    /**
     * Generates the filter/fake tags
     * @param audioMetaInfo the meta info object
     * @param mimeTypeCode the mimetype of the resource
     * @return the list of fake tags
     */
    public static List<Integer> getFilterTags(final AudioMetaInfo audioMetaInfo, Integer mimeTypeCode) {
        final List<Integer> filterTags = new ArrayList<>();
        final Integer mediaTypeCode = 2;

        if(audioMetaInfo.getMimeType() != null) {
            mimeTypeCode = CommonTagExtractor.getMimeTypeCode(audioMetaInfo.getMimeType());
        }
        final Integer qualityCode = getQualityCode(audioMetaInfo.getBitDepth(), audioMetaInfo.getSampleRate(), audioMetaInfo.getFileFormat());
        final Integer durationCode = getDurationCode(audioMetaInfo.getDuration());

        final Set<Integer> mimeTypeCodes = new HashSet<>();
        mimeTypeCodes.add(mimeTypeCode);
        mimeTypeCodes.add(0);

        final Set<Integer> qualityCodes = new HashSet<>();
        qualityCodes.add(qualityCode);
        qualityCodes.add(0);

        final Set<Integer> durationCodes = new HashSet<>();
        durationCodes.add(durationCode);
        durationCodes.add(0);

        for (Integer mimeType : mimeTypeCodes) {
            for (Integer quality : qualityCodes) {
                for (Integer duration : durationCodes) {
                    final Integer result = mediaTypeCode<<25 | mimeType<<15 | quality<<13 | duration<<10;

                    filterTags.add(result);

//                  System.out.println(result);
//                  System.out.println(mediaTypeCode + " " + mimeType + " " + fileSize + " " + colorSpace + " " + aspectRatio + " " + color);
//                  System.out.println(Integer.toBinaryString(result));
                }
            }
        }

        return filterTags;
    }

    /**
     * Generates the list of facet tags.
     * @param audioMetaInfo the meta info object
     * @param mimeTypeCode the mimetype of the resource
     * @return the list of facet tags
     */
    public static List<Integer> getFacetTags(final AudioMetaInfo audioMetaInfo, Integer mimeTypeCode) {
        final List<Integer> facetTags = new ArrayList<>();

        final Integer mediaTypeCode = 2;

        Integer facetTag;

        if(audioMetaInfo.getMimeType() != null) {
            mimeTypeCode = CommonTagExtractor.getMimeTypeCode(audioMetaInfo.getMimeType());
            facetTag = mediaTypeCode<<25 | mimeTypeCode<<15;
            facetTags.add(facetTag);
        }

        final Integer qualityCode = getQualityCode(audioMetaInfo.getBitDepth(), audioMetaInfo.getSampleRate(), audioMetaInfo.getFileFormat());
        facetTag = mediaTypeCode<<25 | qualityCode<<13;
        facetTags.add(facetTag);

        final Integer durationCode = getDurationCode(audioMetaInfo.getDuration());
        facetTag = mediaTypeCode<<25 | durationCode<<10;
        facetTags.add(facetTag);

        return facetTags;
    }

}
