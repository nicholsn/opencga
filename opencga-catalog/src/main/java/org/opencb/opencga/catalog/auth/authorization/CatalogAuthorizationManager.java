package org.opencb.opencga.catalog.auth.authorization;

import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.audit.AuditManager;
import org.opencb.opencga.catalog.db.CatalogDBAdaptorFactory;
import org.opencb.opencga.catalog.db.api.*;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.FileManager;
import org.opencb.opencga.catalog.models.*;
import org.opencb.opencga.catalog.models.acls.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opencb.opencga.catalog.utils.CatalogMemberValidator.checkMembers;

/**
 * Created by pfurio on 12/05/16.
 */
public class CatalogAuthorizationManager implements AuthorizationManager {

    private static final QueryOptions FILE_INCLUDE_QUERY_OPTIONS = new QueryOptions(QueryOptions.INCLUDE,
            Arrays.asList(FILTER_ROUTE_FILES + CatalogFileDBAdaptor.QueryParams.ID.key(),
                    FILTER_ROUTE_FILES + CatalogFileDBAdaptor.QueryParams.PATH.key(),
                    FILTER_ROUTE_FILES + CatalogFileDBAdaptor.QueryParams.ACLS.key()
            ));

    private final CatalogDBAdaptorFactory dbAdaptorFactory;
    private final CatalogUserDBAdaptor userDBAdaptor;
    private final CatalogProjectDBAdaptor projectDBAdaptor;
    private final CatalogStudyDBAdaptor studyDBAdaptor;
    private final CatalogFileDBAdaptor fileDBAdaptor;
    private final CatalogJobDBAdaptor jobDBAdaptor;
    private final CatalogSampleDBAdaptor sampleDBAdaptor;
    private final CatalogIndividualDBAdaptor individualDBAdaptor;
    private final CatalogCohortDBAdaptor cohortDBAdaptor;
    private final CatalogDatasetDBAdaptor datasetDBAdaptor;
    private final CatalogPanelDBAdaptor panelDBAdaptor;
    private final CatalogMetaDBAdaptor metaDBAdaptor;
    private final AuditManager auditManager;

    public CatalogAuthorizationManager(CatalogDBAdaptorFactory catalogDBAdaptorFactory, AuditManager auditManager) {
        this.auditManager = auditManager;
        this.dbAdaptorFactory = catalogDBAdaptorFactory;
        userDBAdaptor = catalogDBAdaptorFactory.getCatalogUserDBAdaptor();
        projectDBAdaptor = catalogDBAdaptorFactory.getCatalogProjectDbAdaptor();
        studyDBAdaptor = catalogDBAdaptorFactory.getCatalogStudyDBAdaptor();
        fileDBAdaptor = catalogDBAdaptorFactory.getCatalogFileDBAdaptor();
        jobDBAdaptor = catalogDBAdaptorFactory.getCatalogJobDBAdaptor();
        sampleDBAdaptor = catalogDBAdaptorFactory.getCatalogSampleDBAdaptor();
        individualDBAdaptor = catalogDBAdaptorFactory.getCatalogIndividualDBAdaptor();
        cohortDBAdaptor = catalogDBAdaptorFactory.getCatalogCohortDBAdaptor();
        datasetDBAdaptor = catalogDBAdaptorFactory.getCatalogDatasetDBAdaptor();
        panelDBAdaptor = catalogDBAdaptorFactory.getCatalogPanelDBAdaptor();
        metaDBAdaptor = catalogDBAdaptorFactory.getCatalogMetaDBAdaptor();
    }

    @Override
    public void checkProjectPermission(long projectId, String userId, StudyAcl.StudyPermissions permission) throws CatalogException {
        if (projectDBAdaptor.getProjectOwnerId(projectId).equals(userId)) {
            return;
        }

        if (permission.equals(StudyAcl.StudyPermissions.VIEW_STUDY)) {
            final Query query = new Query(CatalogStudyDBAdaptor.QueryParams.PROJECT_ID.key(), projectId);
            final QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE,
                    FILTER_ROUTE_STUDIES + CatalogStudyDBAdaptor.QueryParams.ID.key());
            for (Study study : studyDBAdaptor.get(query, queryOptions).getResult()) {
                try {
                    checkStudyPermission(study.getId(), userId, StudyAcl.StudyPermissions.VIEW_STUDY);
                    return; //Return if can read some study
                } catch (CatalogException e) {
                    e.printStackTrace();
                }
            }
        }

