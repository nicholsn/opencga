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

package org.opencb.opencga.catalog.managers;

import org.apache.commons.lang3.StringUtils;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.audit.AuditManager;
import org.opencb.opencga.catalog.audit.AuditRecord;
import org.opencb.opencga.catalog.auth.authorization.AuthorizationManager;
import org.opencb.opencga.catalog.db.DBAdaptorFactory;
import org.opencb.opencga.catalog.db.api.CohortDBAdaptor;
import org.opencb.opencga.catalog.db.api.DBIterator;
import org.opencb.opencga.catalog.db.api.IndividualDBAdaptor;
import org.opencb.opencga.catalog.db.api.SampleDBAdaptor;
import org.opencb.opencga.catalog.db.mongodb.MongoDBAdaptorFactory;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.io.CatalogIOManagerFactory;
import org.opencb.opencga.catalog.utils.ParamUtils;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.config.Configuration;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.models.acls.AclParams;
import org.opencb.opencga.core.models.acls.permissions.CohortAclEntry;
import org.opencb.opencga.core.models.acls.permissions.StudyAclEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.opencb.opencga.catalog.auth.authorization.CatalogAuthorizationManager.checkPermissions;

/**
 * Created by pfurio on 06/07/16.
 */
public class CohortManager extends AnnotationSetManager<Cohort> {

    protected static Logger logger = LoggerFactory.getLogger(CohortManager.class);

    private UserManager userManager;
    private StudyManager studyManager;

    CohortManager(AuthorizationManager authorizationManager, AuditManager auditManager, CatalogManager catalogManager,
                  DBAdaptorFactory catalogDBAdaptorFactory, CatalogIOManagerFactory ioManagerFactory,
                  Configuration configuration) {
        super(authorizationManager, auditManager, catalogManager, catalogDBAdaptorFactory, ioManagerFactory, configuration);

        this.userManager = catalogManager.getUserManager();
        this.studyManager = catalogManager.getStudyManager();
    }

    public QueryResult<Cohort> create(long studyId, String name, Study.Type type, String description, List<Sample> samples,
                                      List<AnnotationSet> annotationSetList, Map<String, Object> attributes, String sessionId)
            throws CatalogException {
        Cohort cohort = new Cohort(name, type, "", description, samples, annotationSetList, -1, attributes);
        return create(String.valueOf(studyId), cohort, QueryOptions.empty(), sessionId);
    }

