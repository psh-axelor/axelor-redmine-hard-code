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
package com.axelor.apps.redmine.exports.service;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.project.db.ProjectCategory;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.apps.redmine.db.repo.RedmineSyncMappingRepository;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.team.db.TeamTask;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.IssueManager;
import com.taskadapter.redmineapi.ProjectManager;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.TimeEntryManager;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.Project;
import com.taskadapter.redmineapi.bean.User;
import com.taskadapter.redmineapi.internal.Transport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineExportIssueServiceImpl extends RedmineSyncService
    implements RedmineExportIssueService {

  protected RedmineSyncMappingRepository redmineSyncMappingRepository;

  @Inject
  public RedmineExportIssueServiceImpl(
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
  protected HashMap<String, Integer> redmineStatusMap;
  protected HashMap<String, Integer> redminePriorityMap;
  protected Product product;
  protected Integer parentId;
  protected Project redmineProject;
  protected String trackerName;

  @Override
  @SuppressWarnings("unchecked")
  public void exportIssue(
      List<TeamTask> teamTaskList,
      HashMap<String, Object> paramsMap,
      HashMap<String, String> exportSelectionMap,
      HashMap<String, String> exportFieldMap) {

    if (teamTaskList != null && !teamTaskList.isEmpty()) {
      this.onError = (Consumer<Throwable>) paramsMap.get("onError");
      this.onSuccess = (Consumer<Object>) paramsMap.get("onSuccess");
      this.batch = (Batch) paramsMap.get("batch");
      this.redmineIssueManager = (IssueManager) paramsMap.get("redmineIssueManager");
      this.redmineUserManager = (UserManager) paramsMap.get("redmineUserManager");
      this.redmineProjectManager = (ProjectManager) paramsMap.get("redmineProjectManager");
      this.redmineTimeEntryManager = (TimeEntryManager) paramsMap.get("redmineTimeEntryManager");
      this.redmineTransport = (Transport) paramsMap.get("redmineTransport");
      this.errorObjList = (List<Object[]>) paramsMap.get("errorObjList");
      this.lastBatchUpdatedOn = (LocalDateTime) paramsMap.get("lastBatchUpdatedOn");
      this.selectionMap = exportSelectionMap;
      this.fieldMap = exportFieldMap;

      this.redmineStatusMap = new HashMap<String, Integer>();
      this.redminePriorityMap = new HashMap<String, Integer>();

      try {
        redmineIssueManager
            .getStatuses()
            .forEach(s -> redmineStatusMap.put(s.getName(), s.getId()));

        redmineIssueManager
            .getIssuePriorities()
            .forEach(p -> redminePriorityMap.put(p.getName(), p.getId()));
      } catch (RedmineException e) {
        TraceBackService.trace(e, "", batch.getId());
      }

      teamTaskList.sort(
          new Comparator<TeamTask>() {
            @Override
            public int compare(TeamTask arg0, TeamTask arg1) {
              if (arg1.getParentTask() == null) {
                return 0;
              }
              return -1;
            }
          });

      for (TeamTask teamTask : teamTaskList) {
        this.product = teamTask.getProduct();

        if (product == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
              teamTask.getId().toString(),
              null,
              I18n.get(IMessage.REDMINE_SYNC_PRODUCT_FIELD_NOT_SET));

          fail++;
          continue;
        }

        TeamTask parentTask = teamTask.getParentTask();

        if (parentTask != null) {
          this.parentId = parentTask.getRedmineId();

          if (parentId == null || parentId == 0) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
                teamTask.getId().toString(),
                null,
                I18n.get(IMessage.REDMINE_SYNC_ERROR_PARENT_TASK_NOT_FOUND));

            fail++;
            continue;
          }
        }

        try {
          com.axelor.apps.project.db.Project project = teamTask.getProject();
          this.redmineProject = project != null ? findRedmineProject(project.getRedmineId()) : null;

          if (redmineProject == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
                teamTask.getId().toString(),
                null,
                I18n.get(IMessage.REDMINE_SYNC_REDMINE_PROJECT_NOT_FOUND));

            fail++;
            continue;
          }
        } catch (RedmineException e) {
          TraceBackService.trace(e, "", batch.getId());
        }

        ProjectCategory projectCategory = teamTask.getProjectCategory();
        this.trackerName = projectCategory != null ? fieldMap.get(projectCategory.getName()) : null;

        if (trackerName == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
              teamTask.getId().toString(),
              null,
              I18n.get(IMessage.REDMINE_SYNC_TRACKER_NOT_FOUND));

          fail++;
          continue;
        }

        createRedmineIssue(teamTask);
      }
    }

    String resultStr =
        String.format("ABS Teamtask -> Redmine Issue : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  public void createRedmineIssue(TeamTask teamTask) {

    try {
      Issue redmineIssue = null;
      Integer redmineId = teamTask.getRedmineId();

      if (redmineId != null && redmineId != 0) {
        redmineIssue = redmineIssueManager.getIssueById(redmineId);
      }

      if (redmineIssue == null) {
        redmineIssue = new Issue();
      } else if (lastBatchUpdatedOn != null) {
        LocalDateTime osUpdatedOn = teamTask.getUpdatedOn();
        LocalDateTime redmineUpdatedOn =
            redmineIssue
                .getUpdatedOn()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        if (osUpdatedOn.isBefore(lastBatchUpdatedOn)
            || (redmineUpdatedOn.isAfter(lastBatchUpdatedOn)
                && redmineUpdatedOn.isAfter(osUpdatedOn))) {
          return;
        }
      }

      LOG.debug("Exporting teamtask: " + teamTask.getId());

      this.setRedmineIssueFields(redmineIssue, teamTask);
      this.saveRedmineIssue(redmineIssue, teamTask);
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);

      if (e.getMessage().equals(REDMINE_SERVER_404_NOT_FOUND)) {
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
            teamTask.getId().toString(),
            null,
            I18n.get(IMessage.REDMINE_SYNC_ERROR_RECORD_NOT_FOUND));
      }
    }
  }

  public void setRedmineIssueFields(Issue redmineIssue, TeamTask teamTask) {

    redmineIssue.setSubject(teamTask.getName());
    redmineIssue.setDescription(teamTask.getDescription());

    com.axelor.auth.db.User assignedTo = teamTask.getAssignedTo();
    com.axelor.auth.db.User createdBy = teamTask.getCreatedBy();

    if (assignedTo != null
        && assignedTo.getPartner() != null
        && assignedTo.getPartner().getEmailAddress() != null) {
      User redmineUser =
          findRedmineUserByEmail(assignedTo.getPartner().getEmailAddress().getAddress());
      redmineIssue.setAssigneeId(redmineUser.getId());
      redmineIssue.setAssigneeName(redmineUser.getFullName());
    }

    if (createdBy != null
        && createdBy.getPartner() != null
        && createdBy.getPartner().getEmailAddress() != null) {
      User redmineUser =
          findRedmineUserByEmail(createdBy.getPartner().getEmailAddress().getAddress());
      redmineIssue.setAuthorId(redmineUser.getId());
      redmineIssue.setAuthorName(redmineUser.getFullName());
    }

    try {
      redmineIssue.setProjectId(redmineProject.getId());
      redmineIssue.setTargetVersion(
          findRedmineVersion(teamTask.getFixedVersion(), redmineIssue.getProjectId()));
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }

    redmineIssue.setParentId(parentId);

    BigDecimal budgetedTime = teamTask.getBudgetedTime();
    redmineIssue.setEstimatedHours(budgetedTime != null ? budgetedTime.floatValue() : null);

    LocalDate taskEndDate = teamTask.getTaskEndDate();

    if (taskEndDate != null) {
      redmineIssue.setClosedOn(Date.from(taskEndDate.atStartOfDay(ZoneId.of("UTC")).toInstant()));
    }

    redmineIssue.setCreatedOn(
        Date.from(teamTask.getCreatedOn().atZone(ZoneId.of("UTC")).toInstant()));

    LocalDateTime updatedOn = teamTask.getUpdatedOn();
    redmineIssue.setUpdatedOn(
        updatedOn != null ? Date.from(updatedOn.atZone(ZoneId.of("UTC")).toInstant()) : null);

    String redmineStatus = fieldMap.get(selectionMap.get(teamTask.getStatus()));

    if (redmineStatus != null) {
      redmineIssue.setStatusId(redmineStatusMap.get(redmineStatus));
      redmineIssue.setStatusName(redmineStatus);
    } else {
      redmineIssue.setStatusId(redmineStatusMap.get("New"));
      redmineIssue.setStatusName("New");
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
          teamTask.getId().toString(),
          null,
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_WITH_DEFAULT_STATUS));
    }

    String redminePriority = fieldMap.get(selectionMap.get(teamTask.getPriority()));

    if (redminePriority != null) {
      redmineIssue.setPriorityId(redminePriorityMap.get(redminePriority));
    } else {
      redmineIssue.setPriorityId(redminePriorityMap.get("Normal"));
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
          teamTask.getId().toString(),
          null,
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_WITH_DEFAULT_PRIORITY));
    }

    try {
      redmineIssue.setTracker(findRedmineTracker(trackerName));
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }
  }

  @Transactional
  public void saveRedmineIssue(Issue redmineIssue, TeamTask teamTask) {

    try {
      redmineIssue.setTransport(redmineTransport);

      if (redmineIssue.getId() == null) {
        redmineIssue = redmineIssue.create();
        teamTask.setRedmineId(redmineIssue.getId());
        teamTaskRepo.save(teamTask);
      }

      redmineIssue.getCustomFieldByName("OS Id").setValue(teamTask.getId().toString());
      redmineIssue.getCustomFieldByName("Product").setValue(product.getCode());
      redmineIssue
          .getCustomFieldByName("Prestation refusée/annulée")
          .setValue(teamTask.getIsTaskRefused() ? "1" : "0");
      redmineIssue
          .getCustomFieldByName("Date d'échéance (INTERNE)")
          .setValue(teamTask.getTaskDate() != null ? teamTask.getTaskDate().toString() : null);
      redmineIssue
          .getCustomFieldByName("Temps estimé (INTERNE)")
          .setValue(
              teamTask.getTotalPlannedHrs() != null
                  ? teamTask.getTotalPlannedHrs().toString()
                  : null);

      redmineIssue.update();

      teamTask.addRedminebatchSetItem(batch);

      onSuccess.accept(teamTask);
      success++;
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);
      fail++;

      if (e.getMessage().equals(REDMINE_ISSUE_ASSIGNEE_INVALID)) {
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_TEAMTASK_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
            teamTask.getId().toString(),
            null,
            I18n.get(IMessage.REDMINE_SYNC_ERROR_ASSIGNEE_IS_NOT_VALID));
      }
    }
  }
}
