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
import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
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
import com.taskadapter.redmineapi.bean.TimeEntry;
import com.taskadapter.redmineapi.bean.TimeEntryActivity;
import com.taskadapter.redmineapi.bean.User;
import com.taskadapter.redmineapi.internal.Transport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineExportTimeSpentServiceImpl extends RedmineSyncService
    implements RedmineExportTimeSpentService {

  protected TimesheetLineRepository timesheetLineRepo;

  @Inject
  public RedmineExportTimeSpentServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo,
      TimesheetLineRepository timesheetLineRepo) {

    super(userRepo, projectRepo, productRepo, teamTaskRepo, projectCategoryRepo, partnerRepo);
    this.timesheetLineRepo = timesheetLineRepo;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected TimeEntryActivity defaultTimeEntryActivity;
  protected Project redmineProject;
  protected Issue redmineIssue;
  protected Product product;

  @Override
  @SuppressWarnings("unchecked")
  public void exportTimeSpent(
      List<TimesheetLine> timesheetLineList, HashMap<String, Object> paramsMap) {

    if (timesheetLineList != null && !timesheetLineList.isEmpty()) {
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

      try {
        this.defaultTimeEntryActivity = redmineTimeEntryManager.getTimeEntryActivities().get(0);
      } catch (RedmineException e) {
        TraceBackService.trace(e, "", batch.getId());
      }

      for (TimesheetLine timesheetLine : timesheetLineList) {
        try {
          com.axelor.apps.project.db.Project project = timesheetLine.getProject();
          this.redmineProject = project != null ? findRedmineProject(project.getRedmineId()) : null;

          if (redmineProject == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
                timesheetLine.getId().toString(),
                null,
                I18n.get(IMessage.REDMINE_SYNC_REDMINE_PROJECT_NOT_FOUND));

            fail++;
            continue;
          }

          TeamTask teamTask = timesheetLine.getTeamTask();
          this.redmineIssue = teamTask != null ? findRedmineIssue(teamTask.getRedmineId()) : null;

          if (redmineIssue == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
                timesheetLine.getId().toString(),
                null,
                I18n.get(IMessage.REDMINE_SYNC_ISSUE_NOT_FOUND));

            fail++;
            continue;
          }

          this.product = timesheetLine.getProduct();

          if (product == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
                timesheetLine.getId().toString(),
                null,
                I18n.get(IMessage.REDMINE_SYNC_PRODUCT_FIELD_NOT_SET));

            fail++;
            continue;
          }
        } catch (RedmineException e) {
          TraceBackService.trace(e, "", batch.getId());
        }

        createRedmineSpentTime(timesheetLine);
      }
    }

    String resultStr =
        String.format(
            "ABS Timesheetline -> Redmine SpentTime : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  public void createRedmineSpentTime(TimesheetLine timesheetLine) {

    try {
      TimeEntry redmineTimeEntry = null;
      Integer redmineId = timesheetLine.getRedmineId();

      if (redmineId != null && redmineId != 0) {
        redmineTimeEntry = redmineTimeEntryManager.getTimeEntry(redmineId);
      }

      if (redmineTimeEntry == null) {
        redmineTimeEntry = new TimeEntry(redmineTransport);
      } else if (lastBatchUpdatedOn != null) {
        LocalDateTime osUpdatedOn = timesheetLine.getUpdatedOn();
        LocalDateTime redmineUpdatedOn =
            redmineTimeEntry
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

      LOG.debug("Exporting timesheetline: " + timesheetLine.getId());

      this.setRedmineSpentTimeFields(redmineTimeEntry, timesheetLine);
      this.saveRedmineSpentTime(redmineTimeEntry, timesheetLine);
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);

      if (e.getMessage().equals(REDMINE_SERVER_404_NOT_FOUND)) {
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
            timesheetLine.getId().toString(),
            null,
            I18n.get(IMessage.REDMINE_SYNC_ERROR_RECORD_NOT_FOUND));
      }
    }
  }

  public void setRedmineSpentTimeFields(TimeEntry redmineTimeEntry, TimesheetLine timesheetLine) {

    redmineTimeEntry.setProjectId(redmineProject.getId());
    redmineTimeEntry.setIssueId(redmineIssue.getId());
    redmineTimeEntry.setComment(timesheetLine.getComments());
    redmineTimeEntry.setSpentOn(
        Date.from(timesheetLine.getDate().atStartOfDay(ZoneId.of("UTC")).toInstant()));

    BigDecimal duration = timesheetLine.getDuration();

    if (duration != null) {
      redmineTimeEntry.setHours(duration.floatValue());
    }

    redmineTimeEntry.setActivityId(defaultTimeEntryActivity.getId());

    com.axelor.auth.db.User user = timesheetLine.getUser();

    if (user != null && user.getPartner() != null && user.getPartner().getEmailAddress() != null) {
      User redmineUser = findRedmineUserByEmail(user.getPartner().getEmailAddress().getAddress());

      if (redmineUser != null) {
        redmineTimeEntry.setUserId(redmineUser.getId());
        redmineTimeEntry.setUserName(redmineUser.getFullName());
      }
    }
  }

  @Transactional
  public void saveRedmineSpentTime(TimeEntry redmineTimeEntry, TimesheetLine timesheetLine) {

    try {

      if (redmineTimeEntry.getId() == null) {
        redmineTimeEntry = redmineTimeEntry.create();
        timesheetLine.setRedmineId(redmineTimeEntry.getId());
        timesheetLineRepo.save(timesheetLine);
      }

      redmineTimeEntry.getCustomField("Product").setValue(product.getCode());
      redmineTimeEntry.getCustomField("OS Id").setValue(timesheetLine.getId().toString());

      redmineTimeEntry.update();

      onSuccess.accept(timesheetLine);
      success++;
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);
      fail++;
    }
  }
}