    @Override
    public QueryResult<Cohort> create(String studyStr, Cohort cohort, QueryOptions options, String sessionId) throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);
        authorizationManager.checkStudyPermission(studyId, userId, StudyAclEntry.StudyPermissions.WRITE_COHORTS);

        ParamUtils.checkObj(cohort, "Cohort");
        ParamUtils.checkParameter(cohort.getName(), "name");
        ParamUtils.checkObj(cohort.getSamples(), "Sample list");
        cohort.setType(ParamUtils.defaultObject(cohort.getType(), Study.Type.COLLECTION));
        cohort.setCreationDate(TimeUtils.getTime());
        cohort.setDescription(ParamUtils.defaultString(cohort.getDescription(), ""));
        cohort.setAnnotationSets(ParamUtils.defaultObject(cohort.getAnnotationSets(), Collections::emptyList));
        cohort.setAnnotationSets(validateAnnotationSets(cohort.getAnnotationSets()));
        cohort.setAttributes(ParamUtils.defaultObject(cohort.getAttributes(), HashMap<String, Object>::new));
        cohort.setRelease(studyManager.getCurrentRelease(studyId));

        if (!cohort.getSamples().isEmpty()) {
            Query query = new Query()
                    .append(SampleDBAdaptor.QueryParams.STUDY_ID.key(), studyId)
                    .append(SampleDBAdaptor.QueryParams.ID.key(), cohort.getSamples().stream()
                            .map(Sample::getId)
                            .collect(Collectors.toList()));
            QueryResult<Long> count = sampleDBAdaptor.count(query);
            if (count.first() != cohort.getSamples().size()) {
                throw new CatalogException("Error: Some samples do not exist in the study " + studyId);
            }
        }

        QueryResult<Cohort> queryResult = cohortDBAdaptor.insert(cohort, studyId, null);
        auditManager.recordCreation(AuditRecord.Resource.cohort, queryResult.first().getId(), userId, queryResult.first(), null, null);
        return queryResult;
    }

    @Override
    public Long getStudyId(long cohortId) throws CatalogException {
        return cohortDBAdaptor.getStudyId(cohortId);
    }

    @Override
    public QueryResult<Cohort> get(String studyStr, Query query, QueryOptions options, String sessionId) throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);

        query.append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId);

        QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(query, options, userId);

        if (cohortQueryResult.getNumResults() == 0 && query.containsKey("id")) {
            List<Long> idList = query.getAsLongList("id");
            for (Long myId : idList) {
                authorizationManager.checkCohortPermission(studyId, myId, userId, CohortAclEntry.CohortPermissions.VIEW);
            }
        }

        return cohortQueryResult;
    }

    /**
     * Fetch all the samples from a cohort.
     *
     * @param studyStr  Study id in string format. Could be one of [id|user@aliasProject:aliasStudy|aliasProject:aliasStudy|aliasStudy].
     * @param cohortStr Cohort id or name.
     * @param options   QueryOptions object.
     * @param sessionId Token of the user logged in.
     * @return a QueryResult containing all the samples belonging to the cohort.
     * @throws CatalogException if there is any kind of error (permissions or invalid ids).
     */
    public QueryResult<Sample> getSamples(String studyStr, String cohortStr, QueryOptions options, String sessionId)
            throws CatalogException {
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        MyResourceId resource = getId(cohortStr, studyStr, sessionId);
        QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(resource.getResourceId(),
                new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.SAMPLES.key()));

        if (cohortQueryResult == null || cohortQueryResult.getNumResults() == 0) {
            throw new CatalogException("No cohort " + cohortStr + " found in study " + studyStr);
        }
        if (cohortQueryResult.first().getSamples().size() == 0) {
            return new QueryResult<>("Samples from cohort " + cohortStr);
        }

        // Look for the samples
        List<Long> sampleIds = cohortQueryResult.first().getSamples()
                .stream()
                .map(Sample::getId)
                .collect(Collectors.toList());
        Query query = new Query(SampleDBAdaptor.QueryParams.ID.key(), sampleIds);
        QueryResult<Sample> sampleQueryResult = catalogManager.getSampleManager().get(String.valueOf(resource.getStudyId()), query,
                options, sessionId);
        sampleQueryResult.setId("Samples from cohort " + cohortStr);

        return sampleQueryResult;
    }

    public QueryResult<Cohort> get(long studyId, Query query, QueryOptions options, String sessionId) throws CatalogException {
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        query = ParamUtils.defaultObject(query, Query::new);

        String userId = userManager.getUserId(sessionId);

        if (query.containsKey(CohortDBAdaptor.QueryParams.SAMPLES.key())) {
            // First look for the sample ids.
            AbstractManager.MyResourceIds samples = catalogManager.getSampleManager()
                    .getIds(query.getAsStringList(CohortDBAdaptor.QueryParams.SAMPLES.key()), Long.toString(studyId), sessionId);
            query.remove(CohortDBAdaptor.QueryParams.SAMPLES.key());
            query.append(CohortDBAdaptor.QueryParams.SAMPLE_IDS.key(), samples.getResourceIds());
        }

        Query myQuery = new Query(query).append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId);
        QueryResult<Cohort> queryResult = cohortDBAdaptor.get(myQuery, options, userId);
        return queryResult;
    }

    @Override
    public DBIterator<Cohort> iterator(String studyStr, Query query, QueryOptions options, String sessionId) throws CatalogException {
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        query = ParamUtils.defaultObject(query, Query::new);

        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);

        if (query.containsKey(CohortDBAdaptor.QueryParams.SAMPLES.key())) {
            // First look for the sample ids.
            AbstractManager.MyResourceIds samples = catalogManager.getSampleManager()
                    .getIds(query.getAsStringList(CohortDBAdaptor.QueryParams.SAMPLES.key()), Long.toString(studyId), sessionId);
            query.remove(CohortDBAdaptor.QueryParams.SAMPLES.key());
            query.append(CohortDBAdaptor.QueryParams.SAMPLE_IDS.key(), samples.getResourceIds());
        }

        Query myQuery = new Query(query).append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId);
        return cohortDBAdaptor.iterator(myQuery, options, userId);
    }

    @Override
    public MyResourceId getId(String cohortStr, @Nullable String studyStr, String sessionId) throws CatalogException {
        if (StringUtils.isEmpty(cohortStr)) {
            throw new CatalogException("Missing cohort parameter");
        }

        String userId;
        long studyId;
        long cohortId;

        if (StringUtils.isNumeric(cohortStr) && Long.parseLong(cohortStr) > configuration.getCatalog().getOffset()) {
            cohortId = Long.parseLong(cohortStr);
            cohortDBAdaptor.exists(cohortId);
            studyId = cohortDBAdaptor.getStudyId(cohortId);
            userId = userManager.getUserId(sessionId);
        } else {
            if (cohortStr.contains(",")) {
                throw new CatalogException("More than one cohort found");
            }

            userId = userManager.getUserId(sessionId);
            studyId = studyManager.getId(userId, studyStr);

            Query query = new Query()
                    .append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId)
                    .append(CohortDBAdaptor.QueryParams.NAME.key(), cohortStr);
            QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.ID.key());
            QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(query, queryOptions);
            if (cohortQueryResult.getNumResults() == 1) {
                cohortId = cohortQueryResult.first().getId();
            } else {
                if (cohortQueryResult.getNumResults() == 0) {
                    throw new CatalogException("Cohort " + cohortStr + " not found in study " + studyStr);
                } else {
                    throw new CatalogException("More than one cohort found under " + cohortStr + " in study " + studyStr);
                }
            }
        }

        return new MyResourceId(userId, studyId, cohortId);
    }

    @Override
    MyResourceIds getIds(List<String> cohortList, @Nullable String studyStr, boolean silent, String sessionId) throws CatalogException {
        if (cohortList == null || cohortList.isEmpty()) {
            throw new CatalogException("Missing cohort parameter");
        }

        String userId;
        long studyId;
        List<Long> cohortIds = new ArrayList<>();

        if (cohortList.size() == 1 && StringUtils.isNumeric(cohortList.get(0))
                && Long.parseLong(cohortList.get(0)) > configuration.getCatalog().getOffset()) {
            cohortIds.add(Long.parseLong(cohortList.get(0)));
            cohortDBAdaptor.checkId(cohortIds.get(0));
            studyId = cohortDBAdaptor.getStudyId(cohortIds.get(0));
            userId = userManager.getUserId(sessionId);
        } else {
            userId = userManager.getUserId(sessionId);
            studyId = studyManager.getId(userId, studyStr);

            Map<String, Long> myIds = new HashMap<>();
            for (String cohortStrAux : cohortList) {
                if (StringUtils.isNumeric(cohortStrAux)) {
                    long cohortId = getCohortId(silent, cohortStrAux);
                    myIds.put(cohortStrAux, cohortId);
                }
            }

            if (myIds.size() < cohortList.size()) {
                Query query = new Query()
                        .append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId)
                        .append(CohortDBAdaptor.QueryParams.NAME.key(), cohortList);

                QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, Arrays.asList(
                        CohortDBAdaptor.QueryParams.ID.key(), CohortDBAdaptor.QueryParams.NAME.key()));
                QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(query, queryOptions);

                if (cohortQueryResult.getNumResults() > 0) {
                    myIds.putAll(cohortQueryResult.getResult().stream().collect(Collectors.toMap(Cohort::getName, Cohort::getId)));
                }
            }
            if (myIds.size() < cohortList.size() && !silent) {
                throw new CatalogException("Found only " + myIds.size() + " out of the " + cohortList.size()
                        + " cohorts looked for in study " + studyStr);
            }
            for (String cohortStrAux : cohortList) {
                cohortIds.add(myIds.getOrDefault(cohortStrAux, -1L));
            }
        }

        return new MyResourceIds(userId, studyId, cohortIds);
    }

    @Override
    public QueryResult<Cohort> search(String studyStr, Query query, QueryOptions options, String sessionId) throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);

        if (query.containsKey(CohortDBAdaptor.QueryParams.SAMPLES.key())) {
            // First look for the sample ids.
            AbstractManager.MyResourceIds samples = catalogManager.getSampleManager()
                    .getIds(query.getAsStringList(CohortDBAdaptor.QueryParams.SAMPLES.key()), studyStr, sessionId);
            query.remove(CohortDBAdaptor.QueryParams.SAMPLES.key());
            query.append(CohortDBAdaptor.QueryParams.SAMPLE_IDS.key(), samples.getResourceIds());
        }

        query.append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId);
        QueryResult<Cohort> queryResult = cohortDBAdaptor.get(query, options, userId);
