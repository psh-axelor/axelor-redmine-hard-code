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
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.project.db.ProjectCategory;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.apps.redmine.db.repo.RedmineSyncMappingRepository;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.team.db.TeamTask;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.IssueManager;
import com.taskadapter.redmineapi.ProjectManager;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.CustomField;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.Version;
import com.taskadapter.redmineapi.internal.Transport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineImportIssueServiceImpl extends RedmineSyncService
    implements RedmineImportIssueService {

  protected RedmineSyncMappingRepository redmineSyncMappingRepository;

  @Inject
  public RedmineImportIssueServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo,
      RedmineSyncMappingRepository redmineSyncMappingRepository) {

    super(userRepo, projectRepo, productRepo, teamTaskRepo, projectCategoryRepo, partnerRepo);
    this.redmineSyncMappingRepository = redmineSyncMappingRepository;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected Product product;
  protected TeamTask parentTask;
  protected Project project;
  protected ProjectCategory projectCategory;

  @Override
  @SuppressWarnings("unchecked")
  public void importIssue(
      List<Issue> redmineIssueList,
      HashMap<String, Object> paramsMap,
      HashMap<String, String> importSelectionMap,
      HashMap<String, String> importFieldMap) {

    if (redmineIssueList != null && !redmineIssueList.isEmpty()) {
      this.onError = (Consumer<Throwable>) paramsMap.get("onError");
      this.onSuccess = (Consumer<Object>) paramsMap.get("onSuccess");
      this.batch = (Batch) paramsMap.get("batch");
      this.redmineIssueManager = (IssueManager) paramsMap.get("redmineIssueManager");
      this.redmineUserManager = (UserManager) paramsMap.get("redmineUserManager");
      this.redmineProjectManager = (ProjectManager) paramsMap.get("redmineProjectManager");
      this.redmineTransport = (Transport) paramsMap.get("redmineTransport");
      this.errorObjList = (List<Object[]>) paramsMap.get("errorObjList");
      this.lastBatchUpdatedOn = (LocalDateTime) paramsMap.get("lastBatchUpdatedOn");
      this.selectionMap = importSelectionMap;
      this.fieldMap = importFieldMap;

      redmineIssueList.sort(
          new Comparator<Issue>() {
            @Override
            public int compare(Issue arg0, Issue arg1) {
              if (arg1.getParentId() != null) {
                return 0;
              }
              return -1;
            }
          });
      Collections.reverse(redmineIssueList);

      LOG.debug("Total issues to import: {}", redmineIssueList.size());

      int i = 0;

      for (Issue redmineIssue : redmineIssueList) {

        // Error and don't import if product not found
        CustomField redmineProduct = redmineIssue.getCustomFieldByName("Product");
        this.product = findOpenSuiteProduct(redmineProduct);

        if (product == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
              null,
              redmineIssue.getId().toString(),
              I18n.get(IMessage.REDMINE_SYNC_CUSTOM_FIELD_PRODUCT_NOT_FOUND));

          fail++;
          continue;
        }

        // Error and don't import if parent task not found
        if (redmineIssue.getParentId() != null) {
          this.parentTask = findOpenSuiteTask(redmineIssue.getParentId());

          if (parentTask == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
                null,
                redmineIssue.getId().toString(),
                I18n.get(IMessage.REDMINE_SYNC_PARENT_TASK_NOT_FOUND));

            fail++;
            continue;
          }
        }

        // Error and don't import if project not found
        try {
          this.project = findOpenSuiteProject(redmineIssue.getProjectId());

          if (project == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
                null,
                redmineIssue.getId().toString(),
                I18n.get(IMessage.REDMINE_SYNC_PROJECT_NOT_FOUND));

            fail++;
            continue;
          }
        } catch (RedmineException e) {
          TraceBackService.trace(e, "", batch.getId());
        }

        // Error and don't import if project category not found
        String trackerName = fieldMap.get(redmineIssue.getTracker().getName());

        this.projectCategory = findOpenSuiteProjectCategory(trackerName);

        if (projectCategory == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
              null,
              redmineIssue.getId().toString(),
              I18n.get(IMessage.REDMINE_SYNC_PROJECT_CATEGORY_NOT_FOUND));

          fail++;
          continue;
        }

        try {
          this.createOpenSuiteIssue(redmineIssue);
        } finally {
          if (++i % AbstractBatch.FETCH_LIMIT == 0) {
            JPA.em().getTransaction().commit();

            if (!JPA.em().getTransaction().isActive()) {
              JPA.em().getTransaction().begin();
            }
          }
        }
      }
    }

    String resultStr =
        String.format("Redmine Issue -> ABS Teamtask : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  @Transactional
  public void createOpenSuiteIssue(Issue redmineIssue) {

    CustomField osId = redmineIssue.getCustomFieldByName("OS Id");
    String osIdValue = osId.getValue();
    TeamTask teamTask;

    if (osIdValue != null && !osIdValue.equals("0")) {
      teamTask = teamTaskRepo.find(Long.parseLong(osIdValue));
    } else {
      teamTask = teamTaskRepo.findByRedmineId(redmineIssue.getId());
    }

    if (teamTask == null) {
      teamTask = new TeamTask();
      teamTask.setTypeSelect(TeamTaskRepository.TYPE_TASK);
    } else if (lastBatchUpdatedOn != null) {
      LocalDateTime redmineUpdatedOn =
          redmineIssue.getUpdatedOn().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

      if (redmineUpdatedOn.isBefore(lastBatchUpdatedOn)
          || (teamTask.getUpdatedOn().isAfter(lastBatchUpdatedOn)
              && teamTask.getUpdatedOn().isAfter(redmineUpdatedOn))) {
        return;
      }
    }

    LOG.debug("Importing issue: " + redmineIssue.getId());

    this.setTeamTaskFields(teamTask, redmineIssue);

    try {
      teamTask.addOsbatchSetItem(batch);
      teamTaskRepo.save(teamTask);
      onSuccess.accept(teamTask);
      success++;

      redmineIssue.setTransport(redmineTransport);
      osId.setValue(teamTask.getId().toString());
      redmineIssue.update();
    } catch (Exception e) {
      onError.accept(e);
      fail++;
      TraceBackService.trace(e, "", batch.getId());
    }
  }

  public void setTeamTaskFields(TeamTask teamTask, Issue redmineIssue) {

    try {
      teamTask.setRedmineId(redmineIssue.getId());
      teamTask.setProduct(product);
      teamTask.setParentTask(parentTask);
      teamTask.setProject(project);
      teamTask.setProjectCategory(projectCategory);
      teamTask.setName(redmineIssue.getSubject());
      teamTask.setDescription(redmineIssue.getDescription());
      teamTask.setAssignedTo(findOpenSuiteUser(redmineIssue.getAssigneeId(), null));
      teamTask.setProgressSelect(redmineIssue.getDoneRatio());

      Float estimatedHours = redmineIssue.getEstimatedHours();

      if (estimatedHours != null) {
        teamTask.setBudgetedTime(BigDecimal.valueOf(estimatedHours));
      }

      Date closedOn = redmineIssue.getClosedOn();

      if (closedOn != null) {
        teamTask.setTaskEndDate(closedOn.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
      }

      Version targetVersion = redmineIssue.getTargetVersion();

      if (targetVersion != null) {
        teamTask.setFixedVersion(targetVersion.getName());
      }

      CustomField customField = redmineIssue.getCustomFieldByName("Prestation refusée/annulée");
      String value = customField.getValue();
      teamTask.setIsTaskRefused(
          value != null && !value.equals("") ? (value.equals("1") ? true : false) : false);

      customField = redmineIssue.getCustomFieldByName("Date d'échéance (INTERNE)");
      value = customField.getValue();

      if (value != null && !value.equals("")) {
        teamTask.setTaskDate(LocalDate.parse(value));
      }

      customField = redmineIssue.getCustomFieldByName("Temps estimé (INTERNE)");
      value = customField.getValue();

      if (value != null && !value.equals("")) {
        teamTask.setTotalPlannedHrs(new BigDecimal(value));
      }

      String status = fieldMap.get(redmineIssue.getStatusName());

      // Error if status not found
      if (status != null) {
        teamTask.setStatus(selectionMap.get(status));
      } else {
        teamTask.setStatus(TeamTaskRepository.TEAM_TASK_DEFAULT_STATUS);
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
            null,
            redmineIssue.getId().toString(),
            I18n.get(IMessage.REDMINE_SYNC_IMPORT_WITH_DEFAULT_STATUS));
      }

      String priority = fieldMap.get(redmineIssue.getPriorityText());

      // Error if priority not found
      if (priority != null) {
        teamTask.setPriority(selectionMap.get(priority));
      } else {
        teamTask.setPriority(TeamTaskRepository.TEAM_TASK_DEFAULT_PRIORITY);
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
            null,
            redmineIssue.getId().toString(),
            I18n.get(IMessage.REDMINE_SYNC_IMPORT_WITH_DEFAULT_PRIORITY));
      }

      setCreatedByUser(
          teamTask, findOpenSuiteUser(redmineIssue.getAuthorId(), null), "setCreatedBy");
      setLocalDateTime(teamTask, redmineIssue.getCreatedOn(), "setCreatedOn");
      setLocalDateTime(teamTask, redmineIssue.getUpdatedOn(), "setUpdatedOn");
    } catch (Exception e) {
      TraceBackService.trace(e, "", batch.getId());
    }
  }
}
