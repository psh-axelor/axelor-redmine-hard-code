/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmine.imports.service;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.AppRedmineRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.project.db.ProjectCategory;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.IssueManager;
import com.taskadapter.redmineapi.ProjectManager;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.CustomField;
import com.taskadapter.redmineapi.bean.Membership;
import com.taskadapter.redmineapi.bean.Tracker;
import com.taskadapter.redmineapi.internal.Transport;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineImportProjectServiceImpl extends RedmineSyncService
    implements RedmineImportProjectService {

  protected AppRedmineRepository appRedmineRepo;

  @Inject
  public RedmineImportProjectServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo,
      AppRedmineRepository appRedmineRepo) {

    super(userRepo, projectRepo, productRepo, teamTaskRepo, projectCategoryRepo, partnerRepo);
    this.appRedmineRepo = appRedmineRepo;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected Company defaultCompany;

  @Override
  @SuppressWarnings("unchecked")
  public void importProject(
      List<com.taskadapter.redmineapi.bean.Project> redmineProjectList,
      HashMap<String, Object> paramsMap,
      HashMap<String, String> importFieldMap) {

    if (redmineProjectList != null && !redmineProjectList.isEmpty()) {
      this.onError = (Consumer<Throwable>) paramsMap.get("onError");
      this.onSuccess = (Consumer<Object>) paramsMap.get("onSuccess");
      this.batch = (Batch) paramsMap.get("batch");
      this.redmineIssueManager = (IssueManager) paramsMap.get("redmineIssueManager");
      this.redmineUserManager = (UserManager) paramsMap.get("redmineUserManager");
      this.redmineProjectManager = (ProjectManager) paramsMap.get("redmineProjectManager");
      this.redmineTransport = (Transport) paramsMap.get("redmineTransport");
      this.errorObjList = (List<Object[]>) paramsMap.get("errorObjList");
      this.lastBatchUpdatedOn = (LocalDateTime) paramsMap.get("lastBatchUpdatedOn");
      this.fieldMap = importFieldMap;

      this.defaultCompany = appRedmineRepo.all().fetchOne().getCompany();

      redmineProjectList.sort(
          new Comparator<com.taskadapter.redmineapi.bean.Project>() {
            @Override
            public int compare(
                com.taskadapter.redmineapi.bean.Project arg0,
                com.taskadapter.redmineapi.bean.Project arg1) {
              if (arg1.getParentId() != null) {
                return 0;
              }
              return -1;
            }
          });
      Collections.reverse(redmineProjectList);

      for (com.taskadapter.redmineapi.bean.Project redmineProject : redmineProjectList) {
        this.createOpenSuiteProject(redmineProject);
      }
    }

    String resultStr =
        String.format("Redmine Project -> ABS Project : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  @Transactional
  public void createOpenSuiteProject(com.taskadapter.redmineapi.bean.Project redmineProject) {

    this.setRedmineCustomFieldsMap(redmineProject.getCustomFields());

    CustomField osId = (CustomField) redmineCustomFieldsMap.get("OS Id");
    String osIdValue = osId.getValue();
    Project project;

    if (osIdValue != null && !osIdValue.equals("0")) {
      project = projectRepo.find(Long.parseLong(osIdValue));
    } else {
      project = projectRepo.findByRedmineId(redmineProject.getId());
    }

    if (project == null) {
      project = new Project();
    } else if (lastBatchUpdatedOn != null) {
      LocalDateTime redmineUpdatedOn =
          redmineProject
              .getUpdatedOn()
              .toInstant()
              .atZone(ZoneId.systemDefault())
              .toLocalDateTime();

      if (redmineUpdatedOn.isBefore(lastBatchUpdatedOn)
          || (project.getUpdatedOn().isAfter(lastBatchUpdatedOn)
              && project.getUpdatedOn().isAfter(redmineUpdatedOn))) {
        return;
      }
    }

    LOG.debug("Importing project: " + redmineProject.getIdentifier());

    this.setProjectFields(redmineProject, project);

    try {

      if (project.getId() == null) {
        project.addBatchSetItem(batch);
      }

      projectRepo.save(project);
      onSuccess.accept(project);
      success++;

      redmineProject.setTransport(redmineTransport);
      osId.setValue(project.getId().toString());

      if (!redmineProject.getStatus().equals(REDMINE_PROJECT_STATUS_CLOSED)) {
        redmineProject.update();
      }
    } catch (Exception e) {
      onError.accept(e);
      fail++;
      TraceBackService.trace(e, "", batch.getId());
    }
  }

  public void setProjectFields(
      com.taskadapter.redmineapi.bean.Project redmineProject, Project project) {

    project.setRedmineId(redmineProject.getId());
    project.setName(redmineProject.getName());
    project.setCode(redmineProject.getIdentifier());
    project.setDescription(redmineProject.getDescription());
    project.setCompany(defaultCompany);

    CustomField customField = (CustomField) redmineCustomFieldsMap.get("Invoiceable");
    String value = customField.getValue();

    project.setIsInvoiceable(value != null ? (value.equals("1") ? true : false) : false);
    project.setIsBusinessProject(project.getIsInvoiceable());

    customField = (CustomField) redmineCustomFieldsMap.get("Customer Code");
    value = customField.getValue();

    // Error if client partner not found
    if (value != null && !value.equals("")) {
      project.setClientPartner(findOpenSuitePartner((String) value));
    } else {
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
          null,
          redmineProject.getIdentifier(),
          I18n.get(IMessage.REDMINE_SYNC_CUSTOM_FIELD_CUSTOMER_CODE_NOT_FOUND));
    }

    Integer parentId = redmineProject.getParentId();

    if (parentId != null) {

      try {
        project.setParentProject(findOpenSuiteProject(parentId));

        // Error if parent project not found
        if (project.getParentProject() == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
              null,
              redmineProject.getIdentifier(),
              I18n.get(IMessage.REDMINE_SYNC_PARENT_PROJECT_NOT_FOUND));
        }
      } catch (RedmineException e) {
        TraceBackService.trace(e, "", batch.getId());
      }
    }

    project.setProjectTypeSelect(
        project.getParentProject() != null
            ? ProjectRepository.TYPE_PHASE
            : ProjectRepository.TYPE_PROJECT);

    try {
      List<Membership> redmineProjectMembers =
          redmineProjectManager.getProjectMembers(redmineProject.getId());

      for (Membership membership : redmineProjectMembers) {

        Integer userId = membership.getUserId();

        if (userId == null) {
          continue;
        }

        com.taskadapter.redmineapi.bean.User redmineUser = findRedmineUserById(userId);
        User user = findOpenSuiteUser(null, redmineUser);

        if (user != null) {
          project.addMembersUserSetItem(user);
        }
      }
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }

    Collection<Tracker> redmineTrackers = redmineProject.getTrackers();

    for (Tracker tracker : redmineTrackers) {
      String name = fieldMap.get(tracker.getName());
      ProjectCategory projectCategory = findOpenSuiteProjectCategory(name);

      if (projectCategory != null) {
        project.addProjectCategorySetItem(projectCategory);
      }
    }

    if (redmineProject.getStatus().equals(REDMINE_PROJECT_STATUS_CLOSED)) {
      project.setStatusSelect(ProjectRepository.STATE_FINISHED);
    }

    customField = (CustomField) redmineCustomFieldsMap.get("Invoicing Type");
    value = customField.getValue();

    if (value != null && !value.equals("")) {
      project.setInvoicingSequenceSelect(
          value.equals("Empty")
              ? ProjectRepository.INVOICING_SEQ_EMPTY
              : value.equals("Pre-invoiced")
                  ? ProjectRepository.INVOICING_SEQ_INVOICE_PRE_TASK
                  : value.equals("Post-invoiced")
                      ? ProjectRepository.INVOICING_SEQ_INVOICE_POST_TASK
                      : null);
    } else {
      // Error if invoicing type not found
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
          null,
          redmineProject.getIdentifier(),
          I18n.get(IMessage.REDMINE_SYNC_CUSTOM_FIELD_INVOICING_TYPE_NOT_FOUND));
    }

    setLocalDateTime(project, redmineProject.getCreatedOn(), "setCreatedOn");
    setLocalDateTime(project, redmineProject.getUpdatedOn(), "setUpdatedOn");
  }
}
