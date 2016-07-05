package org.opencb.opencga.catalog.db.mongodb;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.opencga.catalog.db.api.CatalogCohortDBAdaptor;
import org.opencb.opencga.catalog.db.api.CatalogDBIterator;
import org.opencb.opencga.catalog.db.api.CatalogSampleDBAdaptor;
import org.opencb.opencga.catalog.db.mongodb.converters.CohortConverter;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.models.*;
import org.opencb.opencga.catalog.models.acls.CohortAcl;
import org.opencb.opencga.core.common.TimeUtils;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Math.toIntExact;
import static org.opencb.opencga.catalog.db.mongodb.CatalogMongoDBUtils.*;
import static org.opencb.opencga.catalog.utils.CatalogMemberValidator.checkMembers;

public class CatalogMongoCohortDBAdaptor extends CatalogMongoDBAdaptor implements CatalogCohortDBAdaptor {

    private final MongoDBCollection cohortCollection;
    private CohortConverter cohortConverter;

    public CatalogMongoCohortDBAdaptor(MongoDBCollection cohortCollection, CatalogMongoDBAdaptorFactory dbAdaptorFactory) {
        super(LoggerFactory.getLogger(CatalogMongoSampleDBAdaptor.class));
        this.dbAdaptorFactory = dbAdaptorFactory;
        this.cohortCollection = cohortCollection;
        this.cohortConverter = new CohortConverter();
    }

    @Override
    public QueryResult<Cohort> createCohort(long studyId, Cohort cohort, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();

        dbAdaptorFactory.getCatalogStudyDBAdaptor().checkStudyId(studyId);
        checkCohortNameExists(studyId, cohort.getName());

        long newId = dbAdaptorFactory.getCatalogMetaDBAdaptor().getNewAutoIncrementId();
        cohort.setId(newId);

        Document cohortObject = cohortConverter.convertToStorageType(cohort);
        cohortObject.append(PRIVATE_STUDY_ID, studyId);
        cohortObject.append(PRIVATE_ID, newId);

        try {
            cohortCollection.insert(cohortObject, null);
        } catch (MongoWriteException e) {
            throw ifDuplicateKeyException(() -> CatalogDBException.alreadyExists("Cohort", studyId, "name", cohort.getName(), e), e);
        }

        return endQuery("createCohort", startTime, getCohort(newId, options));
    }