        throw CatalogAuthorizationException.deny(userId, permission.toString(), "Project", projectId, null);
    }

    @Override
    public void checkStudyPermission(long studyId, String userId, StudyAcl.StudyPermissions permission) throws CatalogException {
        checkStudyPermission(studyId, userId, permission, permission.toString());
    }

    @Override
    public void checkStudyPermission(long studyId, String userId, StudyAcl.StudyPermissions permission, String message)
            throws CatalogException {
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        StudyAcl studyAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                studyAcl = studyAclQueryResult.first();
            }
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;
            studyAcl = getStudyAclBelonging(studyId, userId, groupId);
        }
        if (studyAcl == null) {
            throw CatalogAuthorizationException.deny(userId, message, "Study", studyId, null);
        }

        if (studyAcl.getPermissions().contains(permission)) {
            return;
        }

        throw CatalogAuthorizationException.deny(userId, message, "Study", studyId, null);
    }

    @Override
    public void checkFilePermission(long fileId, String userId, FileAcl.FilePermissions permission) throws CatalogException {
        checkFilePermission(fileId, userId, permission, null);
    }

    private void checkFilePermission(long fileId, String userId, FileAcl.FilePermissions permission,
                                     StudyAuthenticationContext studyAuthenticationContext) throws CatalogException {
        long studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        FileAcl fileAcl = null;

        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                fileAcl = transformStudyAclToFileAcl(studyAclQueryResult.first());
            }
        } else {
            if (studyAuthenticationContext == null) {
                studyAuthenticationContext = new StudyAuthenticationContext(studyId);
            }
            fileAcl = resolveFilePermissions(fileId, userId, studyId, studyAuthenticationContext);
        }


        if (!fileAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "File", fileId, null);
        }
    }

    /**
     * Obtains the file permissions of the userId.
     *
     * @param fileId file id.
     * @param userId user id.
     * @param studyId study id.
     * @param studyAuthenticationContext Mini cache containing the map of path -> user -> permissions.
     * @return fileAcl.
     */
    private FileAcl resolveFilePermissions(long fileId, String userId, long studyId,
                                           StudyAuthenticationContext studyAuthenticationContext) throws CatalogException {
        return resolveFilePermissions(fileDBAdaptor.getFile(fileId, FILE_INCLUDE_QUERY_OPTIONS).first(), userId, studyId,
                studyAuthenticationContext);
    }

    /**
     * Obtains the file permissions of the userId.
     *
     * @param file
     * @param userId
     * @param studyId
     * @param studyAuthenticationContext
     * @return
     * @throws CatalogException
     */
    private FileAcl resolveFilePermissions(File file, String userId, long studyId,
                                           StudyAuthenticationContext studyAuthenticationContext) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        // We obtain all the paths from our current position to the root folder
        List<String> paths = FileManager.getParentPaths(file.getPath());
        // We obtain a map with the permissions
        Map<String, Map<String, FileAcl>> pathAclMap = getFileAcls(studyAuthenticationContext, userId, studyId, groupId, paths);

        FileAcl fileAcl = null;
        for (int i = paths.size() - 1; i >= 0; i--) {
            String path = paths.get(i);
            if (pathAclMap.containsKey(path)) {
                Map<String, FileAcl> aclMap = pathAclMap.get(path);
                if (aclMap.get(userId) != null) {
                    fileAcl = aclMap.get(userId);
                } else if (aclMap.get(groupId) != null) {
                    fileAcl = aclMap.get(groupId);
                } else if (aclMap.get(OTHER_USERS_ID) != null) {
                    fileAcl = aclMap.get(OTHER_USERS_ID);
                }
                if (fileAcl != null) {
                    break;
                }
            }
        }

        if (fileAcl == null) {
            StudyAcl studyAcl = getStudyAclBelonging(studyId, userId, groupId);
            fileAcl = transformStudyAclToFileAcl(studyAcl);
        }

        return fileAcl;
    }

    @Override
    public void checkSamplePermission(long sampleId, String userId, SampleAcl.SamplePermissions permission) throws CatalogException {
        long studyId = sampleDBAdaptor.getStudyIdBySampleId(sampleId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        SampleAcl sampleAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                sampleAcl = transformStudyAclToSampleAcl(studyAclQueryResult.first());
            }
        } else {
            sampleAcl = resolveSamplePermissions(studyId, sampleId, userId);
        }

        if (!sampleAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "Sample", sampleId, null);
        }
    }

    /**
     * Resolves the permissions between a sample and a user.
     * Returns the most specific matching ACL following the next sequence:
     * user > group > others > study
     *
     * @param studyId Study id.
     * @param sample Sample.
     * @param userId User id.
     * @return
     * @throws CatalogException
     */
    private SampleAcl resolveSamplePermissions(long studyId, Sample sample, String userId) throws CatalogException {
        if (sample.getAcls() == null) {
            return resolveSamplePermissions(studyId, sample.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, SampleAcl> userAclMap = new HashMap<>();
            for (SampleAcl sampleAcl : sample.getAcls()) {
                userAclMap.put(sampleAcl.getMember(), sampleAcl);
            }
            return resolveSamplePermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private SampleAcl resolveSamplePermissions(long studyId, long sampleId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<SampleAcl> sampleAclList = sampleDBAdaptor.getSampleAcl(sampleId, userIds).getResult();

        Map<String, SampleAcl> userAclMap = new HashMap<>();
        for (SampleAcl sampleAcl : sampleAclList) {
            userAclMap.put(sampleAcl.getMember(), sampleAcl);
        }

        return resolveSamplePermissions(studyId, userId, groupId, userAclMap);
    }

    private SampleAcl resolveSamplePermissions(long studyId, String userId, String groupId, Map<String, SampleAcl> userAclMap)
            throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToSampleAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void checkIndividualPermission(long individualId, String userId, IndividualAcl.IndividualPermissions permission)
            throws CatalogException {
        long studyId = individualDBAdaptor.getStudyIdByIndividualId(individualId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        IndividualAcl individualAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                individualAcl = transformStudyAclToIndividualAcl(studyAclQueryResult.first());
            }
        } else {
            individualAcl = resolveIndividualPermissions(studyId, individualId, userId);
        }

        if (!individualAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "Individual", individualId, null);
        }
    }

    private IndividualAcl resolveIndividualPermissions(long studyId, Individual individual, String userId) throws CatalogException {
        if (individual.getAcls() == null) {
            return resolveIndividualPermissions(studyId, individual.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, IndividualAcl> userAclMap = new HashMap<>();
            for (IndividualAcl individualAcl : individual.getAcls()) {
                userAclMap.put(individualAcl.getMember(), individualAcl);
            }
            return resolveIndividualPermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private IndividualAcl resolveIndividualPermissions(long studyId, long individualId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<IndividualAcl> individualAcls = individualDBAdaptor.getIndividualAcl(individualId, userIds).getResult();

        Map<String, IndividualAcl> userAclMap = new HashMap<>();
        for (IndividualAcl individualAcl : individualAcls) {
            userAclMap.put(individualAcl.getMember(), individualAcl);
        }

        return resolveIndividualPermissions(studyId, userId, groupId, userAclMap);
    }

    private IndividualAcl resolveIndividualPermissions(long studyId, String userId, String groupId, Map<String, IndividualAcl> userAclMap)
            throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToIndividualAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void checkJobPermission(long jobId, String userId, JobAcl.JobPermissions permission) throws CatalogException {
        long studyId = jobDBAdaptor.getStudyIdByJobId(jobId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        JobAcl jobAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                jobAcl = transformStudyAclToJobAcl(studyAclQueryResult.first());
            }
        } else {
            jobAcl = resolveJobPermissions(studyId, jobId, userId);
        }


        if (!jobAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "Job", jobId, null);
        }
    }

    private JobAcl resolveJobPermissions(long studyId, Job job, String userId) throws CatalogException {
        if (job.getAcls() == null) {
            return resolveJobPermissions(studyId, job.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, JobAcl> userAclMap = new HashMap<>();
            for (JobAcl jobAcl : job.getAcls()) {
                userAclMap.put(jobAcl.getMember(), jobAcl);
            }
            return resolveJobPermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private JobAcl resolveJobPermissions(long studyId, long jobId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<JobAcl> jobAcls = jobDBAdaptor.getJobAcl(jobId, userIds).getResult();

        Map<String, JobAcl> userAclMap = new HashMap<>();
        for (JobAcl jobAcl : jobAcls) {
            userAclMap.put(jobAcl.getMember(), jobAcl);
        }

        return resolveJobPermissions(studyId, userId, groupId, userAclMap);
    }

    private JobAcl resolveJobPermissions(long studyId, String userId, String groupId, Map<String, JobAcl> userAclMap)
            throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToJobAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void checkCohortPermission(long cohortId, String userId, CohortAcl.CohortPermissions permission) throws CatalogException {
        long studyId = cohortDBAdaptor.getStudyIdByCohortId(cohortId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        CohortAcl cohortAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                cohortAcl = transformStudyAclToCohortAcl(studyAclQueryResult.first());
            }
        } else {
            cohortAcl = resolveCohortPermissions(studyId, cohortId, userId);
        }

        if (!cohortAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "Cohort", cohortId, null);
        }
    }

    private CohortAcl resolveCohortPermissions(long studyId, Cohort cohort, String userId) throws CatalogException {
        if (cohort.getAcls() == null) {
            return resolveCohortPermissions(studyId, cohort.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, CohortAcl> userAclMap = new HashMap<>();
            for (CohortAcl cohortAcl : cohort.getAcls()) {
                userAclMap.put(cohortAcl.getMember(), cohortAcl);
            }
            return resolveCohortPermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private CohortAcl resolveCohortPermissions(long studyId, long cohortId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<CohortAcl> cohortAcls = cohortDBAdaptor.getCohortAcl(cohortId, userIds).getResult();

        Map<String, CohortAcl> userAclMap = new HashMap<>();
        for (CohortAcl cohortAcl : cohortAcls) {
            userAclMap.put(cohortAcl.getMember(), cohortAcl);
        }

        return resolveCohortPermissions(studyId, userId, groupId, userAclMap);
    }

    private CohortAcl resolveCohortPermissions(long studyId, String userId, String groupId, Map<String, CohortAcl> userAclMap)
            throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToCohortAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void checkDatasetPermission(long datasetId, String userId, DatasetAcl.DatasetPermissions permission) throws CatalogException {
        long studyId = datasetDBAdaptor.getStudyIdByDatasetId(datasetId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        DatasetAcl datasetAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                datasetAcl = transformStudyAclToDatasetAcl(studyAclQueryResult.first());
            }
        } else {
            datasetAcl = resolveDatasetPermissions(studyId, datasetId, userId);
        }

        if (!datasetAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "Dataset", datasetId, null);
        }
    }

    private DatasetAcl resolveDatasetPermissions(long studyId, Dataset dataset, String userId) throws CatalogException {
        if (dataset.getAcls() == null) {
            return resolveDatasetPermissions(studyId, dataset.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, DatasetAcl> userAclMap = new HashMap<>();
            for (DatasetAcl datasetAcl : dataset.getAcls()) {
                userAclMap.put(datasetAcl.getMember(), datasetAcl);
            }
            return resolveDatasetPermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private DatasetAcl resolveDatasetPermissions(long studyId, long datasetId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<DatasetAcl> datasetAcls = datasetDBAdaptor.getDatasetAcl(datasetId, userIds).getResult();

        Map<String, DatasetAcl> userAclMap = new HashMap<>();
        for (DatasetAcl datasetAcl : datasetAcls) {
            userAclMap.put(datasetAcl.getMember(), datasetAcl);
        }

        return resolveDatasetPermissions(studyId, userId, groupId, userAclMap);
    }

    private DatasetAcl resolveDatasetPermissions(long studyId, String userId, String groupId, Map<String, DatasetAcl> userAclMap)
            throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToDatasetAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void checkDiseasePanelPermission(long panelId, String userId, DiseasePanelAcl.DiseasePanelPermissions permission)
            throws CatalogException {
        long studyId = panelDBAdaptor.getStudyIdByPanelId(panelId);
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        DiseasePanelAcl panelAcl = null;
        if (userId.equals("admin")) {
            QueryResult<StudyAcl> studyAclQueryResult = metaDBAdaptor.getDaemonAcl(Arrays.asList("admin"));
            if (studyAclQueryResult.getNumResults() == 1) {
                panelAcl = transformStudyAclToDiseasePanelAcl(studyAclQueryResult.first());
            }
        } else {
            panelAcl = resolveDiseasePanelPermissions(studyId, panelId, userId);
        }

        if (!panelAcl.getPermissions().contains(permission)) {
            throw CatalogAuthorizationException.deny(userId, permission.toString(), "DiseasePanel", panelId, null);
        }
    }

    private DiseasePanelAcl resolveDiseasePanelPermissions(long studyId, DiseasePanel panel, String userId) throws CatalogException {
        if (panel.getAcls() == null) {
            return resolveDiseasePanelPermissions(studyId, panel.getId(), userId);
        } else {
            QueryResult<Group> group = getGroupBelonging(studyId, userId);
            String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

            Map<String, DiseasePanelAcl> userAclMap = new HashMap<>();
            for (DiseasePanelAcl panelAcl : panel.getAcls()) {
                userAclMap.put(panelAcl.getMember(), panelAcl);
            }
            return resolveDiseasePanelPermissions(studyId, userId, groupId, userAclMap);
        }
    }

    private DiseasePanelAcl resolveDiseasePanelPermissions(long studyId, long panelId, String userId) throws CatalogException {
        QueryResult<Group> group = getGroupBelonging(studyId, userId);
        String groupId = group.getNumResults() == 1 ? group.first().getName() : null;

        List<String> userIds = (groupId == null)
                ? Arrays.asList(userId, OTHER_USERS_ID)
                : Arrays.asList(userId, groupId, OTHER_USERS_ID);
        List<DiseasePanelAcl> panelAcls = panelDBAdaptor.getPanelAcl(panelId, userIds).getResult();

        Map<String, DiseasePanelAcl> userAclMap = new HashMap<>();
        for (DiseasePanelAcl panelAcl : panelAcls) {
            userAclMap.put(panelAcl.getMember(), panelAcl);
        }

        return resolveDiseasePanelPermissions(studyId, userId, groupId, userAclMap);
    }

    private DiseasePanelAcl resolveDiseasePanelPermissions(long studyId, String userId, String groupId,
                                                           Map<String, DiseasePanelAcl> userAclMap) throws CatalogException {
        if (userAclMap.containsKey(userId)) {
            return userAclMap.get(userId);
        } else if (groupId != null && userAclMap.containsKey(groupId)) {
            return userAclMap.get(groupId);
        } else if (userAclMap.containsKey(OTHER_USERS_ID)) {
            return userAclMap.get(OTHER_USERS_ID);
        } else {
            return transformStudyAclToDiseasePanelAcl(getStudyAclBelonging(studyId, userId, groupId));
        }
    }

    @Override
    public void filterProjects(String userId, List<Project> projects) throws CatalogException {
        if (projects == null || projects.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        Iterator<Project> projectIt = projects.iterator();
        while (projectIt.hasNext()) {
            Project p = projectIt.next();
            try {
                checkProjectPermission(p.getId(), userId, StudyAcl.StudyPermissions.VIEW_STUDY);
            } catch (CatalogAuthorizationException e) {
                projectIt.remove();
                continue;
            }
            filterStudies(userId, p.getStudies());
        }
    }

    @Override
    public void filterStudies(String userId, List<Study> studies) throws CatalogException {
        if (studies == null || studies.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        Iterator<Study> studyIt = studies.iterator();
        while (studyIt.hasNext()) {
            Study study = studyIt.next();
            try {
                checkStudyPermission(study.getId(), userId, StudyAcl.StudyPermissions.VIEW_STUDY);
            } catch (CatalogAuthorizationException e) {
                studyIt.remove();
                continue;
            }
            StudyAuthenticationContext studyAuthenticationContext = new StudyAuthenticationContext(study.getId());
            filterFiles(userId, study.getId(), study.getFiles(), studyAuthenticationContext);
            filterSamples(userId, study.getId(), study.getSamples());
            filterJobs(userId, study.getId(), study.getJobs());
            filterCohorts(userId, study.getId(), study.getCohorts());
            filterIndividuals(userId, study.getId(), study.getIndividuals());
            filterDatasets(userId, study.getId(), study.getDatasets());
        }
    }

    @Override
    public void filterFiles(String userId, long studyId, List<File> files) throws CatalogException {
        filterFiles(userId, studyId, files, new StudyAuthenticationContext(studyId));
    }

    private void filterFiles(String userId, long studyId, List<File> files, StudyAuthenticationContext studyAuthenticationContext)
            throws CatalogException {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

//        getFileAcls(studyAuthenticationContext, userId, files);

        Iterator<File> fileIt = files.iterator();
        while (fileIt.hasNext()) {
            File file = fileIt.next();
            FileAcl fileAcl = resolveFilePermissions(file, userId, studyId, studyAuthenticationContext);
            if (!fileAcl.getPermissions().contains(FileAcl.FilePermissions.VIEW)) {
                fileIt.remove();
            }
        }
    }

    @Override
    public void filterSamples(String userId, long studyId, List<Sample> samples) throws CatalogException {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        Iterator<Sample> sampleIterator = samples.iterator();
        while (sampleIterator.hasNext()) {
            Sample sample = sampleIterator.next();
            SampleAcl sampleACL = resolveSamplePermissions(studyId, sample, userId);
            if (!sampleACL.getPermissions().contains(SampleAcl.SamplePermissions.VIEW)) {
                sampleIterator.remove();
                continue;
            }

            if (!sampleACL.getPermissions().contains(SampleAcl.SamplePermissions.VIEW_ANNOTATIONS)) {
                sample.setAnnotationSets(new ArrayList<>());
            }
        }
    }

    @Override
    public void filterIndividuals(String userId, long studyId, List<Individual> individuals) throws CatalogException {
        if (individuals == null || individuals.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        Iterator<Individual> individualIterator = individuals.iterator();
        while (individualIterator.hasNext()) {
            Individual individual = individualIterator.next();
            IndividualAcl individualAcl = resolveIndividualPermissions(studyId, individual, userId);
            if (!individualAcl.getPermissions().contains(IndividualAcl.IndividualPermissions.VIEW)) {
                individualIterator.remove();
                continue;
            }

            if (!individualAcl.getPermissions().contains(IndividualAcl.IndividualPermissions.VIEW_ANNOTATIONS)) {
                individual.setAnnotationSets(new ArrayList<>());
            }
        }
    }

    @Override
    public void filterCohorts(String userId, long studyId, List<Cohort> cohorts) throws CatalogException {
        if (cohorts == null || cohorts.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        Iterator<Cohort> cohortIterator = cohorts.iterator();
        while (cohortIterator.hasNext()) {
            Cohort cohort = cohortIterator.next();
            CohortAcl cohortAcl = resolveCohortPermissions(studyId, cohort, userId);
            if (!cohortAcl.getPermissions().contains(CohortAcl.CohortPermissions.VIEW)) {
                cohortIterator.remove();
                continue;
            }

            if (!cohortAcl.getPermissions().contains(CohortAcl.CohortPermissions.VIEW_ANNOTATIONS)) {
                cohort.setAnnotationSets(new ArrayList<>());
            }
        }
    }

    @Override
    public void filterJobs(String userId, long studyId, List<Job> jobs) throws CatalogException {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        Iterator<Job> jobIterator = jobs.iterator();
        while (jobIterator.hasNext()) {
            Job job = jobIterator.next();
            JobAcl jobAcl = resolveJobPermissions(studyId, job, userId);
            if (!jobAcl.getPermissions().contains(JobAcl.JobPermissions.VIEW)) {
                jobIterator.remove();
            }
        }
    }

    @Override
    public void filterDatasets(String userId, long studyId, List<Dataset> datasets) throws CatalogException {
        if (datasets == null || datasets.isEmpty()) {
            return;
        }
        if (userId.equals("admin")) {
            return;
        }
        if (isStudyOwner(studyId, userId)) {
            return;
        }

        Iterator<Dataset> datasetIterator = datasets.iterator();
        while (datasetIterator.hasNext()) {
            Dataset dataset = datasetIterator.next();
            DatasetAcl datasetAcl = resolveDatasetPermissions(studyId, dataset, userId);
            if (!datasetAcl.getPermissions().contains(DatasetAcl.DatasetPermissions.VIEW)) {
                datasetIterator.remove();
            }
        }
    }

    @Override
    public QueryResult<StudyAcl> createStudyAcls(String userId, long studyId, List<String> members, List<String> permissions,
                                                 String template) throws CatalogException {
        studyDBAdaptor.checkStudyId(studyId);
        checkMembers(dbAdaptorFactory, studyId, members);

        checkStudyPermission(studyId, userId, StudyAcl.StudyPermissions.SHARE_STUDY);

        // We obtain the permissions present in the demanded template (if present)
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = AuthorizationManager.getLockedAcls();
        if (template != null && !template.isEmpty()) {
            if (template.equals("admin")) {
                studyPermissions = AuthorizationManager.getAdminAcls();
            } else if (template.equals("analyst")) {
                studyPermissions = AuthorizationManager.getAnalystAcls();
            }
        }
        // Add the permissions present in permissions
        studyPermissions.addAll(permissions.stream().map(StudyAcl.StudyPermissions::valueOf).collect(Collectors.toList()));

        // If the user already has permissions set, we cannot create a new set of permissions
        List<StudyAcl> studyAclList = new ArrayList<>(members.size());
        for (String member : members) {
            if (memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("The member " + member + " already has some permissions set in study. Please, remove those"
                        + " permissions or add, remove or set new permissions.");
            }
        }

        int timeSpent = 0;
        for (String member : members) {
            StudyAcl studyAcl = new StudyAcl(member, studyPermissions);
            QueryResult<StudyAcl> studyAclQueryResult = studyDBAdaptor.createAcl(studyId, studyAcl);
            timeSpent += studyAclQueryResult.getDbTime();
            studyAclList.add(studyAclQueryResult.first());
        }

        return new QueryResult<>("create study Acls", timeSpent, studyAclList.size(), studyAclList.size(), "", "", studyAclList);
    }

    @Override
    public QueryResult<StudyAcl> getAllStudyAcls(String userId, long studyId) throws CatalogException {
        studyDBAdaptor.checkStudyId(studyId);
        checkStudyPermission(studyId, userId, StudyAcl.StudyPermissions.SHARE_STUDY);

        Query query = new Query(CatalogStudyDBAdaptor.QueryParams.ID.key(), studyId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogStudyDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Study> studyQueryResult = studyDBAdaptor.get(query, queryOptions);

        List<StudyAcl> studyAclList;
        if (studyQueryResult == null || studyQueryResult.getNumResults() == 0) {
            studyAclList = Collections.emptyList();
        } else {
            studyAclList = studyQueryResult.first().getAcls();
        }

        return new QueryResult<>("Get all study Acls", studyQueryResult.getDbTime(), studyAclList.size(), studyAclList.size(),
                studyQueryResult.getWarningMsg(), studyQueryResult.getErrorMsg(), studyAclList);
    }

    @Override
    public QueryResult<StudyAcl> getStudyAcl(String userId, long studyId, String member) throws CatalogException {
        studyDBAdaptor.checkStudyId(studyId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkStudyPermission(studyId, userId, StudyAcl.StudyPermissions.SHARE_STUDY);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return studyDBAdaptor.getAcl(studyId, members);
    }

    @Override
    public QueryResult<StudyAcl> removeStudyAcl(String userId, long studyId, String member) throws CatalogException {
        studyDBAdaptor.checkStudyId(studyId);

        checkStudyPermission(studyId, userId, StudyAcl.StudyPermissions.SHARE_STUDY);

        // Cannot remove permissions for the owner of the study
        String studyOwnerId = studyDBAdaptor.getStudyOwnerId(studyId);
        if (member.equals(studyOwnerId)) {
            throw new CatalogException("Error: It is not allowed removing the permissions to the owner of the study.");
        }

        // Obtain the ACLs the member had
        QueryResult<StudyAcl> studyAcl = studyDBAdaptor.getAcl(studyId, Arrays.asList(member));
        if (studyAcl == null || studyAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        studyDBAdaptor.removeAcl(studyId, member);

        studyAcl.setId("Remove study ACLs");
        return studyAcl;
    }

    @Override
    public QueryResult<StudyAcl> updateStudyAcl(String userId, long studyId, String member, @Nullable String addPermissions,
                                                @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        studyDBAdaptor.checkStudyId(studyId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));
        checkStudyPermission(studyId, userId, StudyAcl.StudyPermissions.SHARE_STUDY);

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogStudyDBAdaptor.QueryParams.ID.key(), studyId)
                .append(CatalogStudyDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = studyDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, StudyAcl.StudyPermissions::valueOf);

            studyDBAdaptor.setAclsToMember(studyId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, StudyAcl.StudyPermissions::valueOf);

                studyDBAdaptor.addAclsToMember(studyId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, StudyAcl.StudyPermissions::valueOf);

                studyDBAdaptor.removeAclsFromMember(studyId, member, permissions);
            }
        }

        return studyDBAdaptor.getAcl(studyId, Arrays.asList(member));
    }

    @Override
    public QueryResult<SampleAcl> createSampleAcls(String userId, long sampleId, List<String> members, List<String> permissions)
            throws CatalogException {
        sampleDBAdaptor.checkSampleId(sampleId);
        // Check if the userId has proper permissions for all the samples.
        checkSamplePermission(sampleId, userId, SampleAcl.SamplePermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = sampleDBAdaptor.getStudyIdBySampleId(sampleId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the sample
        if (anyMemberHasPermissions(studyId, sampleId, members, sampleDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular sample. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<SampleAcl> sampleAclList = new ArrayList<>(members.size());
        for (String member : members) {
            SampleAcl sampleAcl = new SampleAcl(member, permissions);
            QueryResult<SampleAcl> sampleAclQueryResult = sampleDBAdaptor.createAcl(sampleId, sampleAcl);
            timeSpent += sampleAclQueryResult.getDbTime();
            sampleAclList.add(sampleAclQueryResult.first());
        }

        return new QueryResult<>("create sample acl", timeSpent, sampleAclList.size(), sampleAclList.size(), "", "", sampleAclList);
    }

    @Override
    public QueryResult<SampleAcl> getAllSampleAcls(String userId, long sampleId) throws CatalogException {
        sampleDBAdaptor.checkSampleId(sampleId);
        // Check if the userId has proper permissions for all the samples.
        checkSamplePermission(sampleId, userId, SampleAcl.SamplePermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogSampleDBAdaptor.QueryParams.ID.key(), sampleId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogSampleDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Sample> queryResult = sampleDBAdaptor.get(query, queryOptions);

        List<SampleAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get sample acls", queryResult.getDbTime(), aclList.size(), aclList.size(), queryResult.getWarningMsg(),
                queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<SampleAcl> getSampleAcl(String userId, long sampleId, String member) throws CatalogException {
        sampleDBAdaptor.checkSampleId(sampleId);

        long studyId = sampleDBAdaptor.getStudyIdBySampleId(sampleId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkSamplePermission(sampleId, userId, SampleAcl.SamplePermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return sampleDBAdaptor.getAcl(studyId, members);
    }

    @Override
    public QueryResult<SampleAcl> removeSampleAcl(String userId, long sampleId, String member) throws CatalogException {
        sampleDBAdaptor.checkSampleId(sampleId);
        checkSamplePermission(sampleId, userId, SampleAcl.SamplePermissions.SHARE);

        long studyId = sampleDBAdaptor.getStudyIdBySampleId(sampleId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<SampleAcl> sampleAcl = sampleDBAdaptor.getAcl(sampleId, Arrays.asList(member));
        if (sampleAcl == null || sampleAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        sampleDBAdaptor.removeAcl(sampleId, member);

        sampleAcl.setId("Remove sample ACLs");
        return sampleAcl;
    }

    @Override
    public QueryResult<SampleAcl> updateSampleAcl(String userId, long sampleId, String member, @Nullable String addPermissions,
                                                  @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        sampleDBAdaptor.checkSampleId(sampleId);
        checkSamplePermission(sampleId, userId, SampleAcl.SamplePermissions.SHARE);

        long studyId = sampleDBAdaptor.getStudyIdBySampleId(sampleId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogSampleDBAdaptor.QueryParams.ID.key(), sampleId)
                .append(CatalogSampleDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = sampleDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, SampleAcl.SamplePermissions::valueOf);

            sampleDBAdaptor.setAclsToMember(sampleId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, SampleAcl.SamplePermissions::valueOf);

                sampleDBAdaptor.addAclsToMember(sampleId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, SampleAcl.SamplePermissions::valueOf);

                sampleDBAdaptor.removeAclsFromMember(sampleId, member, permissions);
            }
        }

        return sampleDBAdaptor.getAcl(sampleId, Arrays.asList(member));
    }

    @Override
    public QueryResult<FileAcl> createFileAcls(String userId, long fileId, List<String> members, List<String> permissions)
            throws CatalogException {
        fileDBAdaptor.checkFileId(fileId);

        // Check if the userId has proper permissions for all the files.
        checkFilePermission(fileId, userId, FileAcl.FilePermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the file
        if (anyMemberHasPermissions(studyId, fileId, members, fileDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular file. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<FileAcl> fileAclList = new ArrayList<>(members.size());
        for (String member : members) {
            FileAcl fileAcl = new FileAcl(member, permissions);
            QueryResult<FileAcl> fileAclQueryResult = fileDBAdaptor.createAcl(fileId, fileAcl);
            timeSpent += fileAclQueryResult.getDbTime();
            fileAclList.add(fileAclQueryResult.first());
        }

        return new QueryResult<>("create file acl", timeSpent, fileAclList.size(), fileAclList.size(), "", "", fileAclList);
    }

    @Override
    public QueryResult<FileAcl> getAllFileAcls(String userId, long fileId) throws CatalogException {
        fileDBAdaptor.checkFileId(fileId);
        // Check if the userId has proper permissions for all the samples.
        checkFilePermission(fileId, userId, FileAcl.FilePermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogFileDBAdaptor.QueryParams.ID.key(), fileId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogFileDBAdaptor.QueryParams.ACLS.key());
        QueryResult<File> queryResult = fileDBAdaptor.get(query, queryOptions);

        List<FileAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get file acls", queryResult.getDbTime(), aclList.size(), aclList.size(), queryResult.getWarningMsg(),
                queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<FileAcl> getFileAcl(String userId, long fileId, String member) throws CatalogException {
        fileDBAdaptor.checkFileId(fileId);

        long studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkFilePermission(fileId, userId, FileAcl.FilePermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return fileDBAdaptor.getAcl(fileId, members);
    }

    @Override
    public QueryResult<FileAcl> removeFileAcl(String userId, long fileId, String member) throws CatalogException {
        fileDBAdaptor.checkFileId(fileId);
        checkFilePermission(fileId, userId, FileAcl.FilePermissions.SHARE);

        long studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<FileAcl> fileAcl = fileDBAdaptor.getAcl(fileId, Arrays.asList(member));
        if (fileAcl == null || fileAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        fileDBAdaptor.removeAcl(fileId, member);

        fileAcl.setId("Remove file ACLs");
        return fileAcl;
    }

    @Override
    public QueryResult<FileAcl> updateFileAcl(String userId, long fileId, String member, @Nullable String addPermissions,
                                              @Nullable String removePermissions, @Nullable String setPermissions) throws CatalogException {
        fileDBAdaptor.checkFileId(fileId);
        checkFilePermission(fileId, userId, FileAcl.FilePermissions.SHARE);

        long studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogFileDBAdaptor.QueryParams.ID.key(), fileId)
                .append(CatalogFileDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = fileDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, FileAcl.FilePermissions::valueOf);

            fileDBAdaptor.setAclsToMember(fileId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, FileAcl.FilePermissions::valueOf);

                fileDBAdaptor.addAclsToMember(fileId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, FileAcl.FilePermissions::valueOf);

                fileDBAdaptor.removeAclsFromMember(fileId, member, permissions);
            }
        }

        return fileDBAdaptor.getAcl(fileId, Arrays.asList(member));
    }

    @Override
    public QueryResult<IndividualAcl> createIndividualAcls(String userId, long individualId, List<String> members, List<String> permissions)
            throws CatalogException {
        individualDBAdaptor.checkIndividualId(individualId);
        // Check if the userId has proper permissions for all the samples.
        checkIndividualPermission(individualId, userId, IndividualAcl.IndividualPermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = individualDBAdaptor.getStudyIdByIndividualId(individualId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the individual
        if (anyMemberHasPermissions(studyId, individualId, members, individualDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular individual. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<IndividualAcl> individualAclList = new ArrayList<>(members.size());
        for (String member : members) {
            IndividualAcl individualAcl = new IndividualAcl(member, permissions);
            QueryResult<IndividualAcl> individualAclQueryResult = individualDBAdaptor.createAcl(individualId, individualAcl);
            timeSpent += individualAclQueryResult.getDbTime();
            individualAclList.add(individualAclQueryResult.first());
        }

        return new QueryResult<>("create individual acl", timeSpent, individualAclList.size(), individualAclList.size(), "", "",
                individualAclList);
    }

    @Override
    public QueryResult<IndividualAcl> getAllIndividualAcls(String userId, long individualId) throws CatalogException {
        individualDBAdaptor.checkIndividualId(individualId);
        // Check if the userId has proper permissions for all the individuals.
        checkIndividualPermission(individualId, userId, IndividualAcl.IndividualPermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogIndividualDBAdaptor.QueryParams.ID.key(), individualId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogIndividualDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Individual> queryResult = individualDBAdaptor.get(query, queryOptions);

        List<IndividualAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get individual acls", queryResult.getDbTime(), aclList.size(), aclList.size(),
                queryResult.getWarningMsg(), queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<IndividualAcl> getIndividualAcl(String userId, long individualId, String member) throws CatalogException {
        individualDBAdaptor.checkIndividualId(individualId);

        long studyId = individualDBAdaptor.getStudyIdByIndividualId(individualId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkIndividualPermission(individualId, userId, IndividualAcl.IndividualPermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return individualDBAdaptor.getAcl(individualId, members);
    }

    @Override
    public QueryResult<IndividualAcl> removeIndividualAcl(String userId, long individualId, String member) throws CatalogException {
        individualDBAdaptor.checkIndividualId(individualId);
        checkIndividualPermission(individualId, userId, IndividualAcl.IndividualPermissions.SHARE);

        long studyId = individualDBAdaptor.getStudyIdByIndividualId(individualId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<IndividualAcl> individualDBAdaptorAcl = individualDBAdaptor.getAcl(individualId, Arrays.asList(member));
        if (individualDBAdaptorAcl == null || individualDBAdaptorAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        individualDBAdaptor.removeAcl(individualId, member);

        individualDBAdaptorAcl.setId("Remove individual ACLs");
        return individualDBAdaptorAcl;
    }

    @Override
    public QueryResult<IndividualAcl> updateIndividualAcl(String userId, long individualId, String member, @Nullable String addPermissions,
                                                          @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        individualDBAdaptor.checkIndividualId(individualId);
        checkIndividualPermission(individualId, userId, IndividualAcl.IndividualPermissions.SHARE);

        long studyId = individualDBAdaptor.getStudyIdByIndividualId(individualId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogIndividualDBAdaptor.QueryParams.ID.key(), individualId)
                .append(CatalogIndividualDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = individualDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, IndividualAcl.IndividualPermissions::valueOf);

            individualDBAdaptor.setAclsToMember(individualId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, IndividualAcl.IndividualPermissions::valueOf);

                individualDBAdaptor.addAclsToMember(individualId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, IndividualAcl.IndividualPermissions::valueOf);

                individualDBAdaptor.removeAclsFromMember(individualId, member, permissions);
            }
        }

        return individualDBAdaptor.getAcl(individualId, Arrays.asList(member));
    }

    @Override
    public QueryResult<CohortAcl> createCohortAcls(String userId, long cohortId, List<String> members, List<String> permissions)
            throws CatalogException {
        cohortDBAdaptor.checkCohortId(cohortId);
        // Check if the userId has proper permissions for all the cohorts.
        checkCohortPermission(cohortId, userId, CohortAcl.CohortPermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = cohortDBAdaptor.getStudyIdByCohortId(cohortId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the file
        if (anyMemberHasPermissions(studyId, cohortId, members, cohortDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular cohort. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<CohortAcl> cohortAclList = new ArrayList<>(members.size());
        for (String member : members) {
            CohortAcl cohortAcl = new CohortAcl(member, permissions);
            QueryResult<CohortAcl> cohortAclQueryResult = cohortDBAdaptor.createAcl(cohortId, cohortAcl);
            timeSpent += cohortAclQueryResult.getDbTime();
            cohortAclList.add(cohortAclQueryResult.first());
        }

        return new QueryResult<>("create cohort acl", timeSpent, cohortAclList.size(), cohortAclList.size(), "", "", cohortAclList);
    }

    @Override
    public QueryResult<CohortAcl> getAllCohortAcls(String userId, long cohortId) throws CatalogException {
        cohortDBAdaptor.checkCohortId(cohortId);
        // Check if the userId has proper permissions for all the cohorts.
        checkCohortPermission(cohortId, userId, CohortAcl.CohortPermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogCohortDBAdaptor.QueryParams.ID.key(), cohortId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogCohortDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Cohort> queryResult = cohortDBAdaptor.get(query, queryOptions);

        List<CohortAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get cohort acls", queryResult.getDbTime(), aclList.size(), aclList.size(),
                queryResult.getWarningMsg(), queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<CohortAcl> getCohortAcl(String userId, long cohortId, String member) throws CatalogException {
        cohortDBAdaptor.checkCohortId(cohortId);

        long studyId = cohortDBAdaptor.getStudyIdByCohortId(cohortId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkCohortPermission(cohortId, userId, CohortAcl.CohortPermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return cohortDBAdaptor.getAcl(cohortId, members);
    }

    @Override
    public QueryResult<CohortAcl> removeCohortAcl(String userId, long cohortId, String member) throws CatalogException {
        cohortDBAdaptor.checkCohortId(cohortId);
        checkCohortPermission(cohortId, userId, CohortAcl.CohortPermissions.SHARE);

        long studyId = cohortDBAdaptor.getStudyIdByCohortId(cohortId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<CohortAcl> cohortDBAdaptorAcl = cohortDBAdaptor.getAcl(cohortId, Arrays.asList(member));
        if (cohortDBAdaptorAcl == null || cohortDBAdaptorAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        cohortDBAdaptor.removeAcl(cohortId, member);

        cohortDBAdaptorAcl.setId("Remove cohort ACLs");
        return cohortDBAdaptorAcl;
    }

    @Override
    public QueryResult<CohortAcl> updateCohortAcl(String userId, long cohortId, String member, @Nullable String addPermissions,
                                                  @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        cohortDBAdaptor.checkCohortId(cohortId);
        checkCohortPermission(cohortId, userId, CohortAcl.CohortPermissions.SHARE);

        long studyId = cohortDBAdaptor.getStudyIdByCohortId(cohortId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogCohortDBAdaptor.QueryParams.ID.key(), cohortId)
                .append(CatalogCohortDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = cohortDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, CohortAcl.CohortPermissions::valueOf);

            cohortDBAdaptor.setAclsToMember(cohortId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, CohortAcl.CohortPermissions::valueOf);

                cohortDBAdaptor.addAclsToMember(cohortId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, CohortAcl.CohortPermissions::valueOf);

                cohortDBAdaptor.removeAclsFromMember(cohortId, member, permissions);
            }
        }

        return cohortDBAdaptor.getAcl(cohortId, Arrays.asList(member));
    }

    @Override
    public QueryResult<DatasetAcl> createDatasetAcls(String userId, long datasetId, List<String> members, List<String> permissions)
            throws CatalogException {
        datasetDBAdaptor.checkDatasetId(datasetId);
        // Check if the userId has proper permissions for all the datasets.
        checkDatasetPermission(datasetId, userId, DatasetAcl.DatasetPermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = datasetDBAdaptor.getStudyIdByDatasetId(datasetId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the file
        if (anyMemberHasPermissions(studyId, datasetId, members, datasetDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular dataset. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<DatasetAcl> datasetAclList = new ArrayList<>(members.size());
        for (String member : members) {
            DatasetAcl datasetAcl = new DatasetAcl(member, permissions);
            QueryResult<DatasetAcl> datasetAclQueryResult = datasetDBAdaptor.createAcl(datasetId, datasetAcl);
            timeSpent += datasetAclQueryResult.getDbTime();
            datasetAclList.add(datasetAclQueryResult.first());
        }

        return new QueryResult<>("create dataset acl", timeSpent, datasetAclList.size(), datasetAclList.size(), "", "",
                datasetAclList);
    }

    @Override
    public QueryResult<DatasetAcl> getAllDatasetAcls(String userId, long datasetId) throws CatalogException {
        datasetDBAdaptor.checkDatasetId(datasetId);
        // Check if the userId has proper permissions for all the datasets.
        checkDatasetPermission(datasetId, userId, DatasetAcl.DatasetPermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogDatasetDBAdaptor.QueryParams.ID.key(), datasetId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogDatasetDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Dataset> queryResult = datasetDBAdaptor.get(query, queryOptions);

        List<DatasetAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get dataset acls", queryResult.getDbTime(), aclList.size(), aclList.size(),
                queryResult.getWarningMsg(), queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<DatasetAcl> getDatasetAcl(String userId, long datasetId, String member) throws CatalogException {
        datasetDBAdaptor.checkDatasetId(datasetId);

        long studyId = datasetDBAdaptor.getStudyIdByDatasetId(datasetId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkDatasetPermission(datasetId, userId, DatasetAcl.DatasetPermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return datasetDBAdaptor.getAcl(datasetId, members);
    }

    @Override
    public QueryResult<DatasetAcl> removeDatasetAcl(String userId, long datasetId, String member) throws CatalogException {
        datasetDBAdaptor.checkDatasetId(datasetId);
        checkDatasetPermission(datasetId, userId, DatasetAcl.DatasetPermissions.SHARE);

        long studyId = datasetDBAdaptor.getStudyIdByDatasetId(datasetId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<DatasetAcl> datasetDBAdaptorAcl = datasetDBAdaptor.getAcl(datasetId, Arrays.asList(member));
        if (datasetDBAdaptorAcl == null || datasetDBAdaptorAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        datasetDBAdaptor.removeAcl(datasetId, member);

        datasetDBAdaptorAcl.setId("Remove dataset ACLs");
        return datasetDBAdaptorAcl;
    }

    @Override
    public QueryResult<DatasetAcl> updateDatasetAcl(String userId, long datasetId, String member, @Nullable String addPermissions,
                                                    @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        datasetDBAdaptor.checkDatasetId(datasetId);
        checkDatasetPermission(datasetId, userId, DatasetAcl.DatasetPermissions.SHARE);

        long studyId = datasetDBAdaptor.getStudyIdByDatasetId(datasetId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogDatasetDBAdaptor.QueryParams.ID.key(), datasetId)
                .append(CatalogDatasetDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = datasetDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, DatasetAcl.DatasetPermissions::valueOf);

            datasetDBAdaptor.setAclsToMember(datasetId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, DatasetAcl.DatasetPermissions::valueOf);

                datasetDBAdaptor.addAclsToMember(datasetId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, DatasetAcl.DatasetPermissions::valueOf);

                datasetDBAdaptor.removeAclsFromMember(datasetId, member, permissions);
            }
        }

        return datasetDBAdaptor.getAcl(datasetId, Arrays.asList(member));
    }

    @Override
    public QueryResult<JobAcl> createJobAcls(String userId, long jobId, List<String> members, List<String> permissions)
            throws CatalogException {
        jobDBAdaptor.checkJobId(jobId);
        // Check if the userId has proper permissions for all the jobs.
        checkJobPermission(jobId, userId, JobAcl.JobPermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        long studyId = jobDBAdaptor.getStudyIdByJobId(jobId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the job
        if (anyMemberHasPermissions(studyId, jobId, members, jobDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular job. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<JobAcl> jobAclList = new ArrayList<>(members.size());
        for (String member : members) {
            JobAcl jobAcl = new JobAcl(member, permissions);
            QueryResult<JobAcl> jobAclQueryResult = jobDBAdaptor.createAcl(jobId, jobAcl);
            timeSpent += jobAclQueryResult.getDbTime();
            jobAclList.add(jobAclQueryResult.first());
        }

        return new QueryResult<>("create job acl", timeSpent, jobAclList.size(), jobAclList.size(), "", "", jobAclList);
    }

    @Override
    public QueryResult<JobAcl> getAllJobAcls(String userId, long jobId) throws CatalogException {
        jobDBAdaptor.checkJobId(jobId);
        // Check if the userId has proper permissions for all the jobs.
        checkJobPermission(jobId, userId, JobAcl.JobPermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogJobDBAdaptor.QueryParams.ID.key(), jobId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogJobDBAdaptor.QueryParams.ACLS.key());
        QueryResult<Job> queryResult = jobDBAdaptor.get(query, queryOptions);

        List<JobAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get job acls", queryResult.getDbTime(), aclList.size(), aclList.size(),
                queryResult.getWarningMsg(), queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<JobAcl> getJobAcl(String userId, long jobId, String member) throws CatalogException {
        jobDBAdaptor.checkJobId(jobId);

        long studyId = jobDBAdaptor.getStudyIdByJobId(jobId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkJobPermission(jobId, userId, JobAcl.JobPermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return jobDBAdaptor.getAcl(jobId, members);
    }

    @Override
    public QueryResult<JobAcl> removeJobAcl(String userId, long jobId, String member) throws CatalogException {
        jobDBAdaptor.checkJobId(jobId);
        checkJobPermission(jobId, userId, JobAcl.JobPermissions.SHARE);

        long studyId = jobDBAdaptor.getStudyIdByJobId(jobId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<JobAcl> jobDBAdaptorAcl = jobDBAdaptor.getAcl(jobId, Arrays.asList(member));
        if (jobDBAdaptorAcl == null || jobDBAdaptorAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        jobDBAdaptor.removeAcl(jobId, member);

        jobDBAdaptorAcl.setId("Remove job ACLs");
        return jobDBAdaptorAcl;
    }

    @Override
    public QueryResult<JobAcl> updateJobAcl(String userId, long jobId, String member, @Nullable String addPermissions,
                                            @Nullable String removePermissions, @Nullable String setPermissions) throws CatalogException {
        jobDBAdaptor.checkJobId(jobId);
        checkJobPermission(jobId, userId, JobAcl.JobPermissions.SHARE);

        long studyId = jobDBAdaptor.getStudyIdByJobId(jobId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogJobDBAdaptor.QueryParams.ID.key(), jobId)
                .append(CatalogJobDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = jobDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, JobAcl.JobPermissions::valueOf);

            jobDBAdaptor.setAclsToMember(jobId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, JobAcl.JobPermissions::valueOf);

                jobDBAdaptor.addAclsToMember(jobId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, JobAcl.JobPermissions::valueOf);

                jobDBAdaptor.removeAclsFromMember(jobId, member, permissions);
            }
        }

        return jobDBAdaptor.getAcl(jobId, Arrays.asList(member));
    }

    @Override
    public QueryResult<DiseasePanelAcl> createPanelAcls(String userId, long panelId, List<String> members, List<String> permissions)
            throws CatalogException {
        panelDBAdaptor.checkPanelId(panelId);
        // Check if the userId has proper permissions for all the panels.
        checkDiseasePanelPermission(panelId, userId, DiseasePanelAcl.DiseasePanelPermissions.SHARE);

        // Check if all the members have a permission already set at the study level.
        Set<Long> studySet = new HashSet<>();
        long studyId = panelDBAdaptor.getStudyIdByPanelId(panelId);
        studySet.add(studyId);
        for (String member : members) {
            if (!member.equals("*") && !member.equals("anonymous") && !memberHasPermissionsInStudy(studyId, member)) {
                throw new CatalogException("Cannot create ACL for " + member + ". First, a general study permission must be "
                        + "defined for that member.");
            }
        }

        // Check all the members exist in all the possible different studies
        checkMembers(dbAdaptorFactory, studyId, members);

        // Check if any of the members already have permissions set in the panel
        if (anyMemberHasPermissions(studyId, panelId, members, panelDBAdaptor)) {
            throw new CatalogException("Cannot create ACL. At least one of the members already have some permissions set for this "
                    + "particular panel. Please, use update instead.");
        }

        // Set the permissions
        int timeSpent = 0;
        List<DiseasePanelAcl> panelAclList = new ArrayList<>(members.size());
        for (String member : members) {
            DiseasePanelAcl panelAcl = new DiseasePanelAcl(member, permissions);
            QueryResult<DiseasePanelAcl> panelAclQueryResult = panelDBAdaptor.createAcl(panelId, panelAcl);
            timeSpent += panelAclQueryResult.getDbTime();
            panelAclList.add(panelAclQueryResult.first());
        }

        return new QueryResult<>("create panel acl", timeSpent, panelAclList.size(), panelAclList.size(), "", "",
                panelAclList);
    }

    @Override
    public QueryResult<DiseasePanelAcl> getAllPanelAcls(String userId, long panelId) throws CatalogException {
        panelDBAdaptor.checkPanelId(panelId);
        // Check if the userId has proper permissions for all the panels.
        checkDiseasePanelPermission(panelId, userId, DiseasePanelAcl.DiseasePanelPermissions.SHARE);

        // Obtain the Acls
        Query query = new Query(CatalogPanelDBAdaptor.QueryParams.ID.key(), panelId);
        QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, CatalogPanelDBAdaptor.QueryParams.ACLS.key());
        QueryResult<DiseasePanel> queryResult = panelDBAdaptor.get(query, queryOptions);

        List<DiseasePanelAcl> aclList;
        if (queryResult != null && queryResult.getNumResults() == 1) {
            aclList = queryResult.first().getAcls();
        } else {
            aclList = Collections.emptyList();
        }
        return new QueryResult<>("get panel acls", queryResult.getDbTime(), aclList.size(), aclList.size(),
                queryResult.getWarningMsg(), queryResult.getErrorMsg(), aclList);
    }

    @Override
    public QueryResult<DiseasePanelAcl> getPanelAcl(String userId, long panelId, String member) throws CatalogException {
        panelDBAdaptor.checkPanelId(panelId);

        long studyId = panelDBAdaptor.getStudyIdByPanelId(panelId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        try {
            checkDiseasePanelPermission(panelId, userId, DiseasePanelAcl.DiseasePanelPermissions.SHARE);
        } catch (CatalogException e) {
            // It will be OK if the userId asking for the ACLs wants to see its own permissions
            if (member.startsWith("@")) { //group
                // If the userId does not belong to the group...
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, userId);
                if (groupBelonging.getNumResults() != 1 || !groupBelonging.first().getName().equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            } else {
                // If the userId asking to see the permissions is not asking to see their own permissions
                if (!userId.equals(member)) {
                    throw new CatalogAuthorizationException("The user " + userId + " does not have permissions to see the ACLs of "
                            + member);
                }
            }
        }

        List<String> members = new ArrayList<>(2);
        members.add(member);
        if (!member.startsWith("@") && !member.equalsIgnoreCase("anonymous") && !member.equals("*")) {
            QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
            if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                members.add(groupBelonging.first().getName());
            }
        }

        return panelDBAdaptor.getAcl(panelId, members);
    }

    @Override
    public QueryResult<DiseasePanelAcl> removePanelAcl(String userId, long panelId, String member) throws CatalogException {
        panelDBAdaptor.checkPanelId(panelId);
        checkDiseasePanelPermission(panelId, userId, DiseasePanelAcl.DiseasePanelPermissions.SHARE);

        long studyId = panelDBAdaptor.getStudyIdByPanelId(panelId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Obtain the ACLs the member had
        QueryResult<DiseasePanelAcl> panelDBAdaptorAcl = panelDBAdaptor.getAcl(panelId, Arrays.asList(member));
        if (panelDBAdaptorAcl == null || panelDBAdaptorAcl.getNumResults() == 0) {
            throw new CatalogException("Could not remove the ACLs for " + member + ". It seems " + member + " did not have any ACLs "
                    + "defined");
        }

        panelDBAdaptor.removeAcl(panelId, member);

        panelDBAdaptorAcl.setId("Remove panel ACLs");
        return panelDBAdaptorAcl;
    }

    @Override
    public QueryResult<DiseasePanelAcl> updatePanelAcl(String userId, long panelId, String member, @Nullable String addPermissions,
                                                       @Nullable String removePermissions, @Nullable String setPermissions)
            throws CatalogException {
        panelDBAdaptor.checkPanelId(panelId);
        checkDiseasePanelPermission(panelId, userId, DiseasePanelAcl.DiseasePanelPermissions.SHARE);

        long studyId = panelDBAdaptor.getStudyIdByPanelId(panelId);
        checkMembers(dbAdaptorFactory, studyId, Arrays.asList(member));

        // Check that the member has permissions
        Query query = new Query()
                .append(CatalogPanelDBAdaptor.QueryParams.ID.key(), panelId)
                .append(CatalogPanelDBAdaptor.QueryParams.ACLS_MEMBER.key(), member);
        QueryResult<Long> count = panelDBAdaptor.count(query);
        if (count == null || count.first() == 0) {
            throw new CatalogException("Could not update ACLs for " + member + ". It seems the member does not have any permissions set "
                    + "yet.");
        }

        List<String> permissions;
        if (setPermissions != null) {
            permissions = Arrays.asList(setPermissions.split(","));
            // Check if the permissions are correct
            checkPermissions(permissions, DiseasePanelAcl.DiseasePanelPermissions::valueOf);

            panelDBAdaptor.setAclsToMember(panelId, member, permissions);
        } else {
            if (addPermissions != null) {
                permissions = Arrays.asList(addPermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, DiseasePanelAcl.DiseasePanelPermissions::valueOf);

                panelDBAdaptor.addAclsToMember(panelId, member, permissions);
            }

            if (removePermissions != null) {
                permissions = Arrays.asList(removePermissions.split(","));
                // Check if the permissions are correct
                checkPermissions(permissions, DiseasePanelAcl.DiseasePanelPermissions::valueOf);

                panelDBAdaptor.removeAclsFromMember(panelId, member, permissions);
            }
        }

        return panelDBAdaptor.getAcl(panelId, Arrays.asList(member));
    }

    /**
     * Checks whether any of the members already have any permission set for the particular document.
     *
     * @param studyId study id where the main id belongs to.
     * @param id id of the document that is going to be checked (file id, sample id, cohort id...)
     * @param members List of members (users or groups) that will be checked.
     * @param dbAdaptor Mongo db adaptor to make the mongo query.
     * @return a boolean indicating whether any of the members already have permissions.
     */
    private boolean anyMemberHasPermissions(long studyId, long id, List<String> members, CatalogAclDBAdaptor dbAdaptor)
            throws CatalogException {

        List<String> allMembers = new ArrayList<>(members.size());
        allMembers.addAll(members);

        for (String member : members) {
            if (member.startsWith("@")) { // It's a group
                // Obtain the users of the group
                QueryResult<Group> group = studyDBAdaptor.getGroup(studyId, member, Collections.emptyList());
                if (group != null && group.getNumResults() == 1) {
                    allMembers.addAll(group.first().getUserIds());
                }
            } else if (!member.equalsIgnoreCase("anonymous") && !member.equals("*")) { // It's a user id
                // Get the group where the user might belong to
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
                if (groupBelonging != null && groupBelonging.getNumResults() == 1) {
                    allMembers.add(groupBelonging.first().getName());
                }
            }
        }

        return dbAdaptor.getAcl(id, allMembers).getNumResults() > 0;
    }

    @Override
    public boolean memberHasPermissionsInStudy(long studyId, String member) throws CatalogException {
        List<String> memberList = new ArrayList<>();
        memberList.add(member);
        if (!member.startsWith("@")) { // User
            if (member.equals("admin") || isStudyOwner(studyId, member)) {
                return true;
            }
            if (!member.equals("anonymous") && !member.equals("*")) {
                QueryResult<Group> groupBelonging = getGroupBelonging(studyId, member);
                if (groupBelonging.getNumResults() > 0) {
                    memberList.add(groupBelonging.first().getName()); // Add the groupId to the memberList
                }
            }
        }
        StudyAcl studyAcl = getStudyAclBelonging(studyId, memberList);
        return studyAcl != null;
    }

    private boolean isStudyOwner(long studyId, String userId) throws CatalogDBException {
        return studyDBAdaptor.getStudyOwnerId(studyId).equals(userId);
    }

    /**
     * This is like a mini cache where we will store the permissions for each path and user. This is useful for single queries trying to
     * fetch multiple files for example. This way, we only retrieve the permissions once while maintaining the authorization step for each
     * one.
     */
    static class StudyAuthenticationContext {
        private final long studyId;
        private final Map<String, Map<String, FileAcl>> pathUserAclMap;

        StudyAuthenticationContext(long studyId) {
            this.studyId = studyId;
            pathUserAclMap = new HashMap<>();
        }
    }

    /**
     * Return the Acls for the user, group (if any) and {@link #OTHER_USERS_ID}.
     *
     * @param studyAuthenticationContext Context with already fetched elements. Will avoid fetch extra data
     * @param userId                     User id
     * @param studyId                    Study identifier
     * @param groupId                    User belonging group. May be null.
     * @param paths                      List of paths to check
     * @return Map (Path -> Map (UserId -> FileAcl) )
     * @throws CatalogDBException
     */
    private Map<String, Map<String, FileAcl>> getFileAcls(StudyAuthenticationContext studyAuthenticationContext, String userId,
                                                          long studyId, String groupId, List<String> paths) throws CatalogDBException {

        // Make a copy of the pathsClone
        List<String> pathsClone = new ArrayList<>(paths.size());
        for (String path : paths) {
            pathsClone.add(path);
        }

        // We first remove the pathsClone for which we already have the ACL
        for (Iterator<String> iterator = pathsClone.iterator(); iterator.hasNext();) {
            String path = iterator.next();
            if (studyAuthenticationContext.pathUserAclMap.containsKey(path)) {
                Map<String, FileAcl> userAclMap = studyAuthenticationContext.pathUserAclMap.get(path);
                if (userAclMap.containsKey(userId) && (groupId == null || userAclMap.containsKey(groupId))
                        && userAclMap.containsKey(OTHER_USERS_ID)) {
                    iterator.remove();
                }
            }
        }

        // We obtain the Acls for the paths for which we still don't have them.
        if (!pathsClone.isEmpty()) {
            // We make a query to obtain the ACLs of all the pathsClone for userId, groupId and *
            List<String> userIds = (groupId == null)
                    ? Arrays.asList(userId, OTHER_USERS_ID)
                    : Arrays.asList(userId, groupId, OTHER_USERS_ID);
            Map<String, Map<String, FileAcl>> map = fileDBAdaptor.getFilesAcl(studyId, pathsClone, userIds).first();
            for (String path : pathsClone) {
                Map<String, FileAcl> stringAclEntryMap;
                if (map.containsKey(path)) {
                    stringAclEntryMap = map.get(path);
                } else {
                    stringAclEntryMap = new HashMap<>();
                }
                stringAclEntryMap.putIfAbsent(userId, null);
                if (groupId != null) {
                    stringAclEntryMap.putIfAbsent(groupId, null);
                }
                stringAclEntryMap.putIfAbsent(OTHER_USERS_ID, null);

                if (studyAuthenticationContext.pathUserAclMap.containsKey(path)) {
                    studyAuthenticationContext.pathUserAclMap.get(path).putAll(stringAclEntryMap);
                } else {
                    studyAuthenticationContext.pathUserAclMap.put(path, stringAclEntryMap);
                }
            }
        }

        return studyAuthenticationContext.pathUserAclMap;
    }


    /*
    ====================================
    Auxiliar methods
    ====================================
     */
    /**
     * Retrieves the groupId where the members belongs to.
     *
     * @param studyId study id.
     * @param members List of user ids.
     * @return the group id of the user. Null if the user does not take part of any group.
     * @throws CatalogException when there is any database error.
     */
    QueryResult<Group> getGroupBelonging(long studyId, List<String> members) throws CatalogException {
        return studyDBAdaptor.getGroup(studyId, null, members);
    }
    QueryResult<Group> getGroupBelonging(long studyId, String members) throws CatalogException {
        return getGroupBelonging(studyId, Arrays.asList(members.split(",")));
    }

    /**
     * Retrieves the StudyAcl where the user/group belongs to.
     *
     * @param studyId study id.
     * @param userId user id.
     * @param groupId group id. This can be null.
     * @return the studyAcl where the user/group belongs to.
     * @throws CatalogException when there is any database error.
     */
    StudyAcl getStudyAclBelonging(long studyId, String userId, @Nullable String groupId) throws CatalogException {
        List<String> members = groupId != null ? Arrays.asList(userId, groupId) : Arrays.asList(userId);
        return getStudyAclBelonging(studyId, members);
    }

    /**
     * Retrieves the studyAcl for the members.
     *
     * @param studyId study id.
     * @param members Might be one user, one group or one user and the group where the user belongs to.
     * @return the studyAcl of the user/group.
     * @throws CatalogException when there is a database error.
     */
    StudyAcl getStudyAclBelonging(long studyId, List<String> members) throws CatalogException {
        QueryResult<StudyAcl> studyQueryResult = studyDBAdaptor.getAcl(studyId, members);
        if (studyQueryResult.getNumResults() > 0) {
            return studyQueryResult.first();
        }
        return null;
    }

    public static void checkPermissions(List<String> permissions, Function<String, Enum> getValue) throws CatalogException {
        for (String permission : permissions) {
            try {
                getValue.apply(permission);
            } catch (IllegalArgumentException e) {
                throw new CatalogException("The permission " + permission + " is not a correct permission.");
            }
        }
    }

    /*
    ====================================
    ACL transformation methods
    ====================================
     */
    private FileAcl transformStudyAclToFileAcl(StudyAcl studyAcl) {
        FileAcl fileAcl = new FileAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return fileAcl;
        }

        fileAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<FileAcl.FilePermissions> filePermissions = EnumSet.noneOf(FileAcl.FilePermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            FileAcl.FilePermissions aux = studyPermission.getFilePermission();
            if (aux != null) {
                filePermissions.add(aux);
            }
        }
        fileAcl.setPermissions(filePermissions);
        return fileAcl;
    }

    private SampleAcl transformStudyAclToSampleAcl(StudyAcl studyAcl) {
        SampleAcl sampleAcl = new SampleAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return sampleAcl;
        }

        sampleAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<SampleAcl.SamplePermissions> samplePermission = EnumSet.noneOf(SampleAcl.SamplePermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            SampleAcl.SamplePermissions aux = studyPermission.getSamplePermission();
            if (aux != null) {
                samplePermission.add(aux);
            }
        }
        sampleAcl.setPermissions(samplePermission);
        return sampleAcl;
    }

    private IndividualAcl transformStudyAclToIndividualAcl(StudyAcl studyAcl) {
        IndividualAcl individualAcl = new IndividualAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return individualAcl;
        }

        individualAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<IndividualAcl.IndividualPermissions> individualPermissions = EnumSet.noneOf(IndividualAcl.IndividualPermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            IndividualAcl.IndividualPermissions aux = studyPermission.getIndividualPermission();
            if (aux != null) {
                individualPermissions.add(aux);
            }
        }
        individualAcl.setPermissions(individualPermissions);
        return individualAcl;
    }

    private JobAcl transformStudyAclToJobAcl(StudyAcl studyAcl) {
        JobAcl jobAcl = new JobAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return jobAcl;
        }

        jobAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<JobAcl.JobPermissions> jobPermissions = EnumSet.noneOf(JobAcl.JobPermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            JobAcl.JobPermissions aux = studyPermission.getJobPermission();
            if (aux != null) {
                jobPermissions.add(aux);
            }
        }
        jobAcl.setPermissions(jobPermissions);
        return jobAcl;
    }

    private CohortAcl transformStudyAclToCohortAcl(StudyAcl studyAcl) {
        CohortAcl cohortAcl = new CohortAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return cohortAcl;
        }

        cohortAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<CohortAcl.CohortPermissions> cohortPermissions = EnumSet.noneOf(CohortAcl.CohortPermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            CohortAcl.CohortPermissions aux = studyPermission.getCohortPermission();
            if (aux != null) {
                cohortPermissions.add(aux);
            }
        }
        cohortAcl.setPermissions(cohortPermissions);
        return cohortAcl;
    }

    private DatasetAcl transformStudyAclToDatasetAcl(StudyAcl studyAcl) {
        DatasetAcl datasetAcl = new DatasetAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return datasetAcl;
        }

        datasetAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<DatasetAcl.DatasetPermissions> datasetPermissions = EnumSet.noneOf(DatasetAcl.DatasetPermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            DatasetAcl.DatasetPermissions aux = studyPermission.getDatasetPermission();
            if (aux != null) {
                datasetPermissions.add(aux);
            }
        }
        datasetAcl.setPermissions(datasetPermissions);
        return datasetAcl;
    }

    private DiseasePanelAcl transformStudyAclToDiseasePanelAcl(StudyAcl studyAcl) {
        DiseasePanelAcl panelAcl = new DiseasePanelAcl(null, Collections.emptyList());
        if (studyAcl == null) {
            return panelAcl;
        }

        panelAcl.setMember(studyAcl.getMember());
        EnumSet<StudyAcl.StudyPermissions> studyPermissions = studyAcl.getPermissions();
        EnumSet<DiseasePanelAcl.DiseasePanelPermissions> datasetPermissions = EnumSet.noneOf(DiseasePanelAcl.DiseasePanelPermissions.class);

        for (StudyAcl.StudyPermissions studyPermission : studyPermissions) {
            DiseasePanelAcl.DiseasePanelPermissions aux = studyPermission.getDiseasePanelPermission();
            if (aux != null) {
                datasetPermissions.add(aux);
            }
        }
        panelAcl.setPermissions(datasetPermissions);
        return panelAcl;
    }
}
