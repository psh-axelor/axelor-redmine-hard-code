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
import com.axelor.apps.hr.db.Timesheet;
import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.apps.hr.db.repo.TimesheetRepository;
import com.axelor.apps.hr.service.timesheet.TimesheetService;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.auth.db.User;
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
import com.taskadapter.redmineapi.bean.CustomField;
import com.taskadapter.redmineapi.bean.TimeEntry;
import com.taskadapter.redmineapi.internal.Transport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineImportTimeSpentServiceImpl extends RedmineSyncService
    implements RedmineImportTimeSpentService {

  protected TimesheetLineRepository timesheetLineRepo;
  protected TimesheetRepository timesheetRepo;
  protected TimesheetService timesheetService;

  @Inject
  public RedmineImportTimeSpentServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo,
      TimesheetLineRepository timesheetLineRepo,
      TimesheetRepository timesheetRepo,
      TimesheetService timesheetService) {

    super(userRepo, projectRepo, productRepo, teamTaskRepo, projectCategoryRepo, partnerRepo);
    this.timesheetLineRepo = timesheetLineRepo;
    this.timesheetRepo = timesheetRepo;
    this.timesheetService = timesheetService;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected Project project;
  protected TeamTask teamTask;
  protected Product product;

  @Override
  @SuppressWarnings("unchecked")
  public void importTimeSpent(
      List<TimeEntry> redmineTimeEntryList, HashMap<String, Object> paramsMap) {

    if (redmineTimeEntryList != null && !redmineTimeEntryList.isEmpty()) {
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

      Comparator<TimeEntry> compareByDate =
          (TimeEntry o1, TimeEntry o2) -> o1.getSpentOn().compareTo(o2.getSpentOn());
      Collections.sort(redmineTimeEntryList, compareByDate);

      for (TimeEntry redmineTimeEntry : redmineTimeEntryList) {

        // Error and don't import if project not found
        try {
          this.project = findOpenSuiteProject(redmineTimeEntry.getProjectId());

          if (project == null) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
                I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
                null,
                redmineTimeEntry.getId().toString(),
                I18n.get(IMessage.REDMINE_SYNC_PROJECT_NOT_FOUND));

            fail++;
            continue;
          }
        } catch (RedmineException e) {
          TraceBackService.trace(e, "", batch.getId());
        }

        // Error and don't import if teamtask not found
        if (redmineTimeEntry.getIssueId() != null) {
          this.teamTask = findOpenSuiteTask(redmineTimeEntry.getIssueId());
        } else {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
              null,
              redmineTimeEntry.getId().toString(),
              I18n.get(IMessage.REDMINE_SYNC_TEAM_TASK_NOT_FOUND));

          fail++;
          continue;
        }

        // Error and don't import if product not found
        CustomField redmineProduct = redmineTimeEntry.getCustomField("Product");
        this.product = findOpenSuiteProduct(redmineProduct);

        if (product == null) {
          setErrorLog(
              I18n.get(IMessage.REDMINE_SYNC_TIMESHEET_LINE_ERROR),
              I18n.get(IMessage.REDMINE_SYNC_IMPORT_ERROR),
              null,
              redmineTimeEntry.getId().toString(),
              I18n.get(IMessage.REDMINE_SYNC_CUSTOM_FIELD_PRODUCT_NOT_FOUND));

          fail++;
          continue;
        }

        createOpenSuiteTimesheetLine(redmineTimeEntry);
      }
    }

    String resultStr =
        String.format(
            "Redmine SpentTime -> ABS Timesheetline : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  @Transactional
  public void createOpenSuiteTimesheetLine(TimeEntry redmineTimeEntry) {

    CustomField osId = redmineTimeEntry.getCustomField("OS Id");
    String osIdValue = osId.getValue();
    TimesheetLine timesheetLine;

    if (osId != null && osIdValue != null && !osIdValue.equals("0")) {
      timesheetLine = timesheetLineRepo.find(Long.parseLong(osIdValue));
    } else {
      timesheetLine = timesheetLineRepo.findByRedmineId(redmineTimeEntry.getId());
    }

    if (timesheetLine == null) {
      timesheetLine = new TimesheetLine();
    } else if (timesheetLine != null && lastBatchUpdatedOn != null) {
      LocalDateTime redmineUpdatedOn =
          redmineTimeEntry
              .getUpdatedOn()
              .toInstant()
              .atZone(ZoneId.systemDefault())
              .toLocalDateTime();

      if (redmineUpdatedOn.isBefore(lastBatchUpdatedOn)
          || (timesheetLine.getUpdatedOn().isAfter(lastBatchUpdatedOn)
              && timesheetLine.getUpdatedOn().isAfter(redmineUpdatedOn))) {
        return;
      }
    }

    LOG.debug("Importing time entry: " + redmineTimeEntry.getId());

    this.setTimesheetLineFields(timesheetLine, redmineTimeEntry);

    if (timesheetLine.getTimesheet() != null) {

      try {

        if (timesheetLine.getId() == null) {
          timesheetLine.addBatchSetItem(batch);
        }

        timesheetLineRepo.save(timesheetLine);
        onSuccess.accept(timesheetLine);
        success++;

        redmineTimeEntry.setTransport(redmineTransport);
        osId.setValue(timesheetLine.getId().toString());
        redmineTimeEntry.update();
      } catch (Exception e) {
        onError.accept(e);
        fail++;
        TraceBackService.trace(e, "", batch.getId());
      }
    }
  }

  @Transactional
  public void setTimesheetLineFields(TimesheetLine timesheetLine, TimeEntry redmineTimeEntry) {

    try {
      User user = findOpenSuiteUser(redmineTimeEntry.getUserId(), null);

      if (user != null) {
        timesheetLine.setUser(user);
        setCreatedByUser(timesheetLine, user, "setCreatedBy");

        Timesheet timesheet =
            timesheetRepo
                .all()
                .filter(
                    "self.user = ?1 AND self.statusSelect = ?2",
                    user,
                    TimesheetRepository.STATUS_DRAFT)
                .order("-id")
                .fetchOne();

        LocalDate redmineSpentOn =
            redmineTimeEntry.getSpentOn().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

        if (timesheet == null) {
          timesheet = timesheetService.createTimesheet(user, redmineSpentOn, null);
          timesheetRepo.save(timesheet);
        } else if (timesheet.getFromDate().isAfter(redmineSpentOn)) {
          timesheet.setFromDate(redmineSpentOn);
        }

        timesheetLine.setTimesheet(timesheet);

        timesheetLine.setRedmineId(redmineTimeEntry.getId());
        timesheetLine.setProject(project);
        timesheetLine.setTeamTask(teamTask);
        timesheetLine.setProduct(product);
        timesheetLine.setComments(redmineTimeEntry.getComment());
        timesheetLine.setDuration(BigDecimal.valueOf(redmineTimeEntry.getHours()));
        timesheetLine.setDate(
            redmineTimeEntry.getSpentOn().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
      }

      setLocalDateTime(timesheetLine, redmineTimeEntry.getCreatedOn(), "setCreatedOn");
      setLocalDateTime(timesheetLine, redmineTimeEntry.getUpdatedOn(), "setUpdatedOn");
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }
  }
}
