/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
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
package com.axelor.apps.redmine.sync.service;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.project.db.ProjectCategory;
import com.axelor.apps.project.db.repo.ProjectCategoryRepository;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.auth.db.AuditableModel;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.team.db.TeamTask;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.IssueManager;
import com.taskadapter.redmineapi.ProjectManager;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.TimeEntryManager;
import com.taskadapter.redmineapi.UserManager;
import com.taskadapter.redmineapi.bean.CustomField;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.Tracker;
import com.taskadapter.redmineapi.bean.Version;
import com.taskadapter.redmineapi.internal.Transport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class RedmineSyncService {

  protected UserRepository userRepo;
  protected ProjectRepository projectRepo;
  protected ProductRepository productRepo;
  protected TeamTaskRepository teamTaskRepo;
  protected ProjectCategoryRepository projectCategoryRepo;
  protected PartnerRepository partnerRepo;

  @Inject
  public RedmineSyncService(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      ProjectCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo) {

    this.userRepo = userRepo;
    this.projectRepo = projectRepo;
    this.productRepo = productRepo;
    this.teamTaskRepo = teamTaskRepo;
    this.projectCategoryRepo = projectCategoryRepo;
    this.partnerRepo = partnerRepo;
  }

  public static String result = "";
  protected static int success = 0, fail = 0;
  protected Consumer<Object> onSuccess;
  protected Consumer<Throwable> onError;

  protected Batch batch;
  protected IssueManager redmineIssueManager;
  protected UserManager redmineUserManager;
  protected ProjectManager redmineProjectManager;
  protected TimeEntryManager redmineTimeEntryManager;
  protected Transport redmineTransport;
  protected List<Object[]> errorObjList;
  protected Map<String, Object> redmineCustomFieldsMap;
  protected LocalDateTime lastBatchUpdatedOn;

  protected HashMap<String, String> selectionMap;
  protected HashMap<String, String> fieldMap;

  // IMPORT VARIABLES
  public static final Integer REDMINE_PROJECT_STATUS_CLOSED = 5;

  private Map<Integer, User> userMap = new HashMap<>();
  protected HashMap<Integer, com.taskadapter.redmineapi.bean.User> redmineUserMap = new HashMap<>();
  protected HashMap<Integer, Project> projectMap = new HashMap<>();
  protected HashMap<String, Product> productMap = new HashMap<>();
  protected HashMap<Integer, TeamTask> issueMap = new HashMap<>();
  protected HashMap<String, ProjectCategory> projectCategoryMap = new HashMap<>();
  protected HashMap<String, Partner> partnerMap = new HashMap<>();

  // EXPORT VARIABLES
  protected HashMap<Integer, com.taskadapter.redmineapi.bean.Project> redmineProjectMap =
      new HashMap<>();
  protected HashMap<Integer, Issue> redmineIssueMap = new HashMap<>();
  protected HashMap<String, com.taskadapter.redmineapi.bean.User> redmineUserMailMap =
      new HashMap<>();
  protected HashMap<String, Tracker> redmineTrackerMap = new HashMap<>();
  protected HashMap<String, Version> redmineVersionMap = new HashMap<>();

  public static final String REDMINE_SERVER_404_NOT_FOUND =
      "Server returned '404 not found'. response body:";
  public static final String REDMINE_ISSUE_ASSIGNEE_INVALID = "Assignee is invalid\n";

  // IMPORT SERVICE METHODS

  protected void setCreatedByUser(AuditableModel obj, User objUser, String methodName) {

    if (objUser == null) {
      return;
    }

    try {
      Method createdByMethod = AuditableModel.class.getDeclaredMethod(methodName, User.class);
      invokeMethod(createdByMethod, obj, objUser);
    } catch (NoSuchMethodException | SecurityException | IllegalArgumentException e) {
      TraceBackService.trace(e);
    }
  }

  public void setLocalDateTime(AuditableModel obj, Date objDate, String methodName) {

    try {
      Method createdOnMethod =
          AuditableModel.class.getDeclaredMethod(methodName, LocalDateTime.class);
      invokeMethod(
          createdOnMethod,
          obj,
          objDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
    } catch (NoSuchMethodException | SecurityException | IllegalArgumentException e) {
      TraceBackService.trace(e);
    }
  }

  protected void invokeMethod(Method method, AuditableModel obj, Object value) {

    try {
      method.setAccessible(true);
      method.invoke(obj, value);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      TraceBackService.trace(e);
    } finally {
      method.setAccessible(false);
    }
  }

  public void setRedmineCustomFieldsMap(Collection<CustomField> customFieldSet) {

    this.redmineCustomFieldsMap = new HashMap<>();

    if (customFieldSet != null && !customFieldSet.isEmpty()) {

      for (CustomField customField : customFieldSet) {
        redmineCustomFieldsMap.put(customField.getName(), customField);
      }
    }
  }

  public User findOpenSuiteUser(
      Integer redmineUserId, com.taskadapter.redmineapi.bean.User redmineUser)
      throws RedmineException {

    if (redmineUser != null) {
      redmineUserId = redmineUser.getId();
    }

    if (redmineUserId == null) {
      return null;
    }

    if (userMap.containsKey(redmineUserId)) {
      return userMap.get(redmineUserId);
    }
    if (redmineUser == null) {
      redmineUser = redmineUserManager.getUserById(redmineUserId);
    }
    String mail = redmineUser.getMail();
    User user = userRepo.all().filter("self.partner.emailAddress.address = ?1", mail).fetchOne();

    userMap.put(redmineUserId, user);

    return user;
  }

  public com.taskadapter.redmineapi.bean.User findRedmineUserById(Integer redmineUserId)
      throws RedmineException {

    if (redmineUserMap.containsKey(redmineUserId)) {
      return redmineUserMap.get(redmineUserId);
    }

    com.taskadapter.redmineapi.bean.User redmineUser =
        redmineUserManager.getUserById(redmineUserId);
    redmineUserMap.put(redmineUserId, redmineUser);

    return redmineUser;
  }

  public Project findOpenSuiteProject(Integer redmineProjectId) throws RedmineException {

    if (projectMap.containsKey(redmineProjectId)) {
      return projectMap.get(redmineProjectId);
    }

    Project project = projectRepo.findByRedmineId(redmineProjectId);
    projectMap.put(redmineProjectId, project);

    return project;
  }

  public Product findOpenSuiteProduct(CustomField redmineProduct) {

    String value = redmineProduct.getValue();

    if (value == null || value.equals("")) {
      return null;
    }

    if (productMap.containsKey(value)) {
      return productMap.get(value);
    }

    Product product = productRepo.findByCode(value);
    productMap.put(value, product);

    return product;
  }

  public TeamTask findOpenSuiteTask(Integer redmineIssueId) {

    if (issueMap.containsKey(redmineIssueId)) {
      return issueMap.get(redmineIssueId);
    }

    TeamTask teamTask = teamTaskRepo.findByRedmineId(redmineIssueId);
    issueMap.put(redmineIssueId, teamTask);

    return teamTask;
  }

  public ProjectCategory findOpenSuiteProjectCategory(String trackerName) {

    if (trackerName == null) {
      return null;
    }

    if (projectCategoryMap.containsKey(trackerName)) {
      return projectCategoryMap.get(trackerName);
    }

    ProjectCategory projectCategory = projectCategoryRepo.findByName(trackerName);
    projectCategoryMap.put(trackerName, projectCategory);

    return projectCategory;
  }

  public Partner findOpenSuitePartner(String redminePartnerCode) {

    if (partnerMap.containsKey(redminePartnerCode)) {
      return partnerMap.get(redminePartnerCode);
    }

    Partner partner = partnerRepo.findByReference(redminePartnerCode);
    partnerMap.put(redminePartnerCode, partner);

    return partner;
  }

  // EXPORT SERVICE METHODS

  public com.taskadapter.redmineapi.bean.User findRedmineUserByEmail(String mail) {

    if (redmineUserMailMap.containsKey(mail)) {
      return redmineUserMailMap.get(mail);
    }

    try {
      Map<String, String> params = new HashMap<String, String>();
      params.put("name", mail);
      List<com.taskadapter.redmineapi.bean.User> redmineUserList =
          redmineUserManager.getUsers(params).getResults();
      com.taskadapter.redmineapi.bean.User redmineUser =
          redmineUserList != null && !redmineUserList.isEmpty() ? redmineUserList.get(0) : null;

      redmineUserMailMap.put(mail, redmineUser);

      return redmineUser;
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }

    return null;
  }

  public void setRedmineCustomFieldValues(Collection<CustomField> customFields) {

    if (customFields != null && !customFields.isEmpty()) {

      for (CustomField customField : customFields) {

        if (redmineCustomFieldsMap != null
            && !redmineCustomFieldsMap.isEmpty()
            && redmineCustomFieldsMap.get(customField.getName()) != null) {
          customField.setValue(redmineCustomFieldsMap.get(customField.getName()).toString());
        }
      }
    }
  }

  public com.taskadapter.redmineapi.bean.Project findRedmineProject(Integer redmineId)
      throws RedmineException {

    if (redmineProjectMap.containsKey(redmineId)) {
      return redmineProjectMap.get(redmineId);
    }

    com.taskadapter.redmineapi.bean.Project redmineProject =
        redmineProjectManager.getProjectById(redmineId);
    redmineProjectMap.put(redmineId, redmineProject);

    return redmineProject;
  }

  public Issue findRedmineIssue(Integer redmineId) throws RedmineException {

    if (redmineIssueMap.containsKey(redmineId)) {
      return redmineIssueMap.get(redmineId);
    }

    Issue redmineIssue = redmineIssueManager.getIssueById(redmineId);
    redmineIssueMap.put(redmineId, redmineIssue);

    return redmineIssue;
  }

  public Tracker findRedmineTracker(String name) throws RedmineException {

    if (redmineTrackerMap.containsKey(name)) {
      return redmineTrackerMap.get(name);
    }

    List<Tracker> trackers = redmineIssueManager.getTrackers();

    for (Tracker tracker : trackers) {
      redmineTrackerMap.put(tracker.getName(), tracker);
    }

    return redmineTrackerMap.get(name);
  }

  public Version findRedmineVersion(String versionName, Integer projectId) throws RedmineException {

    String key = versionName + projectId.toString();

    if (redmineVersionMap.containsKey(key)) {
      return redmineVersionMap.get(key);
    }

    List<Version> redmineVersionList = redmineProjectManager.getVersions(projectId);

    for (Version version : redmineVersionList) {
      redmineVersionMap.put(version.getName() + version.getProjectId().toString(), version);
    }

    return redmineVersionMap.get(key);
  }

  // COMMON METHODS

  public void setErrorLog(
      String object, String operation, String osRef, String redmineRef, String message) {

    errorObjList.add(new Object[] {object, operation, osRef, redmineRef, message});
  }
}