    @Override
    public QueryResult<Cohort> getCohort(long cohortId, QueryOptions options) throws CatalogDBException {
        return get(new Query(QueryParams.ID.key(), cohortId).append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED), options);
    }

    @Override
    public QueryResult<Cohort> getAllCohorts(long studyId, QueryOptions options) throws CatalogDBException {
        return get(new Query(QueryParams.STUDY_ID.key(), studyId), options);
    }

    @Override
    public QueryResult<Cohort> modifyCohort(long cohortId, ObjectMap parameters, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        update(new Query(QueryParams.ID.key(), cohortId), parameters);
        return endQuery("Modify cohort", startTime, getCohort(cohortId, options));
    }

    @Override
    public QueryResult<Cohort> deleteCohort(long cohortId, QueryOptions queryOptions) throws CatalogDBException {
        return delete(cohortId, queryOptions);
    }

    @Override
    public QueryResult<AnnotationSet> annotateCohort(long cohortId, AnnotationSet annotationSet, boolean overwrite)
            throws CatalogDBException {
        long startTime = startQuery();

        QueryResult<Long> count = cohortCollection.count(new Document("annotationSets.name", annotationSet.getName())
                .append(PRIVATE_ID, cohortId));
        if (overwrite) {
            if (count.getResult().get(0) == 0) {
                throw CatalogDBException.idNotFound("AnnotationSet", annotationSet.getName());
            }
        } else {
            if (count.getResult().get(0) > 0) {
                throw CatalogDBException.alreadyExists("AnnotationSet", "name", annotationSet.getName());
            }
        }

        Document object = getMongoDBDocument(annotationSet, "AnnotationSet");

        Bson query = new Document(PRIVATE_ID, cohortId);
        if (overwrite) {
            ((Document) query).put("annotationSets.name", annotationSet.getName());
        } else {
            ((Document) query).put("annotationSets.name", new Document("$ne", annotationSet.getName()));
        }

        Bson update;
        if (overwrite) {
            update = Updates.set("annotationSets.$", object);
        } else {
            update = Updates.push("annotationSets", object);
        }

        QueryResult<UpdateResult> queryResult = cohortCollection.update(query, update, null);

        if (queryResult.first().getModifiedCount() != 1) {
            throw CatalogDBException.alreadyExists("AnnotationSet", "name", annotationSet.getName());
        }

        return endQuery("", startTime, Collections.singletonList(annotationSet));
    }

    /**
     * The method will add the new variable to each annotation using the default value.
     * @param variableSetId id of the variableSet.
     * @param variable new variable that will be pushed to the annotations.
     */
    @Override
    public QueryResult<Long> addVariableToAnnotations(long variableSetId, Variable variable) throws CatalogDBException {
        long startTime = startQuery();

        Annotation annotation = new Annotation(variable.getName(), variable.getDefaultValue());
        // Obtain the annotation ids of the annotations that are using the variableSet variableSetId
        List<Bson> aggregation = new ArrayList<>(4);
        aggregation.add(Aggregates.match(Filters.eq("annotationSets.variableSetId", variableSetId)));
        aggregation.add(Aggregates.unwind("$annotationSets"));
        aggregation.add(Aggregates.project(Projections.include("annotationSets.name", "annotationSets.variableSetId")));
        aggregation.add(Aggregates.match(Filters.eq("annotationSets.variableSetId", variableSetId)));
        QueryResult<Document> aggregationResult = cohortCollection.aggregate(aggregation, null);

        Set<String> annotationIds = new HashSet<>(aggregationResult.getNumResults());
        for (Document document : aggregationResult.getResult()) {
            annotationIds.add((String) ((Document) document.get("annotationSets")).get("name"));
        }

        Bson bsonQuery;
        Bson update = Updates.push("annotationSets.$." + CatalogSampleDBAdaptor.AnnotationSetParams.ANNOTATIONS.key(),
                getMongoDBDocument(annotation, "annotation"));
        long modifiedCount = 0;
        for (String annotationId : annotationIds) {
            bsonQuery = Filters.elemMatch("annotationSets", Filters.and(
                    Filters.eq("variableSetId", variableSetId),
                    Filters.eq("name", annotationId)
            ));

            modifiedCount += cohortCollection.update(bsonQuery, update, new QueryOptions(MongoDBCollection.MULTI, true)).first()
                    .getModifiedCount();
        }

        return endQuery("Add new variable to annotations", startTime, Collections.singletonList(modifiedCount));
    }

    @Override
    public QueryResult<Long> renameAnnotationField(long variableSetId, String oldName, String newName) throws CatalogDBException {
        long renamedAnnotations = 0;

        // 1. we obtain the variable
        List<Cohort> cohortAnnotations = getAnnotation(variableSetId, oldName);

        if (cohortAnnotations.size() > 0) {
            // Fixme: Change the hard coded annotationSets names per their corresponding QueryParam objects.
            for (Cohort cohort : cohortAnnotations) {
                for (AnnotationSet annotationSet : cohort.getAnnotationSets()) {
                    Bson bsonQuery = Filters.and(
                            Filters.eq(QueryParams.ID.key(), cohort.getId()),
                            Filters.eq("annotationSets.name", annotationSet.getName()),
                            Filters.eq("annotationSets.annotations.name", oldName)
                    );

                    // 1. We extract the annotation.
                    Bson update = Updates.pull("annotationSets.$.annotations", Filters.eq("name", oldName));
                    QueryResult<UpdateResult> queryResult = cohortCollection.update(bsonQuery, update, null);
                    if (queryResult.first().getModifiedCount() != 1) {
                        throw new CatalogDBException("VariableSet {id: " + variableSetId + "} - AnnotationSet {name: "
                                + annotationSet.getName() + "} - An unexpected error happened when extracting the annotation " + oldName
                                + ". Please, report this error to the OpenCGA developers.");
                    }

                    // 2. We change the id and push it again
                    Iterator<Annotation> iterator = annotationSet.getAnnotations().iterator();
                    Annotation annotation = iterator.next();
                    annotation.setName(newName);
                    bsonQuery = Filters.and(
                            Filters.eq(QueryParams.ID.key(), cohort.getId()),
                            Filters.eq("annotationSets.name", annotationSet.getName())
                    );
                    update = Updates.push("annotationSets.$.annotations", getMongoDBDocument(annotation, "Annotation"));
                    queryResult = cohortCollection.update(bsonQuery, update, null);

                    if (queryResult.first().getModifiedCount() != 1) {
                        throw new CatalogDBException("VariableSet {id: " + variableSetId + "} - AnnotationSet {name: "
                                + annotationSet.getName() + "} - A critical error happened when trying to rename the annotation " + oldName
                                + ". Please, report this error to the OpenCGA developers.");
                    }
                    renamedAnnotations += 1;
                }
            }
        }

        return new QueryResult<>("Rename annotation field", -1, toIntExact(renamedAnnotations), renamedAnnotations, "", "",
                Collections.singletonList(renamedAnnotations));
    }

    /**
     * The method will return the list of cohorts containing the annotation.
     * @param variableSetId Id of the variableSet.
     * @param annotationFieldId Name of the field of the annotation from all the annotationSets.
     * @return list of cohorts containing an array of annotationSets, containing just the annotation that matches with annotationFieldId.
     */
    private List<Cohort> getAnnotation(long variableSetId, String annotationFieldId) {
        // Fixme: Change the hard coded annotationSets names per their corresponding QueryParam objects.
        List<Bson> aggregation = new ArrayList<>();
        aggregation.add(Aggregates.match(Filters.elemMatch("annotationSets", Filters.eq("variableSetId", variableSetId))));
        aggregation.add(Aggregates.project(Projections.include("annotationSets", "id")));
        aggregation.add(Aggregates.unwind("$annotationSets"));
        aggregation.add(Aggregates.match(Filters.eq("annotationSets.variableSetId", variableSetId)));
        aggregation.add(Aggregates.unwind("$annotationSets.annotations"));
        aggregation.add(Aggregates.match(
                Filters.eq("annotationSets.annotations.name", annotationFieldId)));

        return cohortCollection.aggregate(aggregation, cohortConverter, new QueryOptions()).getResult();
    }

    @Override
    public QueryResult<Long> removeAnnotationField(long variableSetId, String fieldId) throws CatalogDBException {
        long renamedAnnotations = 0;

        // 1. we obtain the variable
        List<Cohort> cohortAnnotations = getAnnotation(variableSetId, fieldId);

        if (cohortAnnotations.size() > 0) {
            // Fixme: Change the hard coded annotationSets names per their corresponding QueryParam objects.
            for (Cohort cohort : cohortAnnotations) {
                for (AnnotationSet annotationSet : cohort.getAnnotationSets()) {
                    Bson bsonQuery = Filters.and(
                            Filters.eq(QueryParams.ID.key(), cohort.getId()),
                            Filters.eq("annotationSets.name", annotationSet.getName()),
                            Filters.eq("annotationSets.annotations.name", fieldId)
                    );

                    // We extract the annotation.
                    Bson update = Updates.pull("annotationSets.$.annotations", Filters.eq("name", fieldId));
                    QueryResult<UpdateResult> queryResult = cohortCollection.update(bsonQuery, update, null);
                    if (queryResult.first().getModifiedCount() != 1) {
                        throw new CatalogDBException("VariableSet {id: " + variableSetId + "} - AnnotationSet {name: "
                                + annotationSet.getName() + "} - An unexpected error happened when extracting the annotation " + fieldId
                                + ". Please, report this error to the OpenCGA developers.");
                    }

                    renamedAnnotations += 1;
                }
            }
        }

        return new QueryResult<>("Remove annotation field", -1, toIntExact(renamedAnnotations), renamedAnnotations, "", "",
                Collections.singletonList(renamedAnnotations));
    }

    @Override
    public QueryResult<AnnotationSet> deleteAnnotation(long cohortId, String annotationId) throws CatalogDBException {
        long startTime = startQuery();

        Cohort cohort = getCohort(cohortId, new QueryOptions("include", "projects.studies.cohorts.annotationSets")).first();
        AnnotationSet annotationSet = null;
        for (AnnotationSet as : cohort.getAnnotationSets()) {
            if (as.getName().equals(annotationId)) {
                annotationSet = as;
                break;
            }
        }

        if (annotationSet == null) {
            throw CatalogDBException.idNotFound("AnnotationSet", annotationId);
        }

        Bson query = new Document(PRIVATE_ID, cohortId);
        Bson update = Updates.pull("annotationSets", new Document("name", annotationId));
        QueryResult<UpdateResult> resultQueryResult = cohortCollection.update(query, update, null);
        if (resultQueryResult.first().getModifiedCount() < 1) {
            throw CatalogDBException.idNotFound("AnnotationSet", annotationId);
        }

        return endQuery("Delete annotation", startTime, Collections.singletonList(annotationSet));
    }

    @Override
    public QueryResult<CohortAcl> getCohortAcl(long cohortId, List<String> members) throws CatalogDBException {
        long startTime = startQuery();

        checkCohortId(cohortId);

        Bson match = Aggregates.match(Filters.eq(PRIVATE_ID, cohortId));
        Bson unwind = Aggregates.unwind("$" + QueryParams.ACLS.key());
        Bson match2 = Aggregates.match(Filters.in(QueryParams.ACLS_MEMBER.key(), members));
        Bson project = Aggregates.project(Projections.include(QueryParams.ID.key(), QueryParams.ACLS.key()));

        List<CohortAcl> cohortAcl = null;
        QueryResult<Document> aggregate = cohortCollection.aggregate(Arrays.asList(match, unwind, match2, project), null);
        Cohort cohort = cohortConverter.convertToDataModelType(aggregate.first());

        if (cohort != null) {
            cohortAcl = cohort.getAcls();
        }

        return endQuery("get cohort Acl", startTime, cohortAcl);
    }

    @Override
    public QueryResult<CohortAcl> setCohortAcl(long cohortId, CohortAcl acl, boolean override) throws CatalogDBException {
        long startTime = startQuery();
        long studyId = getStudyIdByCohortId(cohortId);

        String member = acl.getMember();

        // If there is a group in acl.getMember(), we will obtain all the users belonging to the groups and will check if any of them
        // already have permissions on its own.
        if (member.startsWith("@")) {
            Group group = dbAdaptorFactory.getCatalogStudyDBAdaptor().getGroup(studyId, member, Collections.emptyList()).first();

            // Check if any user already have permissions set on their own.
            QueryResult<CohortAcl> fileAcl = getCohortAcl(cohortId, group.getUserIds());
            if (fileAcl.getNumResults() > 0) {
                throw new CatalogDBException("Error when adding permissions in cohort. At least one user in " + group.getName()
                        + " has already defined permissions for cohort " + cohortId);
            }
        } else {
            // Check if the members of the new acl already have some permissions set
            QueryResult<CohortAcl> cohortAcls = getCohortAcl(cohortId, acl.getMember());

            if (cohortAcls.getNumResults() > 0 && override) {
                unsetCohortAcl(cohortId, Arrays.asList(member), Collections.emptyList());
            } else if (cohortAcls.getNumResults() > 0 && !override) {
                throw new CatalogDBException("setCohortAcl: " + member + " already had an Acl set. If you "
                        + "still want to set a new Acl and remove the old one, please use the override parameter.");
            }
        }

        // Push the new acl to the list of acls.
        Document queryDocument = new Document(PRIVATE_ID, cohortId);
        Document update = new Document("$push", new Document(QueryParams.ACLS.key(), getMongoDBDocument(acl, "CohortAcl")));
        QueryResult<UpdateResult> updateResult = cohortCollection.update(queryDocument, update, null);

        if (updateResult.first().getModifiedCount() == 0) {
            throw new CatalogDBException("setCohortAcl: An error occurred when trying to share cohort " + cohortId + " with " + member);
        }

        return endQuery("setCohortAcl", startTime, Arrays.asList(acl));
    }

    @Override
    public void unsetCohortAcl(long cohortId, List<String> members, List<String> permissions) throws CatalogDBException {
        // Check that all the members (users) are correct and exist.
        checkMembers(dbAdaptorFactory, getStudyIdByCohortId(cohortId), members);

        // Remove the permissions the members might have had
        for (String member : members) {
            Document query = new Document(PRIVATE_ID, cohortId).append(QueryParams.ACLS_MEMBER.key(), member);
            Bson update;
            if (permissions.size() == 0) {
                update = new Document("$pull", new Document("acls", new Document("member", member)));
            } else {
                update = new Document("$pull", new Document("acls.$.permissions", new Document("$in", permissions)));
            }
            QueryResult<UpdateResult> updateResult = cohortCollection.update(query, update, null);
            if (updateResult.first().getModifiedCount() == 0) {
                throw new CatalogDBException("unsetCohortAcl: An error occurred when trying to stop sharing cohort " + cohortId
                        + " with other " + member + ".");
            }
        }

//        // Remove possible cohortAcls that might have permissions defined but no users
//        Bson queryBson = new Document(QueryParams.ID.key(), cohortId)
//                .append(QueryParams.ACLS_MEMBER.key(),
//                        new Document("$exists", true).append("$eq", Collections.emptyList()));
//        Bson update = new Document("$pull", new Document("acls", new Document("users", Collections.emptyList())));
//        cohortCollection.update(queryBson, update, null);
    }

    @Override
    public void unsetCohortAclsInStudy(long studyId, List<String> members) throws CatalogDBException {
        // Check that all the members (users) are correct and exist.
        checkMembers(dbAdaptorFactory, studyId, members);

        // Remove the permissions the members might have had
        for (String member : members) {
            Document query = new Document(PRIVATE_STUDY_ID, studyId).append(QueryParams.ACLS_MEMBER.key(), member);
            Bson update = new Document("$pull", new Document("acls", new Document("member", member)));
            cohortCollection.update(query, update, new QueryOptions(MongoDBCollection.MULTI, true));
        }

//        // Remove possible CohortAcls that might have permissions defined but no users
//        Bson queryBson = new Document(PRIVATE_STUDY_ID, studyId)
//                .append(CatalogSampleDBAdaptor.QueryParams.ACLS_MEMBER.key(),
//                        new Document("$exists", true).append("$eq", Collections.emptyList()));
//        Bson update = new Document("$pull", new Document("acls", new Document("users", Collections.emptyList())));
//        cohortCollection.update(queryBson, update, new QueryOptions(MongoDBCollection.MULTI, true));
    }

    @Override
    public long getStudyIdByCohortId(long cohortId) throws CatalogDBException {
        checkCohortId(cohortId);
        QueryResult queryResult = nativeGet(new Query(QueryParams.ID.key(), cohortId),
                new QueryOptions(MongoDBCollection.INCLUDE, PRIVATE_STUDY_ID));
        if (queryResult.getResult().isEmpty()) {
            throw CatalogDBException.idNotFound("Cohort", cohortId);
        } else {
            return ((Document) queryResult.first()).getLong(PRIVATE_STUDY_ID);
        }
    }

    @Override
    public QueryResult<Long> count(Query query) throws CatalogDBException {
        long startTime = startQuery();
        return endQuery("Count cohort", startTime, cohortCollection.count(parseQuery(query)));
    }

    @Override
    public QueryResult distinct(Query query, String field) throws CatalogDBException {
        Bson bson = parseQuery(query);
        return cohortCollection.distinct(field, bson);
    }

    @Override
    public QueryResult stats(Query query) {
        return null;
    }

    @Override
    public QueryResult<Cohort> get(Query query, QueryOptions options) throws CatalogDBException {
        long startTime = startQuery();
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.TRASHED + ";!=" + Status.DELETED);
        }
        Bson bson = parseQuery(query);
        QueryOptions qOptions;
        if (options != null) {
            qOptions = options;
        } else {
            qOptions = new QueryOptions();
        }
        qOptions = filterOptions(qOptions, FILTER_ROUTE_COHORTS);
        QueryResult<Cohort> cohortQueryResult = cohortCollection.find(bson, cohortConverter, qOptions);
        return endQuery("Get cohort", startTime, cohortQueryResult);
    }

    @Override
    public QueryResult nativeGet(Query query, QueryOptions options) throws CatalogDBException {
        Bson bson = parseQuery(query);
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.TRASHED + ";!=" + Status.DELETED);
        }
        QueryOptions qOptions;
        if (options != null) {
            qOptions = options;
        } else {
            qOptions = new QueryOptions();
        }
        qOptions = filterOptions(qOptions, FILTER_ROUTE_COHORTS);
        return cohortCollection.find(bson, qOptions);
    }

    @Override
    public QueryResult<Cohort> update(long id, ObjectMap parameters) throws CatalogDBException {
        long startTime = startQuery();
        update(new Query(QueryParams.ID.key(), id), parameters);
        return endQuery("Update cohort", startTime, getCohort(id, new QueryOptions()));
    }

    @Override
    public QueryResult<Long> update(Query query, ObjectMap parameters) throws CatalogDBException {
        long startTime = startQuery();
        Map<String, Object> cohortParams = new HashMap<>();

        String[] acceptedParams = {QueryParams.DESCRIPTION.key(), QueryParams.NAME.key(), QueryParams.CREATION_DATE.key()};
        filterStringParams(parameters, cohortParams, acceptedParams);

        Map<String, Class<? extends Enum>> acceptedEnums = Collections.singletonMap(QueryParams.TYPE.key(), Study.Type.class);
        filterEnumParams(parameters, cohortParams, acceptedEnums);

        String[] acceptedLongListParams = {QueryParams.SAMPLES.key()};
        filterLongListParams(parameters, cohortParams, acceptedLongListParams);
        if (parameters.containsKey(QueryParams.SAMPLES.key())) {
            for (Long sampleId : parameters.getAsLongList(QueryParams.SAMPLES.key())) {
                if (!dbAdaptorFactory.getCatalogSampleDBAdaptor().sampleExists(sampleId)) {
                    throw CatalogDBException.idNotFound("Sample", sampleId);
                }
            }
        }

        String[] acceptedMapParams = {QueryParams.ATTRIBUTES.key(), QueryParams.STATS.key()};
        filterMapParams(parameters, cohortParams, acceptedMapParams);

        //Map<String, Class<? extends Enum>> acceptedEnumParams = Collections.singletonMap(QueryParams.STATUS_NAME.key(),
        //        Cohort.CohortStatus.class);
        //filterEnumParams(parameters, cohortParams, acceptedEnumParams);
        if (parameters.containsKey(QueryParams.STATUS_NAME.key())) {
            cohortParams.put(QueryParams.STATUS_NAME.key(), parameters.get(QueryParams.STATUS_NAME.key()));
            cohortParams.put(QueryParams.STATUS_DATE.key(), TimeUtils.getTime());
        }
        if (parameters.containsKey("status")) {
            throw new CatalogDBException("Unable to modify cohort. Use parameter \"" + QueryParams.STATUS_NAME.key()
                    + "\" instead of \"status\"");
        }

        if (!cohortParams.isEmpty()) {
            QueryResult<UpdateResult> update = cohortCollection.update(parseQuery(query), new Document("$set", cohortParams), null);
            return endQuery("Update cohort", startTime, Arrays.asList(update.getNumTotalResults()));
        }

        return endQuery("Update cohort", startTime, new QueryResult<>());
    }

    @Override
    public QueryResult<Cohort> delete(long id, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();

        checkCohortId(id);
        // Check if the cohort is active
        Query query = new Query(QueryParams.ID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), "!=" + Status.TRASHED + ";!=" + Status.DELETED);
        if (count(query).first() == 0) {
            query.put(QueryParams.STATUS_NAME.key(), Status.TRASHED + "," + Status.DELETED);
            QueryOptions options = new QueryOptions(MongoDBCollection.INCLUDE, QueryParams.STATUS_NAME.key());
            Cohort cohort = get(query, options).first();
            throw new CatalogDBException("The cohort {" + id + "} was already " + cohort.getStatus().getName());
        }

        // Change the status of the cohort to deleted
        setStatus(id, Status.TRASHED);

        query = new Query(QueryParams.ID.key(), id).append(QueryParams.STATUS_NAME.key(), Status.TRASHED);

        return endQuery("Delete cohort", startTime, get(query, null));
    }

    @Override
    public QueryResult<Long> delete(Query query, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();
        query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.TRASHED + ";!=" + Status.DELETED);
        QueryResult<Cohort> cohortQueryResult = get(query, new QueryOptions(MongoDBCollection.INCLUDE, QueryParams.ID.key()));
        for (Cohort cohort : cohortQueryResult.getResult()) {
            delete(cohort.getId(), queryOptions);
        }
        return endQuery("Delete cohort", startTime, Collections.singletonList(cohortQueryResult.getNumTotalResults()));
    }

    QueryResult<Cohort> setStatus(long cohortId, String status) throws CatalogDBException {
        return update(cohortId, new ObjectMap(QueryParams.STATUS_NAME.key(), status));
    }

    QueryResult<Long> setStatus(Query query, String status) throws CatalogDBException {
        return update(query, new ObjectMap(QueryParams.STATUS_NAME.key(), status));
    }

    @Override
    public QueryResult<Cohort> remove(long id, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Operation not yet supported.");
//        long startTime = startQuery();
//        if (queryOptions == null) {
//            queryOptions = new QueryOptions();
//        }
//        QueryResult<Long> remove = remove(new Query(QueryParams.ID.key(), id), queryOptions);
//        if (remove.getResult().get(0) != 1L) {
//            throw CatalogDBException.removeError("Cohort");
//        }
//        Query query = new Query(QueryParams.ID.key(), id).append(QueryParams.STATUS_NAME.key(), Cohort.CohortStatus.DELETED);
//        return endQuery("Remove cohort", startTime, get(query, new QueryOptions()));
    }

    @Override
    public QueryResult<Long> remove(Query query, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Operation not yet supported.");
//        long startTime = startQuery();
//        query.append(QueryParams.STATUS_NAME.key(), Cohort.CohortStatus.NONE + "," + Cohort.CohortStatus.TRASHED);
//
//        // First we obtain the ids of the cohorts that will be removed.
//        List<Cohort> cohorts = get(query, new QueryOptions(MongoDBCollection.INCLUDE,
//                Arrays.asList(QueryParams.ID.key(), QueryParams.STATUS_NAME))).getResult();
//
//        QueryResult<Long> removed = update(query, new ObjectMap(QueryParams.STATUS_NAME.key(), Cohort.CohortStatus.DELETED));
//
//        if (removed.first() != cohorts.size()) {
//            throw CatalogDBException.removeError("Cohort");
//        }
//
//        // Remove the instances to cohort that are stored in study
//        dbAdaptorFactory.getCatalogStudyDBAdaptor().removeCohortDependencies(
//                cohorts.stream()
//                        .filter(c -> c.getName().getName() != Cohort.CohortStatus.TRASHED)
//                        .map(Cohort::getId).collect(Collectors.toList())
//        );
//
//        return endQuery("Remove cohorts", startTime, Collections.singletonList(removed.first()));
    }

    @Override
    public QueryResult<Cohort> restore(long id, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();

        checkCohortId(id);
        // Check if the cohort is active
        Query query = new Query(QueryParams.ID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), Status.TRASHED);
        if (count(query).first() == 0) {
            throw new CatalogDBException("The cohort {" + id + "} is not deleted");
        }

        // Change the status of the cohort to deleted
        setStatus(id, Cohort.CohortStatus.NONE);
        query = new Query(QueryParams.ID.key(), id);

        return endQuery("Restore cohort", startTime, get(query, null));
    }

    @Override
    public QueryResult<Long> restore(Query query, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();
        query.put(QueryParams.STATUS_NAME.key(), Status.TRASHED);
        return endQuery("Restore cohorts", startTime, setStatus(query, Cohort.CohortStatus.NONE));
    }

