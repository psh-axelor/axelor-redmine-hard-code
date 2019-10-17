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
import com.axelor.apps.base.db.Partner;
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
import com.taskadapter.redmineapi.bean.Membership;
import com.taskadapter.redmineapi.bean.Role;
import com.taskadapter.redmineapi.bean.Tracker;
import com.taskadapter.redmineapi.internal.Transport;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineExportProjectServiceImpl extends RedmineSyncService
    implements RedmineExportProjectService {

  @Inject
  public RedmineExportProjectServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo) {
    super(userRepo, projectRepo, productRepo, teamTaskRepo, projectCategoryRepo, partnerRepo);
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected List<Role> roles;

  @Override
  @SuppressWarnings("unchecked")
  public void exportProject(
      List<Project> projectList,
      HashMap<String, Object> paramsMap,
      HashMap<String, String> exportFieldMap) {

    if (projectList != null && !projectList.isEmpty()) {
      this.onError = (Consumer<Throwable>) paramsMap.get("onError");
      this.onSuccess = (Consumer<Object>) paramsMap.get("onSuccess");
      this.batch = (Batch) paramsMap.get("batch");
      this.redmineIssueManager = (IssueManager) paramsMap.get("redmineIssueManager");
      this.redmineUserManager = (UserManager) paramsMap.get("redmineUserManager");
      this.redmineProjectManager = (ProjectManager) paramsMap.get("redmineProjectManager");
      this.redmineTransport = (Transport) paramsMap.get("redmineTransport");
      this.errorObjList = (List<Object[]>) paramsMap.get("errorObjList");
      this.lastBatchUpdatedOn = (LocalDateTime) paramsMap.get("lastBatchUpdatedOn");
      this.fieldMap = exportFieldMap;

      try {
        this.roles = redmineUserManager.getRoles();
      } catch (RedmineException e) {
        TraceBackService.trace(e, "", batch.getId());
      }

      projectList.sort(
          new Comparator<Project>() {
            @Override
            public int compare(Project arg0, Project arg1) {
              if (arg1.getParentProject() == null) {
                return 0;
              }
              return -1;
            }
          });

      for (Project project : projectList) {
        createRedmineProject(project);
      }
    }

    String resultStr =
        String.format("ABS Project -> Redmine Project : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  public void createRedmineProject(Project project) {

    try {
      com.taskadapter.redmineapi.bean.Project redmineProject = null;
      Integer redmineId = project.getRedmineId();

      if (redmineId != null) {
        redmineProject = redmineProjectManager.getProjectById(redmineId);
      }

      if (redmineProject == null) {
        redmineProject = new com.taskadapter.redmineapi.bean.Project(redmineTransport);
      } else if (lastBatchUpdatedOn != null) {
        LocalDateTime osUpdatedOn = project.getUpdatedOn();
        LocalDateTime redmineUpdatedOn =
            redmineProject
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

      LOG.debug("Exporting project: " + project.getCode());

      this.setRedmineProjectFields(redmineProject, project);
      this.saveRedmineProject(redmineProject, project);
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);

      if (e.getMessage().equals(REDMINE_SERVER_404_NOT_FOUND)) {
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
            project.getId().toString(),
            null,
            I18n.get(IMessage.REDMINE_SYNC_ERROR_RECORD_NOT_FOUND));
      }
    }
  }

  public void setRedmineProjectFields(
      com.taskadapter.redmineapi.bean.Project redmineProject, Project project) {

    redmineProject.setIdentifier(project.getCode().toLowerCase().trim().replace(" ", ""));
    redmineProject.setName(project.getName());
    redmineProject.setDescription(project.getDescription());

    Collection<Tracker> trackers = new ArrayList<Tracker>();
    Set<ProjectCategory> projectCategorySet = project.getProjectCategorySet();

    if (projectCategorySet != null && !projectCategorySet.isEmpty()) {

      for (ProjectCategory projectCategory : projectCategorySet) {

        try {
          String trackerName = fieldMap.get(projectCategory.getName());
          Tracker tracker = findRedmineTracker(trackerName);

          if (tracker != null) {
            trackers.add(tracker);
          }
        } catch (RedmineException e) {
          TraceBackService.trace(e, "", batch.getId());
        }
      }
      redmineProject.addTrackers(trackers);
    }

    Project parentProject = project.getParentProject();

    if (parentProject != null) {
      Integer parentProjectRedmineId = parentProject.getRedmineId();

      if (parentProjectRedmineId != null && parentProjectRedmineId != 0) {
        redmineProject.setParentId(parentProject.getRedmineId());
      } else {
        setErrorLog(
            I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
            I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
            project.getCode(),
            null,
            I18n.get(IMessage.REDMINE_SYNC_REDMINE_PARENT_PROJECT_NOT_FOUND));
      }
    }

    this.redmineCustomFieldsMap = new HashMap<>();

    Partner clientPartner = project.getClientPartner();

    if (clientPartner != null) {
      redmineCustomFieldsMap.put("Customer Code", clientPartner.getPartnerSeq());
    } else {
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
          project.getCode(),
          null,
          I18n.get(IMessage.REDMINE_SYNC_CLIENT_PARTNER_FIELD_NOT_SET));
    }

    redmineCustomFieldsMap.put("Invoiceable", project.getIsInvoiceable() ? "1" : "0");
    redmineCustomFieldsMap.put("OS Id", project.getId().toString());

    Integer invoicingSequence = project.getInvoicingSequenceSelect();

    if (invoicingSequence != null) {
      redmineCustomFieldsMap.put(
          "Invoicing Type",
          invoicingSequence.equals(ProjectRepository.INVOICING_SEQ_EMPTY)
              ? "Empty"
              : invoicingSequence.equals(ProjectRepository.INVOICING_SEQ_INVOICE_PRE_TASK)
                  ? "Pre-invoiced"
                  : invoicingSequence.equals(ProjectRepository.INVOICING_SEQ_INVOICE_PRE_TASK)
                      ? "Post-invoiced"
                      : null);
    } else {
      setErrorLog(
          I18n.get(IMessage.REDMINE_SYNC_PROJECT_ERROR),
          I18n.get(IMessage.REDMINE_SYNC_EXPORT_ERROR),
          project.getCode(),
          null,
          I18n.get(IMessage.REDMINE_SYNC_INVOICING_SEQUENCE_SELECT_FIELD_NOT_SET));
    }
  }

  @Transactional
  public void saveRedmineProject(
      com.taskadapter.redmineapi.bean.Project redmineProject, Project project) {

    try {

      if (redmineProject.getId() == null) {
        redmineProject = redmineProject.create();
        project.setRedmineId(redmineProject.getId());
        projectRepo.save(project);
      }

      Set<User> membersUserSet = project.getMembersUserSet();

      for (User user : membersUserSet) {
        Partner partner = user.getPartner();

        if (partner != null && partner.getEmailAddress() != null) {
          com.taskadapter.redmineapi.bean.User redmineUser =
              findRedmineUserByEmail(partner.getEmailAddress().getAddress());

          if (redmineUser != null) {
            List<Membership> memberships =
                redmineProjectManager.getProjectMembers(redmineProject.getId());

            if (!memberships.stream().anyMatch(m -> m.getUserId() == redmineUser.getId())) {
              Membership membership =
                  new Membership(redmineTransport, redmineProject, redmineUser.getId())
                      .addRoles(roles);
              membership.create();
            }
          }
        }
      }

      setRedmineCustomFieldValues(redmineProject.getCustomFields());

      if (!redmineProject.getStatus().equals(REDMINE_PROJECT_STATUS_CLOSED)) {
        redmineProject.update();
      }

      onSuccess.accept(project);
      success++;
    } catch (Exception e) {
      TraceBackService.trace(e, "", batch.getId());
      onError.accept(e);
      fail++;
    }
  }
}