//        authorizationManager.filterCohorts(userId, studyId, queryResultAux.getResult());

        return queryResult;
    }

    @Override
    public QueryResult<Cohort> count(String studyStr, Query query, String sessionId) throws CatalogException {
        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);

        if (query.containsKey(CohortDBAdaptor.QueryParams.SAMPLES.key())) {
            // First look for the sample ids.
            AbstractManager.MyResourceIds samples = catalogManager.getSampleManager()
                    .getIds(query.getAsStringList(CohortDBAdaptor.QueryParams.SAMPLES.key()), studyStr, sessionId);
            query.remove(CohortDBAdaptor.QueryParams.SAMPLES.key());
            query.append(CohortDBAdaptor.QueryParams.SAMPLE_IDS.key(), samples.getResourceIds());
        }

        query.append(CohortDBAdaptor.QueryParams.STUDY_ID.key(), studyId);
        QueryResult<Long> queryResultAux = cohortDBAdaptor.count(query, userId, StudyAclEntry.StudyPermissions.VIEW_COHORTS);
        return new QueryResult<>("count", queryResultAux.getDbTime(), 0, queryResultAux.first(), queryResultAux.getWarningMsg(),
                queryResultAux.getErrorMsg(), Collections.emptyList());
    }

    @Override
    public QueryResult<Cohort> update(String studyStr, String entryStr, ObjectMap parameters, QueryOptions options, String sessionId)
            throws CatalogException {
        ParamUtils.checkObj(parameters, "Update parameters");
        MyResourceId resource = getId(entryStr, studyStr, sessionId);

        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
                CohortAclEntry.CohortPermissions.UPDATE);

        for (Map.Entry<String, Object> param : parameters.entrySet()) {
            CohortDBAdaptor.QueryParams queryParam = CohortDBAdaptor.QueryParams.getParam(param.getKey());
            if (queryParam == null) {
                throw new CatalogException("Cannot update " + param.getKey());
            }
            switch (queryParam) {
                case NAME:
                case CREATION_DATE:
                case DESCRIPTION:
                case SAMPLES:
                case ATTRIBUTES:
                    break;
                default:
                    throw new CatalogException("Cannot update " + queryParam);
            }
        }

        Cohort cohort = cohortDBAdaptor.get(resource.getResourceId(),
                new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.STATUS_NAME.key())).first();
        if (parameters.containsKey(CohortDBAdaptor.QueryParams.SAMPLES.key())
                || parameters.containsKey(CohortDBAdaptor.QueryParams.NAME.key())/* || params.containsKey("type")*/) {
            switch (cohort.getStatus().getName()) {
                case Cohort.CohortStatus.CALCULATING:
                    throw new CatalogException("Unable to modify a cohort while it's in status \"" + Cohort.CohortStatus.CALCULATING
                            + "\"");
                case Cohort.CohortStatus.READY:
                    parameters.putIfAbsent("status.name", Cohort.CohortStatus.INVALID);
                    break;
                case Cohort.CohortStatus.NONE:
                case Cohort.CohortStatus.INVALID:
                    break;
                default:
                    break;
            }
        }

        QueryResult<Cohort> queryResult = cohortDBAdaptor.update(resource.getResourceId(), parameters, options);
        auditManager.recordUpdate(AuditRecord.Resource.cohort, resource.getResourceId(), resource.getUser(), parameters, null, null);
        return queryResult;
    }

    @Override
    public List<QueryResult<Cohort>> delete(@Nullable String studyStr, String cohortIdStr, ObjectMap options, String sessionId)
            throws CatalogException {
        ParamUtils.checkParameter(cohortIdStr, "id");
        options = ParamUtils.defaultObject(options, ObjectMap::new);

        MyResourceIds resource = getIds(Arrays.asList(StringUtils.split(cohortIdStr, ",")), studyStr, sessionId);
        List<Long> cohortIds = resource.getResourceIds();
        String userId = resource.getUser();

        // Check all the cohorts can be deleted
        for (Long cohortId : cohortIds) {
            authorizationManager.checkCohortPermission(resource.getStudyId(), cohortId, userId, CohortAclEntry.CohortPermissions.DELETE);

            QueryResult<Cohort> myCohortQR = cohortDBAdaptor.get(cohortId, new QueryOptions());
            if (myCohortQR.getNumResults() == 0) {
                throw new CatalogException("Internal error: Cohort " + cohortId + "not found");
            }
            // Check if the cohort can be deleted
            if (myCohortQR.first().getStatus() != null && myCohortQR.first().getStatus().getName() != null
                    && !myCohortQR.first().getStatus().getName().equals(Cohort.CohortStatus.NONE)) {
                throw new CatalogException("Cannot delete cohort " + cohortId + ". The cohort is used in storage.");
            }
        }

        // Delete the cohorts
        List<QueryResult<Cohort>> queryResultList = new ArrayList<>(cohortIds.size());
        for (Long cohortId : cohortIds) {
            QueryResult<Cohort> queryResult = cohortDBAdaptor.delete(cohortId, QueryOptions.empty());
            auditManager.recordDeletion(AuditRecord.Resource.cohort, cohortId, userId, queryResult.first(), null, null);
            queryResultList.add(queryResult);
        }

        return queryResultList;
    }

    @Override
    public QueryResult groupBy(@Nullable String studyStr, Query query, List<String> fields, QueryOptions options, String sessionId)
            throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        options = ParamUtils.defaultObject(options, QueryOptions::new);
        ParamUtils.checkObj(fields, "fields");

        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);

        // Add study id to the query
        query.put(IndividualDBAdaptor.QueryParams.STUDY_ID.key(), studyId);

        QueryResult queryResult = cohortDBAdaptor.groupBy(query, fields, options, userId);

        return ParamUtils.defaultObject(queryResult, QueryResult::new);
    }

    public List<QueryResult<Cohort>> delete(Query query, QueryOptions options, String sessionId) throws CatalogException, IOException {
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.ID.key());
        QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(query, queryOptions);
        List<Long> cohortIds = cohortQueryResult.getResult().stream().map(Cohort::getId).collect(Collectors.toList());
        String cohortIdStr = StringUtils.join(cohortIds, ",");
        return delete(null, cohortIdStr, options, sessionId);
    }

    public List<QueryResult<Cohort>> restore(String cohortIdStr, QueryOptions options, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(cohortIdStr, "id");
        options = ParamUtils.defaultObject(options, QueryOptions::new);

        MyResourceIds resource = getIds(Arrays.asList(StringUtils.split(cohortIdStr, ",")), null, sessionId);

        List<QueryResult<Cohort>> queryResultList = new ArrayList<>(resource.getResourceIds().size());
        for (Long cohortId : resource.getResourceIds()) {
            QueryResult<Cohort> queryResult = null;
            try {
                authorizationManager.checkCohortPermission(resource.getStudyId(), cohortId, resource.getUser(),
                        CohortAclEntry.CohortPermissions.DELETE);
                queryResult = cohortDBAdaptor.restore(cohortId, options);
                auditManager.recordRestore(AuditRecord.Resource.cohort, cohortId, resource.getUser(), Status.DELETED,
                        Cohort.CohortStatus.INVALID, "Cohort restore", null);
            } catch (CatalogAuthorizationException e) {
                auditManager.recordRestore(AuditRecord.Resource.cohort, cohortId, resource.getUser(), null, null, e.getMessage(), null);

                queryResult = new QueryResult<>("Restore cohort " + cohortId);
                queryResult.setErrorMsg(e.getMessage());
            } catch (CatalogException e) {
                e.printStackTrace();
                queryResult = new QueryResult<>("Restore cohort " + cohortId);
                queryResult.setErrorMsg(e.getMessage());
            } finally {
                queryResultList.add(queryResult);
            }
        }

        return queryResultList;
    }

    public List<QueryResult<Cohort>> restore(Query query, QueryOptions options, String sessionId) throws CatalogException {
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CohortDBAdaptor.QueryParams.ID.key());
        QueryResult<Cohort> cohortQueryResult = cohortDBAdaptor.get(query, queryOptions);
        List<Long> cohortIds = cohortQueryResult.getResult().stream().map(Cohort::getId).collect(Collectors.toList());
        String cohortIdStr = StringUtils.join(cohortIds, ",");
        return restore(cohortIdStr, options, sessionId);
    }

    public void setStatus(String id, String status, String message, String sessionId) throws CatalogException {
        MyResourceId resource = getId(id, null, sessionId);

        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
                CohortAclEntry.CohortPermissions.UPDATE);

        if (status != null && !Cohort.CohortStatus.isValid(status)) {
            throw new CatalogException("The status " + status + " is not valid cohort status.");
        }

        ObjectMap parameters = new ObjectMap();
        parameters.putIfNotNull(CohortDBAdaptor.QueryParams.STATUS_NAME.key(), status);
        parameters.putIfNotNull(CohortDBAdaptor.QueryParams.STATUS_MSG.key(), message);

        cohortDBAdaptor.update(resource.getResourceId(), parameters, new QueryOptions());
        auditManager.recordUpdate(AuditRecord.Resource.cohort, resource.getResourceId(), resource.getUser(), parameters, null, null);
    }

    @Override
    public QueryResult rank(String studyStr, Query query, String field, int numResults, boolean asc, String sessionId)
            throws CatalogException {
        query = ParamUtils.defaultObject(query, Query::new);
        ParamUtils.checkObj(field, "field");

        String userId = userManager.getUserId(sessionId);
        long studyId = studyManager.getId(userId, studyStr);
        authorizationManager.checkStudyPermission(studyId, userId, StudyAclEntry.StudyPermissions.VIEW_COHORTS);

        // TODO: In next release, we will have to check the count parameter from the queryOptions object.
        boolean count = true;
        QueryResult queryResult = null;
        if (count) {
            // We do not need to check for permissions when we show the count of files
            queryResult = cohortDBAdaptor.rank(query, field, numResults, asc);
        }

        return ParamUtils.defaultObject(queryResult, QueryResult::new);
    }

    @Override
    public QueryResult<AnnotationSet> createAnnotationSet(String id, @Nullable String studyStr, String variableSetId,
                                                          String annotationSetName, Map<String, Object> annotations,
                                                          Map<String, Object> attributes, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(annotationSetName, "annotationSetName");
        ParamUtils.checkObj(annotations, "annotations");
        attributes = ParamUtils.defaultObject(attributes, HashMap<String, Object>::new);

        MyResourceId resource = getId(id, studyStr, sessionId);
        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
                CohortAclEntry.CohortPermissions.WRITE_ANNOTATIONS);
        MyResourceId variableSetResource = studyManager.getVariableSetId(variableSetId,
                Long.toString(resource.getStudyId()), sessionId);
        QueryResult<VariableSet> variableSet = studyDBAdaptor.getVariableSet(variableSetResource.getResourceId(), null,
                resource.getUser());
        if (variableSet.getNumResults() == 0) {
            // Variable set must be confidential and the user does not have those permissions
            throw new CatalogAuthorizationException("Permission denied: User " + resource.getUser() + " cannot create annotations over "
                    + "that variable set");
        }

        QueryResult<AnnotationSet> annotationSet = createAnnotationSet(resource.getResourceId(), variableSet.first(), annotationSetName,
                annotations, studyManager.getCurrentRelease(resource.getStudyId()), attributes, cohortDBAdaptor);

        auditManager.recordUpdate(AuditRecord.Resource.cohort, resource.getResourceId(), resource.getUser(),
                new ObjectMap("annotationSets", annotationSet.first()), "annotate", null);

        return annotationSet;
    }

    @Deprecated
    @Override
    public QueryResult<AnnotationSet> getAllAnnotationSets(String id, @Nullable String studyStr, String sessionId) throws CatalogException {
        MyResourceId resource = commonGetAllAnnotationSets(id, studyStr, sessionId);
        return cohortDBAdaptor.getAnnotationSet(resource, null,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
    }

    @Deprecated
    @Override
    public QueryResult<ObjectMap> getAllAnnotationSetsAsMap(String id, @Nullable String studyStr, String sessionId) throws
            CatalogException {
        MyResourceId resource = commonGetAllAnnotationSets(id, studyStr, sessionId);
        return cohortDBAdaptor.getAnnotationSetAsMap(resource, null,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
    }

    @Override
    public QueryResult<AnnotationSet> getAnnotationSet(String id, @Nullable String studyStr, String annotationSetName, String sessionId)
            throws CatalogException {
        if (StringUtils.isEmpty(annotationSetName)) {
            MyResourceId resource = commonGetAllAnnotationSets(id, studyStr, sessionId);
            return cohortDBAdaptor.getAnnotationSet(resource, null,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
        } else {
            MyResourceId resource = commonGetAnnotationSet(id, studyStr, annotationSetName, sessionId);
            return cohortDBAdaptor.getAnnotationSet(resource, annotationSetName,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
        }
    }

    @Override
    public QueryResult<ObjectMap> getAnnotationSetAsMap(String id, @Nullable String studyStr, String annotationSetName, String sessionId)
            throws CatalogException {
        if (StringUtils.isEmpty(annotationSetName)) {
            MyResourceId resource = commonGetAllAnnotationSets(id, studyStr, sessionId);
            return cohortDBAdaptor.getAnnotationSetAsMap(resource, null,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
        } else {
            MyResourceId resource = commonGetAnnotationSet(id, studyStr, annotationSetName, sessionId);
            return cohortDBAdaptor.getAnnotationSetAsMap(resource, annotationSetName,
                    StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
        }
    }

    @Override
    public QueryResult<AnnotationSet> updateAnnotationSet(String id, @Nullable String studyStr, String annotationSetName,
                                                          Map<String, Object> newAnnotations, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(id, "id");
        ParamUtils.checkParameter(annotationSetName, "annotationSetName");
        ParamUtils.checkObj(newAnnotations, "newAnnotations");

        MyResourceId resource = getId(id, studyStr, sessionId);
        long cohortId = resource.getResourceId();
        authorizationManager.checkCohortPermission(resource.getStudyId(), cohortId, resource.getUser(), CohortAclEntry.CohortPermissions
                .WRITE_ANNOTATIONS);

        // Update the annotation
        QueryResult<AnnotationSet> queryResult = updateAnnotationSet(resource, annotationSetName, newAnnotations, cohortDBAdaptor);

        if (queryResult == null || queryResult.getNumResults() == 0) {
            throw new CatalogException("There was an error with the update");
        }

        AnnotationSet annotationSet = queryResult.first();

        // Audit the changes
        AnnotationSet annotationSetUpdate = new AnnotationSet(annotationSet.getName(), annotationSet.getVariableSetId(),
                newAnnotations.entrySet().stream()
                        .map(entry -> new Annotation(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toSet()), annotationSet.getCreationDate(), 1, null);
        auditManager.recordUpdate(AuditRecord.Resource.cohort, cohortId, resource.getUser(), new ObjectMap("annotationSets",
                Collections.singletonList(annotationSetUpdate)), "update annotation", null);

        return queryResult;
    }

    @Override
    public QueryResult<AnnotationSet> deleteAnnotationSet(String id, @Nullable String studyStr, String annotationSetName, String sessionId)
            throws CatalogException {
        ParamUtils.checkParameter(id, "id");
        ParamUtils.checkParameter(annotationSetName, "annotationSetName");

        MyResourceId resource = getId(id, studyStr, sessionId);
        long cohortId = resource.getResourceId();
        authorizationManager.checkCohortPermission(resource.getStudyId(), cohortId, resource.getUser(),
                CohortAclEntry.CohortPermissions.DELETE_ANNOTATIONS);

        QueryResult<AnnotationSet> annotationSet = cohortDBAdaptor.getAnnotationSet(cohortId, annotationSetName);
        if (annotationSet == null || annotationSet.getNumResults() == 0) {
            throw new CatalogException("Could not delete annotation set. The annotation set with name " + annotationSetName + " could not "
                    + "be found in the database.");
        }
        // We make this query because it will check the proper permissions in case the variable set is confidential
        studyDBAdaptor.getVariableSet(annotationSet.first().getVariableSetId(), new QueryOptions(), resource.getUser());

        cohortDBAdaptor.deleteAnnotationSet(cohortId, annotationSetName);

        auditManager.recordDeletion(AuditRecord.Resource.cohort, cohortId, resource.getUser(), new ObjectMap("annotationSets",
                Collections.singletonList(annotationSet.first())), "delete annotation", null);

        return annotationSet;
    }

    @Override
    public QueryResult<ObjectMap> searchAnnotationSetAsMap(String id, @Nullable String studyStr, String variableSetStr,
                                                           @Nullable String annotation, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(id, "id");

        MyResourceId resource = getId(id, studyStr, sessionId);
//        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
//                CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS);

        long variableSetId = -1;
        if (StringUtils.isNotEmpty(variableSetStr)) {
            variableSetId = studyManager.getVariableSetId(variableSetStr, Long.toString(resource.getStudyId()),
                    sessionId).getResourceId();
        }

        return cohortDBAdaptor.searchAnnotationSetAsMap(resource, variableSetId, annotation,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
    }

    @Override
    public QueryResult<AnnotationSet> searchAnnotationSet(String id, @Nullable String studyStr, String variableSetStr,
                                                          @Nullable String annotation, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(id, "id");

        MyResourceId resource = getId(id, studyStr, sessionId);
//        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
//                CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS);

        long variableSetId = -1;
        if (StringUtils.isNotEmpty(variableSetStr)) {
            variableSetId = studyManager.getVariableSetId(variableSetStr, Long.toString(resource.getStudyId()),
                    sessionId).getResourceId();
        }

        return cohortDBAdaptor.searchAnnotationSet(resource, variableSetId, annotation,
                StudyAclEntry.StudyPermissions.VIEW_COHORT_ANNOTATIONS.toString());
    }

    private MyResourceId commonGetAllAnnotationSets(String id, @Nullable String studyStr, String sessionId) throws CatalogException {
        ParamUtils.checkParameter(id, "id");
        return getId(id, studyStr, sessionId);
//        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
//                CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS);
//        return resource.getResourceId();
    }

    private MyResourceId commonGetAnnotationSet(String id, @Nullable String studyStr, String annotationSetName, String sessionId)
            throws CatalogException {
        ParamUtils.checkParameter(id, "id");
        ParamUtils.checkAlias(annotationSetName, "annotationSetName", configuration.getCatalog().getOffset());
        return getId(id, studyStr, sessionId);
//        authorizationManager.checkCohortPermission(resource.getStudyId(), resource.getResourceId(), resource.getUser(),
//                CohortAclEntry.CohortPermissions.VIEW_ANNOTATIONS);
//        return resource.getResourceId();
    }


    // **************************   ACLs  ******************************** //
    public List<QueryResult<CohortAclEntry>> getAcls(String studyStr, List<String> cohortList, String member,
                                                     boolean silent, String sessionId) throws CatalogException {
        MyResourceIds resource = getIds(cohortList, studyStr, silent, sessionId);

        List<QueryResult<CohortAclEntry>> cohortAclList = new ArrayList<>(resource.getResourceIds().size());
        List<Long> resourceIds = resource.getResourceIds();
        for (int i = 0; i < resourceIds.size(); i++) {
            Long cohortId = resourceIds.get(i);
            try {
                QueryResult<CohortAclEntry> allCohortAcls;
                if (StringUtils.isNotEmpty(member)) {
                    allCohortAcls =
                            authorizationManager.getCohortAcl(resource.getStudyId(), cohortId, resource.getUser(), member);
                } else {
                    allCohortAcls = authorizationManager.getAllCohortAcls(resource.getStudyId(), cohortId, resource.getUser());
                }
                allCohortAcls.setId(String.valueOf(cohortId));
                cohortAclList.add(allCohortAcls);
            } catch (CatalogException e) {
                if (silent) {
                    cohortAclList.add(new QueryResult<>(cohortList.get(i), 0, 0, 0, "", e.toString(), new ArrayList<>(0)));
                } else {
                    throw e;
                }
            }
        }
        return cohortAclList;
    }

    public List<QueryResult<CohortAclEntry>> updateAcl(String studyStr, List<String> cohortList, String memberIds, AclParams aclParams,
                                                       String sessionId) throws CatalogException {
        if (cohortList == null || cohortList.isEmpty()) {
            throw new CatalogException("Missing cohort parameter");
        }

        if (aclParams.getAction() == null) {
            throw new CatalogException("Invalid action found. Please choose a valid action to be performed.");
        }

        List<String> permissions = Collections.emptyList();
        if (StringUtils.isNotEmpty(aclParams.getPermissions())) {
            permissions = Arrays.asList(aclParams.getPermissions().trim().replaceAll("\\s", "").split(","));
            checkPermissions(permissions, CohortAclEntry.CohortPermissions::valueOf);
        }

        // Obtain the resource ids
        MyResourceIds resourceIds = getIds(cohortList, studyStr, sessionId);

        authorizationManager.checkCanAssignOrSeePermissions(resourceIds.getStudyId(), resourceIds.getUser());

        // Validate that the members are actually valid members
        List<String> members;
        if (memberIds != null && !memberIds.isEmpty()) {
            members = Arrays.asList(memberIds.split(","));
        } else {
            members = Collections.emptyList();
        }
        checkMembers(resourceIds.getStudyId(), members);
        authorizationManager.checkNotAssigningPermissionsToAdminsGroup(members);
//        studyManager.membersHavePermissionsInStudy(resourceIds.getStudyId(), members);

        String collectionName = MongoDBAdaptorFactory.COHORT_COLLECTION;

        switch (aclParams.getAction()) {
            case SET:
                return authorizationManager.setAcls(resourceIds.getStudyId(), resourceIds.getResourceIds(), members, permissions,
                        collectionName);
            case ADD:
                return authorizationManager.addAcls(resourceIds.getStudyId(), resourceIds.getResourceIds(), members, permissions,
                        collectionName);
            case REMOVE:
                return authorizationManager.removeAcls(resourceIds.getResourceIds(), members, permissions, collectionName);
            case RESET:
                return authorizationManager.removeAcls(resourceIds.getResourceIds(), members, null, collectionName);
            default:
                throw new CatalogException("Unexpected error occurred. No valid action found.");
        }
    }

    private long getCohortId(boolean silent, String cohortStr) throws CatalogException {
        long cohortId = Long.parseLong(cohortStr);
        try {
            cohortDBAdaptor.checkId(cohortId);
        } catch (CatalogException e) {
            if (silent) {
                return -1L;
            } else {
                throw e;
            }
        }
        return cohortId;
    }

}