//    @Override
//    public QueryResult<Long> updateStatus(Query query, String status) throws CatalogDBException {
//        long startTime = startQuery();
//        QueryResult<UpdateResult> update = cohortCollection.update(parseQuery(query),
//                Updates.combine(
//                        Updates.set(QueryParams.STATUS_NAME.key(), status),
//                        Updates.set(QueryParams.STATUS_DATE.key(), TimeUtils.getTimeMillis()))
//                , new QueryOptions());
//        return endQuery("Update cohort status", startTime, Collections.singletonList(update.first().getModifiedCount()));
//    }

    @Override
    public CatalogDBIterator<Cohort> iterator(Query query, QueryOptions options) throws CatalogDBException {
        Bson bson = parseQuery(query);
        MongoCursor<Document> iterator = cohortCollection.nativeQuery().find(bson, options).iterator();
        return new CatalogMongoDBIterator<>(iterator, cohortConverter);
    }

    @Override
    public CatalogDBIterator nativeIterator(Query query, QueryOptions options) throws CatalogDBException {
        Bson bson = parseQuery(query);
        MongoCursor<Document> iterator = cohortCollection.nativeQuery().find(bson, options).iterator();
        return new CatalogMongoDBIterator<>(iterator);
    }

    @Override
    public QueryResult rank(Query query, String field, int numResults, boolean asc) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return rank(cohortCollection, bsonQuery, field, "name", numResults, asc);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return groupBy(cohortCollection, bsonQuery, field, "name", options);
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return groupBy(cohortCollection, bsonQuery, fields, "name", options);
    }

    @Override
    public void forEach(Query query, Consumer<? super Object> action, QueryOptions options) throws CatalogDBException {
        Objects.requireNonNull(action);
        CatalogDBIterator<Cohort> catalogDBIterator = iterator(query, options);
        while (catalogDBIterator.hasNext()) {
            action.accept(catalogDBIterator.next());
        }
        catalogDBIterator.close();
    }

    private void checkCohortNameExists(long studyId, String cohortName) throws CatalogDBException {
        QueryResult<Long> count = cohortCollection.count(Filters.and(
                Filters.eq(PRIVATE_STUDY_ID, studyId), Filters.eq(QueryParams.NAME.key(), cohortName)));
        if (count.getResult().get(0) > 0) {
            throw CatalogDBException.alreadyExists("Cohort", "name", cohortName);
        }
    }

    private Bson parseQuery(Query query) throws CatalogDBException {
        List<Bson> andBsonList = new ArrayList<>();
        List<Bson> annotationList = new ArrayList<>();
        // We declare variableMap here just in case we have different annotation queries
        Map<String, Variable> variableMap = null;

        for (Map.Entry<String, Object> entry : query.entrySet()) {
            String key = entry.getKey().split("\\.")[0];
            QueryParams queryParam = QueryParams.getParam(entry.getKey()) != null ? QueryParams.getParam(entry.getKey())
                    : QueryParams.getParam(key);
            try {
                switch (queryParam) {
                    case ID:
                        addOrQuery(PRIVATE_ID, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case STUDY_ID:
                        addOrQuery(PRIVATE_STUDY_ID, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case ATTRIBUTES:
                        addAutoOrQuery(entry.getKey(), entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case BATTRIBUTES:
                        String mongoKey = entry.getKey().replace(QueryParams.BATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case NATTRIBUTES:
                        mongoKey = entry.getKey().replace(QueryParams.NATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case VARIABLE_SET_ID:
                        addOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), annotationList);
                        break;
                    case ANNOTATION:
                        if (variableMap == null) {
                            long variableSetId = query.getLong(QueryParams.VARIABLE_SET_ID.key());
                            if (variableSetId > 0) {
                                variableMap = dbAdaptorFactory.getCatalogStudyDBAdaptor().getVariableSet(variableSetId, null).first()
                                        .getVariables().stream().collect(Collectors.toMap(Variable::getName, Function.identity()));
                            }
                        }
                        addAnnotationQueryFilter(entry.getKey(), query, variableMap, annotationList);
                        break;
                    case ANNOTATION_SET_NAME:
                        addOrQuery("name", queryParam.key(), query, queryParam.type(), annotationList);
                        break;
                    default:
                        addAutoOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                }
            } catch (Exception e) {
                throw new CatalogDBException(e);
            }
        }

        if (annotationList.size() > 0) {
            Bson projection = Projections.elemMatch(QueryParams.ANNOTATION_SETS.key(), Filters.and(annotationList));
            andBsonList.add(projection);
        }
        if (andBsonList.size() > 0) {
            return Filters.and(andBsonList);
        } else {
            return new Document();
        }
    }

    public MongoDBCollection getCohortCollection() {
        return cohortCollection;
    }

    public QueryResult<Long> extractSamplesFromCohorts(Query query, List<Long> sampleIds) throws CatalogDBException {
        long startTime = startQuery();
        QueryResult<Cohort> cohortQueryResult = get(query, new QueryOptions(QueryOptions.INCLUDE, QueryParams.ID.key()));
        if (cohortQueryResult.getNumResults() > 0) {
            Bson bsonQuery = parseQuery(query);
            Bson update = new Document("$pull", new Document(QueryParams.SAMPLES.key(), new Document("$in", sampleIds)));
            QueryOptions multi = new QueryOptions(MongoDBCollection.MULTI, true);
            QueryResult<UpdateResult> updateQueryResult = cohortCollection.update(bsonQuery, update, multi);

            // Now we set all the cohorts where a sample has been taken out to status INVALID
            List<Long> ids = cohortQueryResult.getResult().stream().map(Cohort::getId).collect(Collectors.toList());
            setStatus(new Query(QueryParams.ID.key(), ids), Cohort.CohortStatus.INVALID);

            return endQuery("Extract samples from cohorts", startTime,
                    Collections.singletonList(updateQueryResult.first().getModifiedCount()));
        }
        return endQuery("Extract samples from cohorts", startTime, Collections.singletonList(0L));
    }

    @Override
    public QueryResult<CohortAcl> createAcl(long id, CohortAcl acl) throws CatalogDBException {
        long startTime = startQuery();
        CatalogMongoDBUtils.createAcl(id, acl, cohortCollection, "CohortAcl");
        return endQuery("create cohort Acl", startTime, Arrays.asList(acl));
    }

    @Override
    public QueryResult<CohortAcl> getAcl(long id, List<String> members) throws CatalogDBException {
        long startTime = startQuery();

        List<CohortAcl> acl = null;
        QueryResult<Document> aggregate = CatalogMongoDBUtils.getAcl(id, members, cohortCollection, logger);
        Cohort cohort = cohortConverter.convertToDataModelType(aggregate.first());

        if (cohort != null) {
            acl = cohort.getAcls();
        }

        return endQuery("get cohort Acl", startTime, acl);
    }

    @Override
    public void removeAcl(long id, String member) throws CatalogDBException {
        CatalogMongoDBUtils.removeAcl(id, member, cohortCollection);
    }

    @Override
    public QueryResult<CohortAcl> setAclsToMember(long id, String member, List<String> permissions) throws CatalogDBException {
        long startTime = startQuery();
        CatalogMongoDBUtils.setAclsToMember(id, member, permissions, cohortCollection);
        return endQuery("Set Acls to member", startTime, getAcl(id, Arrays.asList(member)));
    }

    @Override
    public QueryResult<CohortAcl> addAclsToMember(long id, String member, List<String> permissions) throws CatalogDBException {
        long startTime = startQuery();
        CatalogMongoDBUtils.addAclsToMember(id, member, permissions, cohortCollection);
        return endQuery("Add Acls to member", startTime, getAcl(id, Arrays.asList(member)));
    }

    @Override
    public void removeAclsFromMember(long id, String member, List<String> permissions) throws CatalogDBException {
        CatalogMongoDBUtils.removeAclsFromMember(id, member, permissions, cohortCollection);
    }
}
