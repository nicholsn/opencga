/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.core.metadata;

import com.google.common.base.Throwables;
import com.google.common.collect.BiMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.opencb.biodata.models.variant.VariantFileMetadata;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryException;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.isNegated;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.removeNegation;

/**
 * @author Jacobo Coll <jacobo167@gmail.com>
 */
public class StudyConfigurationManager implements AutoCloseable {
    public static final String CACHED = "cached";
    public static final String READ_ONLY = "ro";
    protected static Logger logger = LoggerFactory.getLogger(StudyConfigurationManager.class);

    protected StudyConfigurationAdaptor adaptor;

    private final Map<String, StudyConfiguration> stringStudyConfigurationMap = new HashMap<>();
    private final Map<Integer, StudyConfiguration> intStudyConfigurationMap = new HashMap<>();

    public StudyConfigurationManager(StudyConfigurationAdaptor adaptor) {
        this.adaptor = adaptor;
    }

    public long lockStudy(int studyId) throws StorageEngineException {
        try {
            return lockStudy(studyId, 10000, 20000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageEngineException("Unable to lock the Study " + studyId, e);
        } catch (TimeoutException e) {
            throw new StorageEngineException("Unable to lock the Study " + studyId, e);
        }
    }

    public long lockStudy(int studyId, long lockDuration, long timeout) throws InterruptedException, TimeoutException {
        return adaptor.lockStudy(studyId, lockDuration, timeout);
    }

    public void unLockStudy(int studyId, long lockId) {
        adaptor.unLockStudy(studyId, lockId);
    }

    public interface UpdateStudyConfiguration<E extends Exception> {
        StudyConfiguration update(StudyConfiguration studyConfiguration) throws E;
    }

    public <E extends Exception> StudyConfiguration lockAndUpdate(String studyName, UpdateStudyConfiguration<E> updater)
            throws StorageEngineException, E {
        Integer studyId = getStudyId(studyName, null);
        return lockAndUpdate(studyId, updater);
    }

    public <E extends Exception> StudyConfiguration lockAndUpdate(int studyId, UpdateStudyConfiguration<E> updater)
            throws StorageEngineException, E {
        long lock = lockStudy(studyId);
        try {
            StudyConfiguration sc = getStudyConfiguration(studyId, new QueryOptions(CACHED, false)).first();

            sc = updater.update(sc);

            updateStudyConfiguration(sc, QueryOptions.empty());
            return sc;
        } finally {
            unLockStudy(studyId, lock);
        }
    }

    public final QueryResult<StudyConfiguration> getStudyConfiguration(String studyName, QueryOptions options) {
        if (StringUtils.isNumeric(studyName)) {
            return getStudyConfiguration(Integer.valueOf(studyName), options);
        }
        QueryResult<StudyConfiguration> result;
        final boolean cached = options != null && options.getBoolean(CACHED, false);
        final boolean readOnly = options != null && options.getBoolean(READ_ONLY, false);
        if (stringStudyConfigurationMap.containsKey(studyName)) {
            if (cached) {
                StudyConfiguration studyConfiguration = stringStudyConfigurationMap.get(studyName);
                if (!readOnly) {
                    studyConfiguration = studyConfiguration.newInstance();
                }
                return new QueryResult<>(studyConfiguration.getStudyName(), 0, 1, 1, "", "", Collections.singletonList(studyConfiguration));
            }
            result = adaptor.getStudyConfiguration(studyName, stringStudyConfigurationMap.get(studyName).getTimeStamp(), options);
            if (result.getNumTotalResults() == 0) { //No changes. Return old value
                StudyConfiguration studyConfiguration = stringStudyConfigurationMap.get(studyName);
                if (!readOnly) {
                    studyConfiguration = studyConfiguration.newInstance();
                }
                return new QueryResult<>(studyName, 0, 1, 1, "", "", Collections.singletonList(studyConfiguration));
            }
        } else {
            result = adaptor.getStudyConfiguration(studyName, null, options);
        }

        StudyConfiguration studyConfiguration = result.first();
        if (studyConfiguration != null) {
            intStudyConfigurationMap.put(studyConfiguration.getStudyId(), studyConfiguration);
            stringStudyConfigurationMap.put(studyConfiguration.getStudyName(), studyConfiguration);
            if (studyName != null && !studyName.equals(studyConfiguration.getStudyName())) {
                stringStudyConfigurationMap.put(studyName, studyConfiguration);
            }
            if (!readOnly) {
                result.setResult(Collections.singletonList(studyConfiguration.newInstance()));
            }
        }
        return result;

    }

    public final QueryResult<StudyConfiguration> getStudyConfiguration(int studyId, QueryOptions options) {
        QueryResult<StudyConfiguration> result;
        final boolean cached = options != null && options.getBoolean(CACHED, false);
        final boolean readOnly = options != null && options.getBoolean(READ_ONLY, false);
        if (intStudyConfigurationMap.containsKey(studyId)) {
            if (cached) {
                StudyConfiguration studyConfiguration = intStudyConfigurationMap.get(studyId);
                if (!readOnly) {
                    studyConfiguration = studyConfiguration.newInstance();
                }
                return new QueryResult<>(studyConfiguration.getStudyName(), 0, 1, 1, "", "", Collections.singletonList(studyConfiguration));
            }
            result = adaptor.getStudyConfiguration(studyId, intStudyConfigurationMap.get(studyId).getTimeStamp(), options);
            if (result.getNumTotalResults() == 0) { //No changes. Return old value
                StudyConfiguration studyConfiguration = intStudyConfigurationMap.get(studyId);
                if (!readOnly) {
                    studyConfiguration = studyConfiguration.newInstance();
                }
                return new QueryResult<>(studyConfiguration.getStudyName(), 0, 1, 1, "", "", Collections.singletonList(studyConfiguration));
            }
        } else {
            result = adaptor.getStudyConfiguration(studyId, null, options);
        }

        StudyConfiguration studyConfiguration = result.first();
        if (studyConfiguration != null) {
            intStudyConfigurationMap.put(studyConfiguration.getStudyId(), studyConfiguration);
            stringStudyConfigurationMap.put(studyConfiguration.getStudyName(), studyConfiguration);
            if (!readOnly) {
                result.setResult(Collections.singletonList(studyConfiguration.newInstance()));
            }
        }
        return result;

    }

    public Thread buildShutdownHook(String jobOperationName, int studyId, List<Integer> files) {
        return new Thread(() -> {
            try {
                logger.error("Shutdown hook!");
                atomicSetStatus(studyId, BatchFileOperation.Status.ERROR, jobOperationName, files);
            } catch (Exception e) {
                logger.error("Error terminating!", e);
                throw Throwables.propagate(e);
            }
        });
    }

    public List<String> getStudyNames(QueryOptions options) {
        return adaptor.getStudyNames(options);
    }

    public List<Integer> getStudyIds(QueryOptions options) {
        return adaptor.getStudyIds(options);
    }

    public Map<String, Integer> getStudies(QueryOptions options) {
        return adaptor.getStudies(options);
    }

    public final QueryResult updateStudyConfiguration(StudyConfiguration studyConfiguration, QueryOptions options) {
        long timeStamp = System.currentTimeMillis();
        logger.debug("Timestamp : {} -> {}", studyConfiguration.getTimeStamp(), timeStamp);
        studyConfiguration.setTimeStamp(timeStamp);
        Map<Integer, String> headers = studyConfiguration.getHeaders();

        studyConfiguration.setHeaders(null);
        logger.debug("Updating studyConfiguration : {}", studyConfiguration.toJson());
        studyConfiguration.setHeaders(headers);

        // Store a copy of the StudyConfiguration.
        StudyConfiguration copy = studyConfiguration.newInstance();
        stringStudyConfigurationMap.put(copy.getStudyName(), copy);
        intStudyConfigurationMap.put(copy.getStudyId(), copy);
        return adaptor.updateStudyConfiguration(copy, options);
    }


    /**
     * Get studyIds from a list of studies.
     * Replaces studyNames for studyIds.
     * Excludes those studies that starts with '!'
     *
     * @param studiesNames  List of study names or study ids
     * @param options       Options
     * @return              List of study Ids
     */
    public List<Integer> getStudyIds(List studiesNames, QueryOptions options) {
        return getStudyIds(studiesNames, getStudies(options));
    }

    /**
     * Get studyIds from a list of studies.
     * Replaces studyNames for studyIds.
     * Excludes those studies that starts with '!'
     *
     * @param studiesNames  List of study names or study ids
     * @param studies       Map of available studies. See {@link StudyConfigurationManager#getStudies}
     * @return              List of study Ids
     */
    public List<Integer> getStudyIds(List studiesNames, Map<String, Integer> studies) {
        List<Integer> studiesIds;
        if (studiesNames == null) {
            return Collections.emptyList();
        }
        studiesIds = new ArrayList<>(studiesNames.size());
        for (Object studyObj : studiesNames) {
            Integer studyId = getStudyId(studyObj, true, studies);
            if (studyId != null) {
                studiesIds.add(studyId);
            }
        }
        return studiesIds;
    }

    public Integer getStudyId(Object studyObj, QueryOptions options) {
        return getStudyId(studyObj, options, true);
    }

    public Integer getStudyId(Object studyObj, QueryOptions options, boolean skipNegated) {
        if (studyObj instanceof Integer) {
            return ((Integer) studyObj);
        } else if (studyObj instanceof String && StringUtils.isNumeric((String) studyObj)) {
            return Integer.parseInt((String) studyObj);
        } else {
            return getStudyId(studyObj, skipNegated, getStudies(options));
        }
    }

    public Integer getStudyId(Object studyObj, boolean skipNegated, Map<String, Integer> studies) {
        Integer studyId;
        if (studyObj instanceof Integer) {
            studyId = ((Integer) studyObj);
        } else {
            String studyName = studyObj.toString();
            if (isNegated(studyName)) { //Skip negated studies
                if (skipNegated) {
                    return null;
                } else {
                    studyName = removeNegation(studyName);
                }
            }
            if (StringUtils.isNumeric(studyName)) {
                studyId = Integer.parseInt(studyName);
            } else {
                Integer value = studies.get(studyName);
                if (value == null) {
                    throw VariantQueryException.studyNotFound(studyName, studies.keySet());
                }
                studyId = value;
            }
        }
        if (!studies.containsValue(studyId)) {
            throw VariantQueryException.studyNotFound(studyId, studies.keySet());
        }
        return studyId;
    }

    /**
     * Given a study reference (name or id) and a default study, returns the associated StudyConfiguration.
     *
     * @param study     Study reference (name or id)
     * @param defaultStudyConfiguration Default studyConfiguration
     * @param options   Query options
     * @return          Assiciated StudyConfiguration
     * @throws    VariantQueryException is the study does not exists
     */
    public StudyConfiguration getStudyConfiguration(String study, StudyConfiguration defaultStudyConfiguration, QueryOptions options)
            throws VariantQueryException {
        StudyConfiguration studyConfiguration;
        if (StringUtils.isEmpty(study)) {
            studyConfiguration = defaultStudyConfiguration;
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(study, getStudyNames(options));
            }
        } else if (StringUtils.isNumeric(study)) {
            int studyInt = Integer.parseInt(study);
            if (defaultStudyConfiguration != null && studyInt == defaultStudyConfiguration.getStudyId()) {
                studyConfiguration = defaultStudyConfiguration;
            } else {
                studyConfiguration = getStudyConfiguration(studyInt, options).first();
            }
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(studyInt, getStudyNames(options));
            }
        } else {
            if (defaultStudyConfiguration != null && defaultStudyConfiguration.getStudyName().equals(study)) {
                studyConfiguration = defaultStudyConfiguration;
            } else {
                studyConfiguration = getStudyConfiguration(study, options).first();
            }
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(study, getStudyNames(options));
            }
        }
        return studyConfiguration;
    }

    /**
     * Get list of fileIds for each study.
     *
     * @param files                     List of files
     * @param skipNegated               Do not include negated files in the list
     * @param defaultStudyConfiguration Default study configuration. Use to relate files with a study.
     * @return Map from studyId to list of fileIds
     */
    public Map<Integer, List<Integer>> getFileIdsMap(List<?> files, boolean skipNegated, StudyConfiguration defaultStudyConfiguration) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, List<Integer>> fileIdsMap = new HashMap<>();
        for (Object fileObj : files) {
            Pair<Integer, Integer> pair = getFileIdPair(fileObj, skipNegated, defaultStudyConfiguration);
            if (pair != null) {
                Integer studyId = pair.getKey();
                Integer fileId = pair.getValue();
                fileIdsMap.computeIfAbsent(studyId, k -> new ArrayList<>()).add(fileId);
            }
        }
        return fileIdsMap;
    }

    public Pair<Integer, Integer> getFileIdPair(Object fileObj, boolean skipNegated, StudyConfiguration defaultStudyConfiguration) {
        final Integer studyId;
        final Integer fileId;

        if (fileObj instanceof Number) {
            fileId = ((Number) fileObj).intValue();
            if (defaultStudyConfiguration != null && (defaultStudyConfiguration.getFileIds().containsValue(fileId)
                    || defaultStudyConfiguration.getFileIds().containsValue(-fileId))) {
                studyId = defaultStudyConfiguration.getStudyId();
            } else {
                studyId = null;
            }
        } else {
            String fileStr = String.valueOf(fileObj);
            if (isNegated(fileStr)) { //Skip negated studies
                if (skipNegated) {
                    return null;
                } else {
                    fileStr = removeNegation(fileStr);
                }
            }
            String[] studyFile = VariantQueryUtils.splitStudyResource(fileStr);
            if (studyFile.length == 2) {
                String study = studyFile[0];
                fileStr = studyFile[1];
                StudyConfiguration sc;
                if (defaultStudyConfiguration != null
                        && (study.equals(defaultStudyConfiguration.getStudyName())
                        || NumberUtils.isParsable(study) && Integer.valueOf(study).equals(defaultStudyConfiguration.getStudyId()))) {
                    sc = defaultStudyConfiguration;
                } else {
                    QueryResult<StudyConfiguration> queryResult = getStudyConfiguration(study, new QueryOptions());
                    if (queryResult.getResult().isEmpty()) {
                        throw VariantQueryException.studyNotFound(study);
                    }
                    sc = queryResult.first();
                }
                studyId = sc.getStudyId();
                fileId = sc.getFileIds().get(fileStr);
            } else if (defaultStudyConfiguration != null) {
                if (NumberUtils.isParsable(fileStr)) {
                    fileId = Integer.parseInt(fileStr);
                    if (defaultStudyConfiguration.getFileIds().containsValue(fileId)
                            || defaultStudyConfiguration.getFileIds().containsValue(-fileId)) {
                        studyId = defaultStudyConfiguration.getStudyId();
                    } else {
                        studyId = null;
                    }
                } else {
                    fileId = defaultStudyConfiguration.getFileIds().get(fileStr);
                    if (fileId != null) {
                        studyId = defaultStudyConfiguration.getStudyId();
                    } else {
                        studyId = null;
                    }
                }
            } else if (NumberUtils.isParsable(fileStr)) {
                studyId = null;
                fileId = Integer.parseInt(fileStr);
            } else {
                studyId = null;
                fileId = null;
            }
        }

        if (studyId == null) {
            Map<String, Integer> studies = getStudies(null);
            Collection<Integer> studyIds = studies.values();
            Integer fileIdFromStudy;
            for (Integer id : studyIds) {
                StudyConfiguration sc = getStudyConfiguration(id, new QueryOptions(READ_ONLY, true).append(CACHED, true)).first();
                fileIdFromStudy = getFileIdFromStudy(fileId != null ? fileId : fileObj, sc);
                if (fileIdFromStudy != null) {
                    return Pair.of(sc.getStudyId(), fileIdFromStudy);
                }
            }
            throw VariantQueryException.missingStudyForFile(fileObj.toString(), studies.keySet());
        }

        return Pair.of(studyId, fileId);
    }

    /**
     * Get list of fileIds from a study.
     *
     * @param files              List of files
     * @param studyConfiguration Study configuration.
     * @return List of file ids within this study
     * @throws VariantQueryException if the list of files contains files from other studies
     */
    public static List<Integer> getFileIdsFromStudy(List<?> files, StudyConfiguration studyConfiguration) throws VariantQueryException {
        Objects.requireNonNull(studyConfiguration);
        List<Integer> fileIds = new ArrayList<>(files.size());
        for (Object fileObj : files) {
            Integer fileId = getFileIdFromStudy(fileObj, studyConfiguration);
            if (fileId == null) {
                throw VariantQueryException.fileNotFound(fileObj, studyConfiguration.getStudyName());
            }
            fileIds.add(fileId);
        }
        return fileIds;
    }

    /**
     * Get fileId from a given study configuration.
     *
     * @param fileObj            File object
     * @param studyConfiguration Study configuration.
     * @return File id within this study. Null if the file does not exist.
     */
    public static Integer getFileIdFromStudy(Object fileObj, StudyConfiguration studyConfiguration) {
        return getResourceIdFromStudy(fileObj, studyConfiguration, studyConfiguration.getFileIds());
    }

    /**
     * Get fileId from a given study configuration.
     *
     * @param obj                Object
     * @param studyConfiguration Study configuration.
     * @param biMap              BiMap containing Names and Ids for this resource
     * @return File id within this study. Null if the file does not exist.
     */
    private static Integer getResourceIdFromStudy(Object obj, StudyConfiguration studyConfiguration, BiMap<String, Integer> biMap) {
        final Integer id;
        if (obj instanceof Number) {
            int aux = ((Number) obj).intValue();
            if (biMap.containsValue(aux)) {
                id = aux;
            } else {
                id = null;
            }
        } else {
            String str = obj.toString();
            if (isNegated(str)) {
                str = removeNegation(str);
            }
            if (StringUtils.isNumeric(str)) {
                id = Integer.parseInt(str);
            } else {
                String[] split = VariantQueryUtils.splitStudyResource(str);
                if (split.length == 2) {
                    String study = split[0];
                    str = split[1];
                    if (study.equals(studyConfiguration.getStudyName())
                            || StringUtils.isNumeric(study) && Integer.valueOf(study).equals(studyConfiguration.getStudyId())) {
                        if (StringUtils.isNumeric(str)) {
                            int aux = Integer.valueOf(str);
                            if (biMap.containsValue(aux)) {
                                id = aux;
                            } else {
                                id = null;
                            }
                        } else {
                            id = biMap.get(str);
                        }
                    } else {
                        id = null;
                    }
                } else if (StringUtils.isNumeric(str)) {
                    int aux = Integer.valueOf(str);
                    if (biMap.containsValue(aux)) {
                        id = aux;
                    } else {
                        id = null;
                    }
                } else {
                    id = biMap.get(str);
                }
            }
        }
        return id;
    }

    // TODO: Return sampleId and studyId as a Pair
    public int getSampleId(Object sampleObj, StudyConfiguration defaultStudyConfiguration) {
        int sampleId;
        if (sampleObj instanceof Number) {
            sampleId = ((Number) sampleObj).intValue();
        } else {
            String sampleStr = sampleObj.toString();
            if (isNegated(sampleStr)) {
                sampleStr = removeNegation(sampleStr);
            }
            if (StringUtils.isNumeric(sampleStr)) {
                sampleId = Integer.parseInt(sampleStr);
            } else {
                String[] split = VariantQueryUtils.splitStudyResource(sampleStr);
                if (split.length == 2) {  //Expect to be as <study>:<sample>
                    String study = split[0];
                    sampleStr = split[1];
                    StudyConfiguration sc;
                    if (defaultStudyConfiguration != null && study.equals(defaultStudyConfiguration.getStudyName())) {
                        sc = defaultStudyConfiguration;
                    } else {
                        QueryResult<StudyConfiguration> queryResult = getStudyConfiguration(study, new QueryOptions());
                        if (queryResult.getResult().isEmpty()) {
                            throw VariantQueryException.studyNotFound(study);
                        }
                        sc = queryResult.first();
                    }
                    if (!sc.getSampleIds().containsKey(sampleStr)) {
                        throw VariantQueryException.sampleNotFound(sampleStr, study);
                    }
                    sampleId = sc.getSampleIds().get(sampleStr);
                } else if (defaultStudyConfiguration != null) {
                    if (!defaultStudyConfiguration.getSampleIds().containsKey(sampleStr)) {
                        throw VariantQueryException.sampleNotFound(sampleStr, defaultStudyConfiguration.getStudyName());
                    }
                    sampleId = defaultStudyConfiguration.getSampleIds().get(sampleStr);
                } else {
                    //Unable to identify that sample!
                    List<String> studyNames = getStudyNames(null);
                    throw VariantQueryException.missingStudyForSample(sampleStr, studyNames);
                }
            }
        }
        return sampleId;
    }

    public static Integer getSampleIdFromStudy(Object sampleObj, StudyConfiguration sc) {
        return getSampleIdFromStudy(sampleObj, sc, false);
    }

    public static Integer getSampleIdFromStudy(Object sampleObj, StudyConfiguration sc, boolean indexed) {
        Integer sampleId = getResourceIdFromStudy(sampleObj, sc, sc.getSampleIds());
        if (indexed) {
            if (sampleId != null) {
                for (Integer indexedFile : sc.getIndexedFiles()) {
                    if (sc.getSamplesInFiles().get(indexedFile).contains(sampleId)) {
                        return sampleId;
                    }
                }
            }
            return null;
        } else {
            return sampleId;
        }
    }

    // TODO: Return cohortId and studyId as a Pair
    /**
     * Finds the cohortId from a cohort reference.
     *
     * @param cohort    Cohort reference (name or id)
     * @param studyConfiguration  Default study configuration
     * @return  Cohort id
     * @throws VariantQueryException if the cohort does not exist
     */
    public int getCohortId(String cohort, StudyConfiguration studyConfiguration) throws VariantQueryException {
        int cohortId;
        if (StringUtils.isNumeric(cohort)) {
            cohortId = Integer.parseInt(cohort);
            if (!studyConfiguration.getCohortIds().containsValue(cohortId)) {
                throw VariantQueryException.cohortNotFound(cohortId, studyConfiguration.getStudyId(),
                        studyConfiguration.getCohortIds().keySet());
            }
        } else {
            Integer cohortIdNullable = studyConfiguration.getCohortIds().get(cohort);
            if (cohortIdNullable == null) {
                throw VariantQueryException.cohortNotFound(cohort, studyConfiguration.getStudyId(),
                        studyConfiguration.getCohortIds().keySet());
            }
            cohortId = cohortIdNullable;
        }
        return cohortId;
    }

    /**
     * Get list of fileIds from a study.
     *
     * @param cohorts              List of cohorts
     * @param studyConfiguration Study configuration.
     * @return List of file ids within this study
     * @throws VariantQueryException if the list of cohorts contains cohorts from other studies
     */
    public static List<Integer> getCohortIdsFromStudy(List<?> cohorts, StudyConfiguration studyConfiguration) throws VariantQueryException {
        Objects.requireNonNull(studyConfiguration);
        List<Integer> fileIds = new ArrayList<>(cohorts.size());
        for (Object cohortObj : cohorts) {
            Integer cohortId = getCohortIdFromStudy(cohortObj, studyConfiguration);
            if (cohortId == null) {
                throw VariantQueryException.cohortNotFound(cohortObj.toString(), studyConfiguration.getStudyId(),
                        studyConfiguration.getCohortIds().keySet());
            }
            fileIds.add(cohortId);
        }
        return fileIds;
    }

    public static Integer getCohortIdFromStudy(Object cohortObj, StudyConfiguration sc) {
        return getResourceIdFromStudy(cohortObj, sc, sc.getCohortIds());
    }

    /*
     * Before load file, the StudyConfiguration has to be updated with the new sample names.
     * Will read param SAMPLE_IDS like [<sampleName>:<sampleId>,]*
     * If SAMPLE_IDS is missing, will auto-generate sampleIds
     * Will fail if:
     * param SAMPLE_IDS is malformed
     * any given sampleId is not an integer
     * any given sampleName is not in the input file
     * any given sampleName was already in the StudyConfiguration (so, was already loaded)
     * some sample was missing in the given SAMPLE_IDS param
     *
     */
    public static void checkAndUpdateStudyConfiguration(StudyConfiguration studyConfiguration, int fileId, VariantFileMetadata fileMetadata,
                                                        ObjectMap options)
            throws StorageEngineException {
        if (options.containsKey(VariantStorageEngine.Options.SAMPLE_IDS.key())
                && !options.getAsStringList(VariantStorageEngine.Options.SAMPLE_IDS.key()).isEmpty()) {
            for (String sampleEntry : options.getAsStringList(VariantStorageEngine.Options.SAMPLE_IDS.key())) {
                String[] split = VariantQueryUtils.splitStudyResource(sampleEntry);
                if (split.length != 2) {
                    throw new StorageEngineException("Param " + sampleEntry + " is malformed");
                }
                String sampleName = split[0];
                int sampleId;
                try {
                    sampleId = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    throw new StorageEngineException("SampleId " + split[1] + " is not an integer", e);
                }

                if (!fileMetadata.getSamplesPosition().containsKey(sampleName)) {
                    //ERROR
                    throw new StorageEngineException("Given sampleName '" + sampleName + "' is not in the input file");
                } else {
                    if (!studyConfiguration.getSampleIds().containsKey(sampleName)) {
                        //Add sample to StudyConfiguration
                        studyConfiguration.getSampleIds().put(sampleName, sampleId);
                    } else {
                        if (studyConfiguration.getSampleIds().get(sampleName) != sampleId) {
                            throw new StorageEngineException("Sample " + sampleName + ":" + sampleId
                                    + " was already present. It was in the StudyConfiguration with a different sampleId: "
                                    + studyConfiguration.getSampleIds().get(sampleName));
                        }
                    }
                }
            }

            //Check that all samples has a sampleId
            List<String> missingSamples = new LinkedList<>();
            for (String sample : fileMetadata.getSampleIds()) {
                if (!studyConfiguration.getSampleIds().containsKey(sample)) {
                    missingSamples.add(sample);
                } /*else {
                    Integer sampleId = studyConfiguration.getSampleIds().get(sample);
                    if (studyConfiguration.getIndexedSamples().contains(sampleId)) {
                        logger.warn("Sample " + sample + ":" + sampleId + " was already loaded.
                        It was in the StudyConfiguration.indexedSamples");
                    }
                }*/
            }
            if (!missingSamples.isEmpty()) {
                throw new StorageEngineException("Samples " + missingSamples.toString() + " has not assigned sampleId");
            }

        } else {
            //Find the grader sample Id in the studyConfiguration, in order to add more sampleIds if necessary.
            int maxId = studyConfiguration.getSampleIds().values().stream().max(Integer::compareTo).orElse(0);

            //Assign new sampleIds
            for (String sample : fileMetadata.getSampleIds()) {
                if (!studyConfiguration.getSampleIds().containsKey(sample)) {
                    //If the sample was not in the original studyId, a new SampleId is assigned.

                    int sampleId;
                    int samplesSize = studyConfiguration.getSampleIds().size();
                    Integer samplePosition = fileMetadata.getSamplesPosition().get(sample);
                    if (!studyConfiguration.getSampleIds().containsValue(samplePosition) && samplePosition != 0) {
                        //1- Use with the SamplePosition
                        sampleId = samplePosition;
                    } else if (!studyConfiguration.getSampleIds().containsValue(samplesSize) && samplesSize != 0) {
                        //2- Use the number of samples in the StudyConfiguration.
                        sampleId = samplesSize;
                    } else {
                        //3- Use the maxId
                        sampleId = maxId + 1;
                    }
                    studyConfiguration.getSampleIds().put(sample, sampleId);
                    if (sampleId > maxId) {
                        maxId = sampleId;
                    }
                }
            }
        }

        if (studyConfiguration.getSamplesInFiles().containsKey(fileId)) {
            LinkedHashSet<Integer> sampleIds = studyConfiguration.getSamplesInFiles().get(fileId);
            List<String> missingSamples = new LinkedList<>();
            for (String sample : fileMetadata.getSampleIds()) {
                if (!sampleIds.contains(studyConfiguration.getSampleIds().get(sample))) {
                    missingSamples.add(sample);
                }
            }
            if (!missingSamples.isEmpty()) {
                throw new StorageEngineException("Samples " + missingSamples.toString() + " were not in file " + fileId);
            }
            if (sampleIds.size() != fileMetadata.getSampleIds().size()) {
                throw new StorageEngineException("Incorrect number of samples in file " + fileId);
            }
        } else {
            LinkedHashSet<Integer> sampleIdsInFile = new LinkedHashSet<>(fileMetadata.getSampleIds().size());
            for (String sample : fileMetadata.getSampleIds()) {
                sampleIdsInFile.add(studyConfiguration.getSampleIds().get(sample));
            }
            studyConfiguration.getSamplesInFiles().put(fileId, sampleIdsInFile);
        }
    }

    /**
     * Check if the StudyConfiguration is correct.
     *
     * @param studyConfiguration StudyConfiguration to check
     * @throws StorageEngineException If object is null
     */
    public static void checkStudyConfiguration(StudyConfiguration studyConfiguration) throws StorageEngineException {
        if (studyConfiguration == null) {
            throw new StorageEngineException("StudyConfiguration is null");
        }
        checkStudyId(studyConfiguration.getStudyId());
        if (studyConfiguration.getFileIds().size() != StudyConfiguration.inverseMap(studyConfiguration.getFileIds()).size()) {
            throw new StorageEngineException("StudyConfiguration has duplicated fileIds");
        }
        if (studyConfiguration.getCohortIds().size() != StudyConfiguration.inverseMap(studyConfiguration.getCohortIds()).size()) {
            throw new StorageEngineException("StudyConfiguration has duplicated cohortIds");
        }
    }

    /**
     * Check if the file(name,id) can be added to the StudyConfiguration.
     *
     * Will fail if:
     * fileName was already in the studyConfiguration.fileIds with a different fileId
     * fileId was already in the studyConfiguration.fileIds with a different fileName
     * fileId was already in the studyConfiguration.indexedFiles
     *
     * @param studyConfiguration Study Configuration
     * @param fileId    FileId to add. If negative, will generate a new one
     * @param fileName  File name
     * @return fileId related to that file.
     * @throws StorageEngineException if the file is not valid for being loaded
     */
    public static int checkNewFile(StudyConfiguration studyConfiguration, int fileId, String fileName) throws StorageEngineException {
        Map<Integer, String> idFiles = StudyConfiguration.inverseMap(studyConfiguration.getFileIds());

        if (fileId < 0) {
            if (studyConfiguration.getFileIds().containsKey(fileName)) {
                fileId = studyConfiguration.getFileIds().get(fileName);
            } else {
                fileId = studyConfiguration.getFileIds().values().stream().max(Integer::compareTo).orElse(0) + 1;
                studyConfiguration.getFileIds().put(fileName, fileId);
            }
            //throw new StorageEngineException("Invalid fileId " + fileId + " for file " + fileName + ". FileId must be positive.");
        }

        if (studyConfiguration.getFileIds().containsKey(fileName)) {
            if (studyConfiguration.getFileIds().get(fileName) != fileId) {
                throw new StorageEngineException("File " + fileName + " (" + fileId + ") "
                        + "has a different fileId in the study "
                        + studyConfiguration.getStudyName() + " (" + studyConfiguration.getStudyId() + ") : "
                        + fileName + " (" + studyConfiguration.getFileIds().get(fileName) + ")");
            }
        }
        if (idFiles.containsKey(fileId)) {
            if (!idFiles.get(fileId).equals(fileName)) {
                throw new StorageEngineException("File " + fileName + " (" + fileId + ") "
                        + "has a different fileName in the StudyConfiguration: "
                        + idFiles.get(fileId) + " (" + fileId + ")");
            }
        }

        if (studyConfiguration.getIndexedFiles().contains(fileId)) {
            throw StorageEngineException.alreadyLoaded(fileId, fileName);
        }
        return fileId;
    }

    public static void checkStudyId(int studyId) throws StorageEngineException {
        if (studyId < 0) {
            throw new StorageEngineException("Invalid studyId : " + studyId);
        }
    }

    public BatchFileOperation.Status atomicSetStatus(int studyId, BatchFileOperation.Status status, String operationName,
                                                     List<Integer> files)
            throws StorageEngineException {
        final BatchFileOperation.Status[] previousStatus = new BatchFileOperation.Status[1];
        lockAndUpdate(studyId, studyConfiguration -> {
            previousStatus[0] = setStatus(studyConfiguration, status, operationName, files);
            return studyConfiguration;
        });
        return previousStatus[0];
    }

    public static BatchFileOperation getOperation(StudyConfiguration studyConfiguration, String operationName, List<Integer> files) {
        List<BatchFileOperation> batches = studyConfiguration.getBatches();
        BatchFileOperation operation = null;
        for (int i = batches.size() - 1; i >= 0; i--) {
            operation = batches.get(i);
            if (operation.getOperationName().equals(operationName) && operation.getFileIds().equals(files)) {
                break;
            }
            operation = null;
        }
        return operation;
    }

    public static BatchFileOperation.Status setStatus(StudyConfiguration studyConfiguration, BatchFileOperation.Status status,
                                               String operationName, List<Integer> files) {
        BatchFileOperation operation = getOperation(studyConfiguration, operationName, files);
        if (operation == null) {
            throw new IllegalStateException("Batch operation " + operationName + " for files " + files + " not found!");
        }
        BatchFileOperation.Status previousStatus = operation.currentStatus();
        operation.addStatus(Calendar.getInstance().getTime(), status);
        return previousStatus;
    }

    /**
     * Adds a new {@link BatchFileOperation} to the StudyConfiguration.
     *
     * Only allow one running operation at the same time
     *  If any operation is in ERROR and is not the same operation, throw {@link StorageEngineException#otherOperationInProgressException}
     *  If any operation is DONE, RUNNING, is same operation and resume=true, continue
     *  If all operations are ready, continue
     *
     * @param studyConfiguration StudyConfiguration
     * @param jobOperationName   Job operation name used to create the jobName and as {@link BatchFileOperation#operationName}
     * @param fileIds            Files to be processed in this batch.
     * @param resume             Resume operation. Assume that previous operation went wrong.
     * @param type               Operation type as {@link BatchFileOperation#type}
     * @return                   The current batchOperation
     * @throws StorageEngineException if the operation can't be executed
     */
    public static BatchFileOperation addBatchOperation(StudyConfiguration studyConfiguration, String jobOperationName,
                                                       List<Integer> fileIds, boolean resume, BatchFileOperation.Type type)
            throws StorageEngineException {
        return addBatchOperation(studyConfiguration, jobOperationName, fileIds, resume, type, b -> false);
    }

    /**
     * Adds a new {@link BatchFileOperation} to the StudyConfiguration.
     *
     * Allow execute concurrent operations depending on the "allowConcurrent" predicate.
     *  If any operation is in ERROR, is not the same operation, and concurrency is not allowed,
     *      throw {@link StorageEngineException#otherOperationInProgressException}
     *  If any operation is DONE, RUNNING, is same operation and resume=true, continue
     *  If all operations are ready, continue
     *
     * @param studyConfiguration StudyConfiguration
     * @param jobOperationName   Job operation name used to create the jobName and as {@link BatchFileOperation#operationName}
     * @param fileIds            Files to be processed in this batch.
     * @param resume             Resume operation. Assume that previous operation went wrong.
     * @param type               Operation type as {@link BatchFileOperation#type}
     * @param allowConcurrent    Predicate to test if the new operation can be executed at the same time as a non ready operation.
     *                           If not, throws {@link StorageEngineException#otherOperationInProgressException}
     * @return                   The current batchOperation
     * @throws StorageEngineException if the operation can't be executed
     */
    public static BatchFileOperation addBatchOperation(StudyConfiguration studyConfiguration, String jobOperationName,
                                                       List<Integer> fileIds, boolean resume, BatchFileOperation.Type type,
                                                       Predicate<BatchFileOperation> allowConcurrent)
            throws StorageEngineException {

        List<BatchFileOperation> batches = studyConfiguration.getBatches();
        BatchFileOperation resumeOperation = null;
        boolean newOperation = false;
        for (BatchFileOperation operation : batches) {
            BatchFileOperation.Status currentStatus = operation.currentStatus();

            switch (currentStatus) {
                case READY:
                    // Ignore ready operations
                    break;
                case DONE:
                case RUNNING:
                    if (!resume) {
                        if (operation.sameOperation(fileIds, type, jobOperationName)) {
                            throw StorageEngineException.currentOperationInProgressException(operation);
                        } else {
                            if (allowConcurrent.test(operation)) {
                                break;
                            } else {
                                throw StorageEngineException.otherOperationInProgressException(operation, jobOperationName, fileIds);
                            }
                        }
                    }
                    // DO NOT BREAK!. Resuming last loading, go to error case.
                case ERROR:
                    if (!operation.sameOperation(fileIds, type, jobOperationName)) {
                        if (allowConcurrent.test(operation)) {
                            break;
                        } else {
                            throw StorageEngineException.otherOperationInProgressException(operation, jobOperationName, fileIds, resume);
                        }
                    } else {
                        logger.info("Resuming last batch operation \"" + operation.getOperationName() + "\" due to error.");
                        resumeOperation = operation;
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Status " + currentStatus);
            }
        }

        BatchFileOperation operation;
        if (resumeOperation == null) {
            operation = new BatchFileOperation(jobOperationName, fileIds, System.currentTimeMillis(), type);
            newOperation = true;
        } else {
            operation = resumeOperation;
        }

        if (!Objects.equals(operation.currentStatus(), BatchFileOperation.Status.DONE)) {
            operation.addStatus(Calendar.getInstance().getTime(), BatchFileOperation.Status.RUNNING);
        }
        if (newOperation) {
            batches.add(operation);
        }
        return operation;
    }

    @Override
    public void close() throws IOException {
        adaptor.close();
    }
}
